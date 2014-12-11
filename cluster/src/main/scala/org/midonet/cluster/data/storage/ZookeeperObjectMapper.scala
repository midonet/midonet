/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.cluster.data.storage

import java.io.StringWriter
import java.lang.{Long => JLong}
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.{ConcurrentModificationException, List => JList}

import scala.async.Async.async
import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.collection.{Set, mutable}
import scala.concurrent.{ExecutionContext, Future, Promise}

import com.google.common.annotations.VisibleForTesting
import com.google.common.collect.ArrayListMultimap
import com.google.protobuf.{Message, TextFormat}
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.api.transaction.CuratorTransactionFinal
import org.apache.curator.framework.api.{BackgroundCallback, CuratorEvent, CuratorEventType}
import org.apache.curator.utils.EnsurePath
import org.apache.zookeeper.KeeperException.{BadVersionException, NoNodeException, NodeExistsException}
import org.apache.zookeeper.OpResult.ErrorResult
import org.apache.zookeeper.Watcher.Event.EventType.NodeDataChanged
import org.apache.zookeeper.data.Stat
import org.apache.zookeeper.{CreateMode, KeeperException, WatchedEvent, Watcher}
import org.codehaus.jackson.JsonFactory
import org.codehaus.jackson.map.ObjectMapper
import org.slf4j.LoggerFactory
import rx.Observable

import org.midonet.cluster.data.storage.FieldBinding.DeleteAction
import org.midonet.cluster.data.{Obj, ObjId}
import org.midonet.util.reactivex._

/**
 * Object mapper that uses Zookeeper as a data store. Maintains referential
 * integrity through the use of field bindings, which must be declared
 * prior to any CRUD operations through the use of declareBinding().
 *
 * For example:
 *
 * declareBinding(Port.class, "bridgeId", CLEAR,
 * Bridge.class, "portIds", ERROR);
 *
 * This indicates that Port.bridgeId is a reference to Bridge.id
 * field, and that Bridge.portIds is a list of references to Port.id.
 * Each named field is assumed to be a reference (or list of references)
 * to the other classes "id" field (all objects must have a field named
 * "id", although it may be of any type.
 *
 * Whether the specified field is a single reference or list of references
 * is determined by reflectively examining the field to see whether its
 * type implements java.util.List.
 *
 * Consequently, when a port is created or updated with a new bridgeId
 * value, its id will be added to the corresponding bridge's portIds list.
 * CLEAR indicates that when a port is deleted its ID will be removed
 * from the portIds list of the bridge referenced by its bridgeId field.
 *
 * Likewise, when a bridge is created, the bridgeId field of any ports
 * referenced by portIds will be set to that bridge's ID, and when a bridge
 * is updated, ports no longer referenced by portIds will have their
 * bridgeId fields cleared, and ports newly referenced will have their
 * bridgeId fields set to the bridge's id. ERROR indicates that it is an
 * error to attempt to delete a bridge while its portIds field contains
 * references (i.e., while it has ports).
 *
 * Furthermore, if an object has a single-reference (non-list) field with
 * a non-null value, it is an error to create or update a third object in
 * a way that would cause that reference to be overwritten. For example, if
 * a port has a non-null bridge ID, then it is an error to attempt to create
 * another bridge whose portIds field contains that port's ID, as this would
 * effectively steal the port away from another bridge.
 *
 * A binding may be used to link two instances of the same type, as in the
 * case of linking ports:
 *
 * declareBinding(Port.class, "peerId", CLEAR,
 * Port.class, "peerId", CLEAR);
 *
 * DATA SET VERSIONING:
 * The data sets stored in the storage are versioned by monotonically increasing
 * ID numbers. When the storage is flushed, ZOOM just bumps the data set version
 * by 1, keeps the data set and instead starts persisting data under a new path
 * with the the new version number.
 *
 * A new ZOOM instance checks for the version number upon getting built. If it
 * finds one, it sets its data set version number to the found value so that a
 * new instance would be able to take over where the previous ZOOM instance left
 * off. In addition, upon initialization a ZOOM sets a watcher to the version
 * number node and it'd be notified if another ZOOM instances bumps the version
 * number to switch to the new version.
 */
