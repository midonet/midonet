/*
 * Copyright 2015 Midokura SARL
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
import scala.collection.JavaConversions._
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}

import com.google.common.annotations.VisibleForTesting
import com.google.common.collect.ArrayListMultimap
import com.google.protobuf.{Message, TextFormat}

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.api.transaction.CuratorTransactionFinal
import org.apache.curator.framework.api.{BackgroundCallback, CuratorEvent, CuratorEventType}
import org.apache.curator.utils.EnsurePath
import org.apache.zookeeper.KeeperException._
import org.apache.zookeeper.OpResult.ErrorResult
import org.apache.zookeeper.Watcher.Event.EventType.NodeDataChanged
import org.apache.zookeeper.data.Stat
import org.apache.zookeeper.{CreateMode, KeeperException, WatchedEvent, Watcher}
import org.codehaus.jackson.JsonFactory
import org.codehaus.jackson.map.ObjectMapper
import org.slf4j.LoggerFactory

import rx.Observable

import org.midonet.cluster.data.storage.FieldBinding.DeleteAction
import org.midonet.cluster.data.storage.OwnershipType.OwnershipType
import org.midonet.cluster.data.storage.TransactionManager._
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
    private val curator: CuratorFramework) extends StorageWithOwnership {

    import org.midonet.cluster.data.storage.ZookeeperObjectMapper._
    @volatile private var built = false

    /* Monotonically increasing version number for the data set path under
     * which all the Storage contents are stored. When we "flush" the storage,
     * ZOOM actually just bumps the version number by 1, keeps the old data, and
     * starts persisting data in under the new version path.
     */
    private val version = new AtomicLong(INITIAL_ZOOM_DATA_SET_VERSION)
    private def basePath(version: Long = this.version.longValue) =
        s"$basePathPrefix/$version"
    private def versionNodePath = s"$basePathPrefix/$VERSION_NODE"

    private def locksPath(version: Long) = basePath(version) + "/zoomlocks/lock"

    private val allBindings = ArrayListMultimap.create[Class[_], FieldBinding]()

    private val executor = Executors.newCachedThreadPool()
    private implicit val executionContext =
        ExecutionContext.fromExecutorService(executor)

    private val classInfo =
        new mutable.HashMap[Class[_], ClassInfo]()
    private val simpleNameToClass =
        new mutable.HashMap[String, Class[_]]()

    private val instanceCaches = new mutable.HashMap[
        Class[_], TrieMap[String, InstanceSubscriptionCache[_]]]
    private var classCaches = new TrieMap[Class[_], ClassSubscriptionCache[_]]
    private val ownerCaches = new mutable.HashMap[
        Class[_], TrieMap[String, DirectorySubscriptionCache]]

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
    private class ZoomTransactionManager(val version: Long)
            extends TransactionManager(classInfo.toMap, allBindings) {

        import ZookeeperObjectMapper._

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

        private def getOwnerPath(clazz: Class[_], id: ObjId, owner: ObjId) = {
            ZookeeperObjectMapper.this.getOwnerPath(clazz, id, owner, version)
        }

        override def isRegistered(clazz: Class[_]): Boolean = {
            ZookeeperObjectMapper.this.isRegistered(clazz)
        }

        override def getSnapshot(clazz: Class[_], id: ObjId): ObjSnapshot = {
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

            val children = if (stat.getNumChildren > 0) try {
                curator.getChildren.storingStatIn(stat).forPath(path)
                    .asScala.toSet
            } catch {
                case nne: NoNodeException =>
                    throw new NotFoundException(clazz, id)
                case ex: Exception =>
                    throw new InternalObjectMapperException(ex)
            } else Set.empty[String]

            if (stat.getMzxid > zxid) {
                throw new ConcurrentModificationException(
                    s"${clazz.getSimpleName} with ID $id was modified " +
                    "during the transaction.")
            }

            ObjSnapshot(deserialize(data, clazz).asInstanceOf[Obj],
                        stat.getVersion, children)
        }

        override def commit(): Unit = {
            val ops = flattenOps
            var txn =
                curator.inTransaction().asInstanceOf[CuratorTransactionFinal]

            for ((Key(clazz, id), txOp) <- ops) txn = {
                txOp match {
                    case TxCreate(obj, _) =>
                        txn.create()
                            .forPath(getPath(clazz, id), serialize(obj)).and()
                    case TxUpdate(obj, ver, ownerOps) =>
                        txn.setData().withVersion(ver)
                            .forPath(getPath(clazz, id), serialize(obj)).and()
                    case TxDelete(ver, ownerOps) =>
                        txn.delete().withVersion(ver)
                            .forPath(getPath(clazz, id)).and()
                    case TxCreateOwner(owner) =>
                        txn.create().withMode(CreateMode.EPHEMERAL)
                            .forPath(getOwnerPath(clazz, id, owner)).and()
                    case TxDeleteOwner(owner) =>
                        txn.delete()
                            .forPath(getOwnerPath(clazz, id, owner)).and()
                }
            }

            try txn.commit() catch {
                case nee: NodeExistsException =>
                    rethrowException(ops, nee)
                case nee: NotEmptyException =>
                    val op = opForException(ops, nee)
                    throw new OwnershipConflictException(
                        op._1.clazz.toString, op._1.id.toString)
                case rce: ReferenceConflictException => throw rce
                case ex@(_: BadVersionException | _: NoNodeException) =>
                    // NoNodeException is assumed to be due to concurrent delete
                    // operation because we already successfully fetched any
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
         * Returns the operation of this transaction that generated the given
         * exception.
         */
        private def opForException(ops: Seq[(Key, TxOp)], e: KeeperException)
        : (Key, TxOp) = {
            ops(e.getResults.asScala.indexWhere { case res: ErrorResult =>
                res.getErr == e.code.intValue })
        }

        /**
         * Converts and rethrows an exception for transaction operation that
         * generated the given [[NodeExistsException]], which can be either a
         * [[ObjectExistsException]] if the original exception was thrown
         * when creating an object, or a [[OwnershipConflictException]], if
         * the original exception was thrown when creating an object owner.
         */
        @throws[ObjectExistsException]
        @throws[OwnershipConflictException]
        private def rethrowException(ops: Seq[(Key, TxOp)],
                                     e: NodeExistsException): Unit = {
            opForException(ops, e) match {
                case (key: Key, oop: TxOwnerOp) =>
                    throw new OwnershipConflictException(
                        key.clazz.getSimpleName, key.id.toString,
                        Set.empty[String], oop.owner)
                case (key: Key, _) =>
                    throw new ObjectExistsException(key.clazz, key.id)
                case _ => throw new InternalObjectMapperException(e)
            }
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
    override def registerClass(clazz: Class[_]): Unit = {
        assert(!built)
        registerClassInternal(clazz, OwnershipType.Shared)
    }

    /**
     * Registers the state for use.
     */
    override def registerClass(clazz: Class[_],
                               ownershipType: OwnershipType): Unit = {
        assert(!built)
        registerClassInternal(clazz, ownershipType)
    }

    private def registerClassInternal(clazz: Class[_],
                                      ownershipType: OwnershipType): Unit = {
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

        classInfo(clazz) = makeInfo(clazz, ownershipType)

        val ensurePath = new EnsurePath(getPath(clazz))
        try ensurePath.ensure(curator.getZookeeperClient) catch {
            case ex: Exception => throw new InternalObjectMapperException(ex)
        }

        // Add the instance map last, since it's used to verify registration
        instanceCaches(clazz) =
            new TrieMap[String, InstanceSubscriptionCache[_]]
        ownerCaches(clazz) = new TrieMap[String, DirectorySubscriptionCache]
    }

    override def isRegistered(clazz: Class[_]) = {
        val registered = instanceCaches.contains(clazz)
        if (!registered)
            log.warn(s"Class ${clazz.getSimpleName} is not registered.")
        registered
    }

    override def declareBinding(leftClass: Class[_], leftFieldName: String,
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
        ensureClassNodes(instanceCaches.keySet.toSet)
        built = true
    }

    private def getVersionNumberFromZkAndWatch: Long = {
        val watcher = new Watcher() {
                override def process(event: WatchedEvent) {
                    event.getType match {
                        case NodeDataChanged =>
                            setVersionNumberAndWatch()
                        case _ =>  // Do nothing.
                    }
                }
            }
        JLong.parseLong(new String(
                curator.getData.usingWatcher(watcher).forPath(versionNodePath)))
    }

    private def setVersionNumberAndWatch() {
        version.set(getVersionNumberFromZkAndWatch)
        cleanUpAndReInitSubscriptionCaches()
    }

    private def initVersionNumber() {
        val vNodePath = versionNodePath
        try {
            curator.create.forPath(vNodePath, version.toString.getBytes)
            getVersionNumberFromZkAndWatch
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
            curator.setData().forPath(versionNodePath, version.toString.getBytes)
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
    override def getAll[T](clazz: Class[T]): Future[Seq[T]] = {
        assertBuilt()
        assert(isRegistered(clazz))

        val all = Promise[Seq[T]]
        val cb = new BackgroundCallback {
            override def processResult(client: CuratorFramework,
                                       evt: CuratorEvent): Unit = {
                assert(CuratorEventType.CHILDREN == evt.getType)
                Future.sequence(getAll(clazz, evt.getChildren)).onComplete {
                    case Success(l) => all trySuccess l
                    case Failure(t) => all tryFailure t
                }
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
        all.future
    }

    @throws[NotFoundException]
    override def getOwners(clazz: Class[_], id: ObjId): Future[Set[String]] = {
        assertBuilt()
        assert(isRegistered(clazz))

        ownerCaches(clazz).get(id.toString) match {
            case Some(cache) => cache.observable.asFuture
            case None =>
                val p = Promise[Set[String]]()
                val cb = new BackgroundCallback {
                    override def processResult(client: CuratorFramework,
                                               event: CuratorEvent): Unit = {
                        if (event.getResultCode == Code.OK.intValue) {
                            p.success(event.getChildren.asScala.toSet)
                        } else {
                            p.failure(new NotFoundException(clazz, id))
                        }
                    }
                }
                curator.getChildren.inBackground(cb).forPath(getPath(clazz, id))
                p.future
        }
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

        val manager = new ZoomTransactionManager(version.longValue())
        ops.foreach {
            case CreateOp(obj) => manager.create(obj)
            case CreateWithOwnerOp(obj, owner) => manager.create(obj, owner)
            case UpdateOp(obj, validator) => manager.update(obj, validator)
            case UpdateWithOwnerOp(obj, owner, validator) =>
                manager.update(obj, owner, validator)
            case UpdateOwnerOp(clazz, id, owner, throwIfExists) =>
                manager.updateOwner(clazz, id, owner, throwIfExists)
            case DeleteOp(clazz, id, ignoresNeo) =>
                manager.delete(clazz, id, ignoresNeo, None)
            case DeleteWithOwnerOp(clazz, id, owner) =>
                manager.delete(clazz, id, false, Some(owner))
            case DeleteOwnerOp(clazz, id, owner) =>
                manager.deleteOwner(clazz, id, owner)
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
            cleanUpAndReInitSubscriptionCaches()
        } catch {
            case th: Throwable =>
                throw new StorageException("Failure in flushing Storage.", th)
        }
        log.info(s"Flushed the Storage, bumping the version to $version.")
    }

    private def cleanUpAndReInitSubscriptionCaches() {
        simpleNameToClass.clear()

        val oldClassCaches = classCaches
        classCaches = new TrieMap[Class[_], ClassSubscriptionCache[_]]
        oldClassCaches.values.foreach(_.close())

        // Instance/ownerCaches are re-initialized by registerClassInternal.
        val oldOwnerCacheValues = ownerCaches.values.toSet
        ownerCaches.clear()
        oldOwnerCacheValues.foreach( _.values.foreach { _.close() })

        val oldInstanceCacheValues = instanceCaches.values.toSet
        for (info <- classInfo.values)
            registerClassInternal(info.clazz, info.ownershipType)
        oldInstanceCacheValues.foreach(_.values.foreach(_.close()))
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

    private[storage] def getOwnerPath(clazz: Class[_], id: ObjId, owner: ObjId,
                                      version: Long = this.version.longValue) =
        getPath(clazz, id, version) + "/" + owner.toString

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

    override def ownersObservable(clazz: Class[_], id: ObjId)
    : Observable[Set[String]] = {
        assertBuilt()
        assert(isRegistered(clazz))

        ownerCaches(clazz).getOrElse(id.toString, {
            val onLastUnsubscribe: DirectorySubscriptionCache => Unit = c => {
                ownerCaches.get(clazz).foreach( _ remove id.toString )
            }
            val oc = new DirectorySubscriptionCache(getPath(clazz, id), curator,
                                                    onLastUnsubscribe)
            ownerCaches(clazz).putIfAbsent(id.toString, oc).getOrElse(oc)
        }).observable
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

}

object ZookeeperObjectMapper {
    private val VERSION_NODE = "dataset_version"
    private val INITIAL_ZOOM_DATA_SET_VERSION = 1

    private[storage] final class MessageClassInfo(clazz: Class[_],
                                                  ownershipType: OwnershipType)
        extends ClassInfo(clazz, ownershipType) {

        val idFieldDesc =
            ProtoFieldBinding.getMessageField(clazz, FieldBinding.ID_FIELD)

        def idOf(obj: Obj) = obj.asInstanceOf[Message].getField(idFieldDesc)
    }

    private [storage] final class JavaClassInfo(clazz: Class[_],
                                                ownershipType: OwnershipType)
        extends ClassInfo(clazz, ownershipType) {

        val idField = clazz.getDeclaredField(FieldBinding.ID_FIELD)

        idField.setAccessible(true)

        def idOf(obj: Obj) = idField.get(obj)
    }

    private final class OwnerMapMethods(owners: Map[String, Int]) {
        def containsIfOwner(owner: Option[String], default: Boolean): Boolean = {
            owner match {
                case Some(o) => owners.contains(o)
                case None => default
            }
        }
    }

    protected val log = LoggerFactory.getLogger(ZookeeperObjectMapper.getClass)

    private val jsonFactory = new JsonFactory(new ObjectMapper())

    private[storage] def makeInfo(clazz: Class[_],
                                  ownershipType: OwnershipType): ClassInfo = {
        try {
            if (classOf[Message].isAssignableFrom(clazz)) {
                new MessageClassInfo(clazz, ownershipType)
            } else {
                new JavaClassInfo(clazz, ownershipType)
            }
        } catch {
            case ex: Exception =>
                throw new IllegalArgumentException(
                    s"Class $clazz does not have a field named 'id', or the " +
                    "field could not be made accessible.", ex)
        }
    }

    private[storage] def serialize(obj: Obj): Array[Byte] = {
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