class ZookeeperObjectMapper(
    private val basePathPrefix: String,
    private val curator: CuratorFramework) extends Storage {

    import org.midonet.cluster.data.storage.ZookeeperObjectMapper._
    @volatile private var built = false

    /* Monotonically increasing version number for the data set path under
     * which all the Storage contents are stored. When we "flush" the storage,
     * ZOOM actually just bumps the version number by 1, keeps the old data, and
     * starts persisting data in under the new version path.
     */
    private val version= new AtomicLong(INITIAL_ZOOM_DATA_SET_VERSION)
    private def basePath(version: Long = this.version.longValue) =
        s"$basePathPrefix/$version"
    private def versionNodePath = s"$basePathPrefix/$VERSION_NODE"

    private def locksPath(version: Long) = basePath(version) + "/zoomlocks/lock"

    private val allBindings = ArrayListMultimap.create[Class[_], FieldBinding]()

    private val executor = Executors.newCachedThreadPool()
    private implicit val executionContext =
        ExecutionContext.fromExecutorService(executor)

    private val classToIdGetter =
        new mutable.HashMap[Class[_], IdGetter]()
    private val simpleNameToClass =
        new mutable.HashMap[String, Class[_]]()

    private val instanceCaches = new mutable.HashMap[
        Class[_], TrieMap[String, InstanceSubscriptionCache[_]]]
    private val classCaches = new TrieMap[Class[_], ClassSubscriptionCache[_]]

    /**
     * Manages objects referenced by the primary target of a create, update,
     * or delete operation.
     *
     * Caches all objects loaded during the operation. This is necessary
     * because an object may reference another object more than once. If we
     * reload the object from Zookeeper to add the second backreference, the
     * object loaded from Zookeeper will not have the first backreference
     * added. Since updates are not incremental, the first backreference will
     * be lost.
     */
    private class TransactionManager(val version: Long) {
        private final val NEW_OBJ_VERSION = -1
        private val objCache = new mutable.HashMap[Key[_], ObjWithVersion[_]]()
        private val objsToDelete = new mutable.HashMap[Key[_], Int]()

        // Create an ephemeral node so that we can get Zookeeper's current
        // ZXID. This will allow us to determine if any of the nodes we read
        // have been modified since the TransactionManager was created, allowing
        // us to ensure a consistent read across multiple nodes.
        private val (lockPath: String, zxid: Long) = try {
            val path = curator.create().creatingParentsIfNeeded()
                              .withMode(CreateMode.EPHEMERAL_SEQUENTIAL)
                              .forPath(locksPath(version))
            val stat = new Stat()
            curator.getData.storingStatIn(stat).forPath(path)
            (path, stat.getCzxid)
        } catch {
            case ex: Exception => throw new InternalObjectMapperException(
                "Could not acquire current zxid.", ex)
        }

        private def getPath(clazz: Class[_], id: ObjId) = {
            ZookeeperObjectMapper.this.getPath(clazz, id, version)
        }

        /**
         * Gets the specified object from the internal cache. If not found,
         * loads it from Zookeeper and caches it.
         *
         * Returns None if the object has been marked for deletion.
         */
        private def cachedGet[T](clazz: Class[T], id: ObjId): Option[T] = {
            val key = Key(clazz, id)
            if (objsToDelete.contains(key))
                return None

            objCache.get(key) match {
                case Some(o) => Some(o.obj.asInstanceOf[T])
                case None =>
                    val objWithVersion = getWithVersion(clazz, id)
                    objCache(key) = objWithVersion
                    Some(objWithVersion.obj)
            }
        }

        private def getWithVersion[T](clazz: Class[T],
                                      id: ObjId): ObjWithVersion[T] = {
            val stat = new Stat()
            val path = getPath(clazz, id)
            val data = try {
                curator.getData.storingStatIn(stat).forPath(path)
            } catch {
                case nne: NoNodeException =>
                    throw new NotFoundException(clazz, id)
                case ex: Exception =>
                    throw new InternalObjectMapperException(ex)
            }

            if (stat.getMzxid > zxid) {
                throw new ConcurrentModificationException(
                    s"${clazz.getSimpleName} with ID $id was modified " +
                    "during the transaction.")
            }

            ObjWithVersion(deserialize(data, clazz), stat.getVersion)
        }

        /**
         * Update the cached object with the specified object.
         */
        private def updateCache[T](clazz: Class[T], id: ObjId, obj: Obj) {
            val key = Key(clazz, id)
            if (objsToDelete.contains(key))
                return

            objCache.get(key).foreach {
                entry => objCache(key) = ObjWithVersion(obj, entry.version)
            }
        }

        /**
         * Adds a backreference from the instance of thatClass whose ID is
         * thatId (thatObj) to thisId, using field thatField. Adds an
         * updated thatObj with back references added to the cache.
         *
         * If thatField is null, thatObj will be loaded and cached, but no
         * backreference will be added.
         */
        private def addBackreference(bdg: FieldBinding,
                                     thisId: ObjId, thatId: ObjId) {
            cachedGet(bdg.getReferencedClass, thatId).foreach { thatObj =>
                val updatedThatObj =
                    bdg.addBackReference(thatObj, thatId, thisId)
                updateCache(bdg.getReferencedClass, thatId,
                            updatedThatObj.asInstanceOf[Obj])
            }
        }

        /**
         * Removes a backreference from the instance of thatClass whose ID
         * is thatId (thatObj) to thisId, using field thatField. Adds an
         * updated thatObj with back references removed to the cache.
         *
         * ThatObj is assumed to exist.
         */
        private def clearBackreference(bdg: FieldBinding, thisId: ObjId,
                                       thatId: ObjId) {
            cachedGet(bdg.getReferencedClass, thatId).foreach { thatObj =>
                val updatedThatObj = bdg.clearBackReference(thatObj, thisId)
                updateCache(bdg.getReferencedClass, thatId,
                            updatedThatObj.asInstanceOf[Obj])
            }
        }

        def create(thisObj: Obj): Unit = {
            assert(isRegistered(thisObj.getClass))

            val thisId = getObjectId(thisObj)
            val key = Key(thisObj.getClass, thisId)
            assert(!objCache.contains(key))
            objCache(key) = ObjWithVersion(thisObj, NEW_OBJ_VERSION)

            for (bdg <- allBindings.get(thisObj.getClass).asScala;
                 thatId <- bdg.getFwdReferenceAsList(thisObj).asScala) {
                addBackreference(bdg, thisId, thatId)
            }
        }

        def update(obj: Obj, validator: UpdateValidator[Obj]): Unit = {
            val thisClass = obj.getClass
            assert(isRegistered(thisClass))

            val thisId = getObjectId(obj)
            val oldThisObj = cachedGet(thisClass, thisId).getOrElse(
                throw new NotFoundException(thisClass, thisId))

            // Invoke the validator/update callback if provided. If it returns
            // a modified object, use that in place of obj for the update.
            val newThisObj = if (validator == null) obj else {
                val modified = validator.validate(oldThisObj, obj)
                val thisObj = if (modified != null) modified else obj
                if (getObjectId(thisObj) != thisId) {
                    throw new IllegalArgumentException(
                        "Modifying newObj.id in UpdateValidator.validate() " +
                        "is not supported.")
                }
                thisObj
            }

            for (bdg <- allBindings.get(thisClass).asScala) {
                val oldThoseIds = bdg.getFwdReferenceAsList(oldThisObj).asScala
                val newThoseIds = bdg.getFwdReferenceAsList(newThisObj).asScala

                for (removedThatId <- oldThoseIds - newThoseIds)
                    clearBackreference(bdg, thisId, removedThatId)
                for (addedThatId <- newThoseIds - oldThoseIds)
                    addBackreference(bdg, thisId, addedThatId)
            }

            updateCache(thisClass, thisId, newThisObj)
        }

        /* If ignoresNeo (ignores deletion on non-existing objects) is true,
         * the method silently returns if the specified object does not exist /
         * has already been deleted.
         */
        def delete(clazz: Class[_], id: ObjId, ignoresNeo: Boolean) {
            assert(isRegistered(clazz))
            val key = Key(clazz, id)
            if (objsToDelete.contains(key)) {
                if (!ignoresNeo)
                    // The primary purpose of this is to throw up a red flag
                    // when the caller explicitly tries to delete the same
                    // object twice in a single multi() call, but this will
                    // throw an exception if an object is deleted twice via
                    // cascading delete. This is intentional; cascading delete
                    // implies an ownership relationship, and it doesn't make
                    // sense for an object to have two owners unless explicitly
                    // requested for idempotent deletion.
                    throw new NotFoundException(clazz, id)
                else return
            }

            // Remove the object from cache if cached, otherwise load from ZK.
            val ObjWithVersion(thisObj, thisVersion) =
                objCache.remove(key).getOrElse {
                    try {
                        getWithVersion(clazz, id)
                    } catch {
                        case nfe: NotFoundException if ignoresNeo =>
                            // Ignores deletion on a non-existing object.
                            return
                    }
                }
            objsToDelete(key) = thisVersion

            for (bdg <- allBindings.get(clazz).asScala
                 if bdg.hasBackReference;
                 thatId <- bdg.getFwdReferenceAsList(thisObj).asScala.distinct
                 if !objsToDelete.contains(
                     Key(bdg.getReferencedClass, thatId))) {

                bdg.onDeleteThis match {
                    case DeleteAction.ERROR =>
                        throw new ObjectReferencedException(
                            clazz, id, bdg.getReferencedClass, thatId)
                    case DeleteAction.CLEAR =>
                        clearBackreference(bdg, id, thatId)
                    case DeleteAction.CASCADE =>
                        // Breaks if A has bindings with cascading delete to B
                        // and C, and B has a binding to C with ERROR semantics.
                        // This would be complicated to fix and probably isn't
                        // needed, so I'm leaving it as is.
                        delete(bdg.getReferencedClass, thatId, ignoresNeo)
                }
            }
        }

        def commit() {
            var txn =
                curator.inTransaction().asInstanceOf[CuratorTransactionFinal]

            val (toCreate, toUpdate) =
                objCache.toList.partition(_._2.version == NEW_OBJ_VERSION)

            for ((Key(clazz, id), ObjWithVersion(obj, _)) <- toCreate) {
                val path = getPath(clazz, id)
                txn = txn.create()
                    .forPath(path, serialize(obj.asInstanceOf[Obj])).and()
            }

            for ((Key(clazz, id), ObjWithVersion(obj, ver)) <- toUpdate) {
                val path = getPath(clazz, id)
                txn = txn.setData().withVersion(ver)
                    .forPath(path, serialize(obj.asInstanceOf[Obj])).and()
            }

            for ((Key(clazz, id), ver) <- objsToDelete) {
                val path = getPath(clazz, id)
                txn = txn.delete().withVersion(ver).forPath(path).and()
            }

            try txn.commit() catch {
                case nee: NodeExistsException =>
                    val key = keyOfFailedCreate(nee, toCreate.map(_._1))
                    throw new ObjectExistsException(key.clazz, key.id)
                case rce: ReferenceConflictException => throw rce
                case ex@(_: BadVersionException | _: NoNodeException) =>
                    // NoNodeException is assumed to be due to concurrent delete
                    // operation because we already sucessfully fetched any
                    // objects that are being updated.
                    throw new ConcurrentModificationException(ex)
                case ex: Exception =>
                    throw new InternalObjectMapperException(ex)
            }
        }

        def releaseLock(): Unit = try {
            curator.delete().forPath(lockPath)
        } catch {
            // Not much we can do. Fortunately, it's ephemeral.
            case ex: Exception => log.warn(
                s"Could not delete TransactionManager lock node $lockPath.", ex)
        }

        /**
         * Zookeeper likes to play games and doesn't include the path of the
         * failed create operation in the NodeExistsException. To figure out
         * which create failed, we need to look at the list of results and find
         * out which one has a NODEEXISTS error code. The index of that result
         * in the result list is the index of the failed create operation in the
         * list of submitted create operations.
         *
         * @param nee Exception raised from a ZK transaction that began with one
         *            or more create operations.
         *
         * @param createKeys List of keys corresponding to create operations
         *                   that were submitted, in the order in which the
         *                   create operations were submitted.
         *
         * @return Key of create operation that triggered the exception.
         */
        private def keyOfFailedCreate(nee: NodeExistsException,
                                      createKeys: List[Key[_]]): Key[_] = {
            val i = nee.getResults.asScala.indexWhere { res =>
                val err = res.asInstanceOf[ErrorResult].getErr
                err == KeeperException.Code.NODEEXISTS.intValue
            }
            createKeys(i)
        }
    }

    /**
     * Registers the class for use. This method is not thread-safe, and
     * initializes a variety of structures which could not easily be
     * initialized dynamically in a thread-safe manner.
     *
     * Most operations require prior registration, including declareBinding.
     * Ideally this method should be called at startup for all classes
     * intended to be stored in this instance of ZookeeperObjectManager.
     */
    def registerClass(clazz: Class[_]) {
        assert(!built)
        registerClassInternal(clazz)
    }

    private def registerClassInternal(clazz: Class[_]) {
        val name = clazz.getSimpleName
        simpleNameToClass.get(name) match {
            case Some(_) =>
                throw new IllegalStateException(
                    s"A class with the simple name $name is already " +
                    s"registered. Registering multiple classes with the same " +
                    s"simple name is not supported.")
            case None =>
                simpleNameToClass.put(name, clazz)
        }

        classToIdGetter(clazz) = makeIdGetter(clazz)

        val ensurePath = new EnsurePath(getPath(clazz))
        try ensurePath.ensure(curator.getZookeeperClient) catch {
            case ex: Exception => throw new InternalObjectMapperException(ex)
        }

        // Add the instance map last, since it's used to verify registration
        instanceCaches(clazz) =
            new TrieMap[String, InstanceSubscriptionCache[_]]
    }

    def isRegistered(clazz: Class[_]) = {
        val registered = instanceCaches.contains(clazz)
        if (!registered)
            log.warn(s"Class ${clazz.getSimpleName} is not registered.")
        registered
    }

    def declareBinding(leftClass: Class[_], leftFieldName: String,
                       onDeleteLeft: DeleteAction,
                       rightClass: Class[_], rightFieldName: String,
                       onDeleteRight: DeleteAction): Unit = {
        assert(!built)
        assert(isRegistered(leftClass))
        assert(isRegistered(rightClass))

        val leftIsMessage = classOf[Message].isAssignableFrom(leftClass)
        val rightIsMessage = classOf[Message].isAssignableFrom(rightClass)
        if (leftIsMessage != rightIsMessage) {
            throw new IllegalArgumentException(
                "Cannot bind a protobuf Message class to a POJO class.")
        }

        val bdgs = if (leftIsMessage) {
            ProtoFieldBinding.createBindings(
                leftClass, leftFieldName, onDeleteLeft,
                rightClass, rightFieldName, onDeleteRight)
        } else {
            PojoFieldBinding.createBindings(
                leftClass, leftFieldName, onDeleteLeft,
                rightClass, rightFieldName, onDeleteRight)
        }

        for (entry <- bdgs.entries().asScala) {
            allBindings.put(entry.getKey, entry.getValue)
        }
    }

    /**
     * This method must be called after all calls to registerClass() and
     * declareBinding() have been made, but before any calls to data-related
     * methods such as CRUD operations and subscribe().
     */
    def build() {
        assert(!built)
        initVersionNumber()
        ensureClassNodes(instanceCaches.keySet)
        built = true
    }

    private def getVersionNumberFromZkAndWatch(): Long = {
        val watcher = new Watcher() {
                override def process(event: WatchedEvent) {
                    event.getType match {
                        case NodeDataChanged =>
                            setVersionNumberAndWatch()
                            // TODO GC subscriptions.
                        case _ =>  // Do nothing.
                    }
                }
            }
        JLong.parseLong(new String(
                curator.getData.usingWatcher(watcher).forPath(versionNodePath)))
    }

    private def setVersionNumberAndWatch() {
        version.set(getVersionNumberFromZkAndWatch())
    }

    private def initVersionNumber() {
        val vNodePath = versionNodePath
        try {
            curator.create.forPath(vNodePath, version.toString.getBytes)
            getVersionNumberFromZkAndWatch()
        } catch {
            case _: NodeExistsException =>
                try {
                    setVersionNumberAndWatch()
                } catch {
                    case ex: Exception =>
                        throw new InternalObjectMapperException(
                                "Failure in initializing version number.", ex)
                }
            case ex: Exception =>
                throw new InternalObjectMapperException(
                        "Failure in initializing version number.", ex)
        }
        log.info(s"Initialized the version number to $version.")
    }

    private def updateVersionNumber() {
        try {
            curator.setData.forPath(versionNodePath, version.toString.getBytes)
        } catch {
            case ex: Exception =>
                throw new InternalObjectMapperException(
                        "Failure in updating version number.", ex)
        }
        log.info(s"Updated the version number to $version.")
    }

    def isBuilt = built

    /**
     * Ensures that the class nodes in Zookeeper for each provided class exist,
     * creating them if needed.
     */
    private def ensureClassNodes(classes: Set[Class[_]]) {
        assert(classes.forall(isRegistered))

        // First try a multi-check for all the classes. If they already exist,
        // as they usually will except on the first startup, we can verify this
        // in a single round trip to Zookeeper.
        var txn = curator.inTransaction().asInstanceOf[CuratorTransactionFinal]
        for (clazz <- classes)
            txn = txn.check().forPath(getPath(clazz)).and()
        try {
            txn.commit()
            return
        } catch {
            case ex: Exception =>
                log.info("Could not confirm existence of all class nodes in " +
                         "Zookeeper. Creating missing class node(s).")
        }

        // One or more didn't exist, so we'll have to check them individually.
        for (clazz <- classes) {
            val ensurePath = new EnsurePath(getPath(clazz))
            try ensurePath.ensure(curator.getZookeeperClient) catch {
                case ex: Exception => throw new
                        InternalObjectMapperException(ex)
            }
        }
    }

    /**
     * Persists the specified object to Zookeeper. The object must have a field
     * named "id", and an appropriate unique ID must already be assigned to the
     * object before the call.
     */
    @throws[NotFoundException]
    @throws[ObjectExistsException]
    @throws[ReferenceConflictException]
    override def create(obj: Obj) = multi(List(CreateOp(obj)))

    /**
     * Updates the specified object in Zookeeper.
     */
    @throws[NotFoundException]
    @throws[ReferenceConflictException]
    override def update(obj: Obj) = multi(List(UpdateOp(obj)))

    @throws[NotFoundException]
    @throws[ReferenceConflictException]
    override def update[T <: Obj](obj: T, validator: UpdateValidator[T]) =
        multi(List(UpdateOp(obj, validator)))

    /**
     * Deletes the specified object from Zookeeper.
     */
    @throws[NotFoundException]
    @throws[ObjectReferencedException]
    override def delete(clazz: Class[_], id: ObjId) =
        multi(List(DeleteOp(clazz, id)))

    /**
     * Deletes the specified object from Zookeeper if it exists and ignores if
     * it doesn't.
     */
    @throws[ObjectReferencedException]
    override def deleteIfExists(clazz: Class[_], id: ObjId) =
        multi(List(DeleteOp(clazz, id, ignoreIfNotExists = true)))

    @throws[NotFoundException]
    override def get[T](clazz: Class[T], id: ObjId): Future[T] = {
        assertBuilt()
        assert(isRegistered(clazz))

        instanceCaches(clazz).get(id.toString) match {
            case Some(cache) => cache.asInstanceOf[InstanceSubscriptionCache[T]]
                                     .observable.asFuture
            case None =>
                val p = Promise[T]()
                val cb = new BackgroundCallback {
                    override def processResult(client: CuratorFramework,
                                               e: CuratorEvent): Unit = {
                        if (e.getStat == null) {
                            p.failure(new NotFoundException(clazz, id))
                        } else {
                            try {
                                p.success(deserialize(e.getData, clazz))
                            } catch {
                                case t: Throwable => p.failure(t)
                            }
                        }
                    }
                }
                curator.getData
                       .inBackground(cb)
                       .forPath(getPath(clazz, id))
                p.future
        }
    }

    override def getAll[T](clazz: Class[T], ids: Seq[_ <: ObjId])
    : Seq[Future[T]] = {
        assertBuilt()
        assert(isRegistered(clazz))
        ids.map { id => get(clazz, id) }
    }

    /**
     * Gets all instances of the specified class from Zookeeper.
     */
    override def getAll[T](clazz: Class[T]): Future[Seq[Future[T]]] = {
        assertBuilt()
        assert(isRegistered(clazz))

        val p = Promise[Seq[Future[T]]]()
        val cb = new BackgroundCallback {
            override def processResult(client: CuratorFramework,
                                       evt: CuratorEvent): Unit = {
                assert(CuratorEventType.CHILDREN == evt.getType)
                p.success(getAll(clazz, evt.getChildren.asScala))
            }
        }

        try {
            curator.getChildren.inBackground(cb).forPath(getPath(clazz))
        } catch {
            case ex: Exception =>
                // Should have created this during class registration.
                throw new InternalObjectMapperException(
                    s"Node ${getPath(clazz)} does not exist in Zookeeper.", ex)
        }
        p.future
    }

    /**
     * Returns true if the specified object exists in Zookeeper.
     */
    override def exists(clazz: Class[_], id: ObjId): Future[Boolean] = {
        assertBuilt()
        assert(isRegistered(clazz))
        val p = Promise[Boolean]()
        val cb = new BackgroundCallback {
            override def processResult(client: CuratorFramework,
                                       evt: CuratorEvent): Unit = {
                assert(CuratorEventType.EXISTS == evt.getType)
                p.success(evt.getStat != null)
            }
        }
        try {
            curator.checkExists().inBackground(cb).forPath(getPath(clazz, id))
        } catch {
            case ex: Exception => throw new InternalObjectMapperException(ex)
        }
        p.future
    }

    /**
     * Executes multiple create, update, and/or delete operations atomically.
     */
    @throws[NotFoundException]
    @throws[ObjectExistsException]
    @throws[ObjectReferencedException]
    @throws[ReferenceConflictException]
    override def multi(ops: Seq[PersistenceOp]): Unit = {
        assertBuilt()
        if (ops.isEmpty) return

        val manager = new TransactionManager(version.longValue())
        ops.foreach {
            case CreateOp(obj) => manager.create(obj)
            case UpdateOp(obj, validator) => manager.update(obj, validator)
            case DeleteOp(clazz, id, ignores) =>
                manager.delete(clazz, id, ignores)
        }

        try manager.commit() finally { manager.releaseLock() }
    }

    /**
     * Executes multiple create, update, and/or delete operations atomically.
     */
    @throws[NotFoundException]
    @throws[ObjectExistsException]
    @throws[ObjectReferencedException]
    @throws[ReferenceConflictException]
    override def multi(ops: JList[PersistenceOp]): Unit = multi(ops.asScala)

    /**
     * Flushes all the data in the storage by bumping the data set path version.
     *
     * TODO: move this operation out, this is a C3PO op, not a ZOOM op.
     */
    @throws[StorageException]
    override def flush(): Unit = {
        version.incrementAndGet()
        updateVersionNumber()
        try {
            simpleNameToClass.clear()
            // TODO: Need to close all class subscriptions.
            classCaches.clear()
            for (c <- classToIdGetter.keySet) registerClassInternal(c)
        } catch {
            case th: Throwable =>
                throw new StorageException("Failure in flushing Storage.", th)
        }
        log.info(s"Flushed the Storage, bumping the version to $version.")
    }

    private[storage] def getPath(clazz: Class[_], version: Long) =
        basePath(version) + "/" + clazz.getSimpleName

    private[storage] def getPath(clazz: Class[_]): String =
        getPath(clazz, this.version.longValue)

    private[storage] def getPath(
            clazz: Class[_], id: ObjId, version: Long = this.version.longValue)
    : String = {
        getPath(clazz, version) + "/" + getIdString(clazz, id)
    }

    /**
     * @return The number of subscriptions to the given class and id. If the
     *         corresponding entry does not exist, None is returned.
     */
    @VisibleForTesting
    protected[storage] def subscriptionCount[T](clazz: Class[T], id: ObjId)
    : Option[Int] = {
        instanceCaches(clazz).get(id.toString).map(_.subscriptionCount)
    }

    override def observable[T](clazz: Class[T], id: ObjId): Observable[T] = {
        assertBuilt()
        assert(isRegistered(clazz))

        instanceCaches(clazz).getOrElse(id.toString, {
            val onLastUnsubscribe = (c: InstanceSubscriptionCache[_]) => {
                instanceCaches.get(clazz).foreach( _ remove id.toString )
            }
            val ic = new InstanceSubscriptionCache(clazz, getPath(clazz, id),
                                                   id.toString, curator,
                                                   onLastUnsubscribe)
            instanceCaches(clazz).putIfAbsent(id.toString, ic) getOrElse {
                async { ic.connect() }
                ic
            }
        }).observable.asInstanceOf[Observable[T]]
    }

    /**
     * Refer to the interface documentation for functionality.
     *
     * This implementation involves a BLOCKING call when the observable is first
     * created, as we initialize the the connection to ZK.
     */
    override def observable[T](clazz: Class[T]): Observable[Observable[T]] = {
        assertBuilt()
        assert(isRegistered(clazz))

        classCaches.getOrElse(clazz, {
            val onLastUnsubscribe: ClassSubscriptionCache[_] => Unit = c => {
                classCaches.remove(clazz)
            }
            val cc = new ClassSubscriptionCache(clazz, getPath(clazz), curator,
                                                onLastUnsubscribe)
            classCaches.putIfAbsent(clazz, cc).getOrElse(cc)
        }).asInstanceOf[ClassSubscriptionCache[T]].observable
    }

    /**
     * @return The number of subscriptions to the given class. If the
     *         corresponding entry does not exist, None is returned.
     */
    @VisibleForTesting
    protected[storage] def subscriptionCount[T](clazz: Class[T]): Option[Int] = {
        classCaches.get(clazz).map(_.subscriptionCount)
    }

    private def assertBuilt() {
        if (!built) throw new ServiceUnavailableException(
            "Data operation received before call to build().")
    }

    private def getObjectId(obj: Obj) = classToIdGetter(obj.getClass).idOf(obj)
}

object ZookeeperObjectMapper {
    private val VERSION_NODE = "dataset_version"
    private val INITIAL_ZOOM_DATA_SET_VERSION = 1

    private[storage] trait IdGetter {
        def idOf(obj: Obj): ObjId
    }

    private[storage] class MessageIdGetter(clazz: Class[_]) extends IdGetter {
        val idFieldDesc =
            ProtoFieldBinding.getMessageField(clazz, FieldBinding.ID_FIELD)

        def idOf(obj: Obj) = obj.asInstanceOf[Message].getField(idFieldDesc)
    }

    private[storage] class PojoIdGetter(clazz: Class[_]) extends IdGetter {
        val idField = clazz.getDeclaredField(FieldBinding.ID_FIELD)
        idField.setAccessible(true)

        def idOf(obj: Obj) = idField.get(obj)
    }

    private case class Key[T](clazz: Class[T], id: ObjId)
    private case class ObjWithVersion[T](obj: T, version: Int)

    protected val log = LoggerFactory.getLogger(ZookeeperObjectMapper.getClass)

    private val jsonFactory = new JsonFactory(new ObjectMapper())

    private[storage] def makeIdGetter(clazz: Class[_]): IdGetter = {
        try {
            if (classOf[Message].isAssignableFrom(clazz)) {
                new MessageIdGetter(clazz)
            } else {
                new PojoIdGetter(clazz)
            }
        } catch {
            case ex: Exception =>
                throw new IllegalArgumentException(
                    s"Class $clazz does not have a field named 'id', or the " +
                    "field could not be made accessible.", ex)
        }
    }

    private[storage] def getIdString(clazz: Class[_], id: ObjId): String = {
        if (classOf[Message].isAssignableFrom(clazz)) {
            ProtoFieldBinding.getIdString(id)
        } else {
            id.toString
        }
    }

    private[storage] def serialize(obj: Obj): Array[Byte] ={
        obj match {
            case msg: Message => serializeMessage(msg)
            case pojo => serializePojo(pojo)
        }
    }

    private def serializeMessage(msg: Message) = msg.toString.getBytes

    private def serializePojo(obj: Obj): Array[Byte] = {
        val writer = new StringWriter()
        try {
            val generator = jsonFactory.createJsonGenerator(writer)
            generator.writeObject(obj)
            generator.close()
        } catch {
            case ex: Exception =>
                throw new InternalObjectMapperException(
                    "Could not serialize " + obj, ex)
        }

        writer.toString.trim.getBytes
    }

    private[storage] def deserialize[T](data: Array[Byte],
                                        clazz: Class[T]): T = {
        try {
            if (classOf[Message].isAssignableFrom(clazz)) {
                deserializeMessage(data, clazz)
            } else {
                deserializePojo(data, clazz)
            }
        } catch {
            case ex: Exception =>
                throw new InternalObjectMapperException(
                    "Could not parse data from Zookeeper: " + new String(data),
                    ex)
        }
    }

    private def deserializeMessage[T](data: Array[Byte], clazz: Class[T]): T = {
        val builderObj = clazz.getMethod("newBuilder").invoke(null)
        val builder = builderObj.asInstanceOf[Message.Builder]
        TextFormat.merge(new String(data), builder)
        builder.build().asInstanceOf[T]
    }

    private def deserializePojo[T](json: Array[Byte], clazz: Class[T]): T = {
        val parser = jsonFactory.createJsonParser(json)
        val t = parser.readValueAs(clazz)
        parser.close()
        t
    }
}
