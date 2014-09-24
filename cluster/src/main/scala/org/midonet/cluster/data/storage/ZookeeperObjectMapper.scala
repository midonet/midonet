/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.storage

import java.io.StringWriter
import java.util.{ConcurrentModificationException, List => JList}

import com.google.common.collect.ArrayListMultimap
import com.google.protobuf.{Message, TextFormat}
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.api.transaction.CuratorTransactionFinal
import org.apache.curator.utils.EnsurePath
import org.apache.zookeeper.KeeperException.{BadVersionException, NoNodeException, NodeExistsException}
import org.apache.zookeeper.OpResult.ErrorResult
import org.apache.zookeeper.data.Stat
import org.apache.zookeeper.{CreateMode, KeeperException}
import org.codehaus.jackson.JsonFactory
import org.codehaus.jackson.map.ObjectMapper
import org.midonet.cluster.data.storage.FieldBinding.DeleteAction
import org.slf4j.LoggerFactory
import rx.{Observable, Observer, Subscription}

import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.collection.mutable.ListBuffer

/**
 * Object mapper that uses Zookeeper as a data store. Maintains referential
 * integrity through the use of field bindings, which must be declared
 * prior to any CRUD operations through the use of declareBinding().
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
 * Note that the Midonet API does not actually allow a Bridge's portIds
 * to be set directly. However, this restriction is not enforced by the
 * ObjectMapper.
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
 */
class ZookeeperObjectMapper(private val basePath: String,
                            private val curator: CuratorFramework)
                            extends StorageService {
    import org.midonet.cluster.data.storage.ZookeeperObjectMapper._

    private val locksPath = basePath + "/zoomlocks/lock"

    private val allBindings = ArrayListMultimap.create[Class[_], FieldBinding]()

    private val classToIdGetter =
        new mutable.HashMap[Class[_], IdGetter]()
    private val simpleNameToClass =
        new mutable.HashMap[String, Class[_]]()

    private val instanceCaches = new mutable.HashMap[
        Class[_], TrieMap[ObjId, InstanceSubscriptionCache[_]]]
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
    private class TransactionManager {
        private final val NEW_OBJ_VERSION = -1
        private val objCache = new mutable.HashMap[Key[_], ObjWithVersion[_]]()
        private val objsToDelete = new mutable.HashMap[Key[_], Int]()

        // Create an ephemeral node so that we can get Zookeeper's current
        // ZXID. This will allow us to determine if any of the nodes we read
        // have been modified since the TransactionManager was created, allowing
        // us to ensure a consistent read across multiple nodes.
        private val (lockPath: String, zxid: Long) = try {
            val path = curator.create().creatingParentsIfNeeded()
                .withMode(CreateMode.EPHEMERAL_SEQUENTIAL).forPath(locksPath)
            val stat = new Stat()
            curator.getData.storingStatIn(stat).forPath(path)
            (path, stat.getCzxid)
        } catch {
            case ex: Exception => throw new InternalObjectMapperException(
                "Could not acquire current zxid.", ex)
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
                updateCache(bdg.getReferencedClass, thatId, updatedThatObj)
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
                updateCache(bdg.getReferencedClass, thatId, updatedThatObj)
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

        def update(newThisObj: Obj): Unit = {
            val thisClass = newThisObj.getClass
            assert(isRegistered(thisClass))

            val thisId = getObjectId(newThisObj)
            val oldThisObj = cachedGet(thisClass, thisId).getOrElse(
                throw new NotFoundException(thisClass, thisId))

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

        def delete(clazz: Class[_], id: ObjId) {
            assert(isRegistered(clazz))
            val key = Key(clazz, id)
            if (objsToDelete.contains(key)) {
                // The primary purpose of this is to throw up a red flag when
                // the caller explicitly tries to delete the same object twice
                // in a single multi() call, but this will throw an exception if
                // an object is deleted twice via cascading delete. This is
                // intentional; cascading delete implies an ownership
                // relationship, and I don't think it makes sense for an object
                // to have two owners. If you have a legitimate use case, let me
                // know and I'll figure out a way to support it.
                throw new NotFoundException(clazz, id)
            }

            // Remove the object from cache if cached, otherwise load from ZK.
            val ObjWithVersion(thisObj, thisVersion) =
                objCache.remove(key).getOrElse(getWithVersion(clazz, id))
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
                        delete(bdg.getReferencedClass, thatId)
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
                txn = txn.create().forPath(path, serialize(obj)).and()
            }

            for ((Key(clazz, id), ObjWithVersion(obj, ver)) <- toUpdate) {
                val path = getPath(clazz, id)
                txn = txn.setData().withVersion(ver)
                    .forPath(path, serialize(obj)).and()
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

        // Add the instance cache map last, since we use this to verify
        // registration.
        instanceCaches(clazz) = new TrieMap[ObjId, InstanceSubscriptionCache[_]]
    }

    def isRegistered(clazz: Class[_]) = instanceCaches.contains(clazz)

    def declareBinding(leftClass: Class[_], leftFieldName: String,
                       onDeleteLeft: DeleteAction,
                       rightClass: Class[_], rightFieldName: String,
                       onDeleteRight: DeleteAction): Unit = {
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
     * For testing. There should be no need to do this in production.
     */
    private[storage] def clearBindings() = allBindings.clear()

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

    /**
     * Deletes the specified object from Zookeeper.
     */
    @throws[NotFoundException]
    @throws[ObjectReferencedException]
    override def delete(clazz: Class[_], id: ObjId) =
        multi(List(DeleteOp(clazz, id)))

    /**
     * Gets the specified instance of the specified class from Zookeeper.
     */
    @throws[NotFoundException]
    override def get[T](clazz: Class[T], id: ObjId): T = {
        assert(isRegistered(clazz))
        val data = try curator.getData.forPath(getPath(clazz, id)) catch {
            case nne: NoNodeException => throw new NotFoundException(clazz, id)
            case ex: Exception => throw new InternalObjectMapperException(ex)
        }

        deserialize(data, clazz)
    }

    /**
     * Gets all instances of the specified class from Zookeeper.
     */
    override def getAll[T](clazz: Class[T]): JList[T] = {
        assert(isRegistered(clazz))
        val ids = try curator.getChildren.forPath(getPath(clazz)) catch {
            case ex: Exception =>
                // Should have created this during class registration.
                throw new InternalObjectMapperException(
                    s"Node ${getPath(clazz)} does not exist in Zookeeper.", ex)
        }

        val ts = ListBuffer[T]()
        ids.asScala.foreach { id =>
            try ts += get(clazz, id) catch {
                case nne: NotFoundException =>
                    // Must have been deleted since fetching IDs. Ignore.
                case ex: Exception =>
                    throw new InternalObjectMapperException(ex)
            }
        }
        ts.toList.asJava
    }

    /**
     * Returns true if the specified object exists in Zookeeper.
     */
    override def exists(clazz: Class[_], id: ObjId): Boolean = {
        try {
            curator.checkExists().forPath(getPath(clazz, id)) != null
        } catch {
            case ex: Exception => throw new InternalObjectMapperException(ex)
        }
    }

    /**
     * Executes multiple create, update, and/or delete operations atomically.
     */
    @throws[NotFoundException]
    @throws[ObjectExistsException]
    @throws[ObjectReferencedException]
    @throws[ReferenceConflictException]
    override def multi(ops: Seq[PersistenceOp]): Unit = {
        if (ops.isEmpty) return

        val manager = new TransactionManager
        ops.foreach {
            case CreateOp(obj) => manager.create(obj)
            case UpdateOp(obj) => manager.update(obj)
            case DeleteOp(clazz, id) => manager.delete(clazz, id)
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

    private[storage] def getPath(clazz: Class[_]) =
        basePath + "/" + clazz.getSimpleName

    private[storage] def getPath(clazz: Class[_], id: ObjId): String = {
        val idString = if (classOf[Message].isAssignableFrom(clazz)) {
            ProtoFieldBinding.getIdString(id)
        } else {
            id.toString
        }

        getPath(clazz) + "/" + idString
    }

    private def makeIdGetter(clazz: Class[_]): IdGetter = {
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

    /**
     * Subscribe to the specified object. Upon subscription at time t0,
     * obs.onNext() will receive the object's state at time t0, and future
     * updates at tn > t0 will trigger additional calls to onNext(). If an
     * object is updated in Zookeeper multiple times in quick succession, some
     * updates may not trigger a call to onNext(), but each call to onNext()
     * will provide the most up-to-date data available.
     *
     * obs.onCompleted() will be called when the object is deleted, and
     * obs.onError() will be invoked with a NotFoundException if the object
     * does not exist.
     */
    override def subscribe[T](clazz: Class[T],
                              id: ObjId,
                              obs: Observer[_ >: T]): Subscription = {
        assert(isRegistered(clazz))
        val cache = instanceCaches(clazz).getOrElse(id, {
            val path = getPath(clazz, id)
            val nw = new InstanceSubscriptionCache(clazz, path, curator)
            instanceCaches(clazz).putIfAbsent(id, nw) match {
                case Some(cur) => nw.close(); cur
                case None => nw
            }
        }).asInstanceOf[InstanceSubscriptionCache[T]]
        cache.subscribe(obs)
    }

    /**
     * Subscribes to the specified class. Upon subscription at time t0,
     * obs.onNext() will receive an Observable[T] for each object of class
     * T existing at time t0, and future updates at tn > t0 will each trigger
     * a call to onNext() with an Observable[T] for a new object.
     *
     * Neither obs.onCompleted() nor obs.onError() will be invoked under normal
     * circumstances.
     *
     * The subscribe() method of each of these Observables has the same behavior
     * as ZookeeperObjectMapper.subscribe(Class[T], ObjId).
     */
    override def subscribeAll[T](
            clazz: Class[T],
            obs: Observer[_ >: Observable[T]]): Subscription = {
        assert(isRegistered(clazz))
        val cache = classCaches.getOrElse(clazz, {
            val nw = new ClassSubscriptionCache(clazz, getPath(clazz), curator)
            classCaches.putIfAbsent(clazz, nw) match {
                case Some(cur) => nw.close(); cur
                case None => nw
            }
        }).asInstanceOf[ClassSubscriptionCache[T]]
        cache.subscribe(obs)
    }

    private trait IdGetter {
        def idOf(obj: Obj): ObjId
    }

    private class MessageIdGetter(clazz: Class[_]) extends IdGetter {
        val idFieldDesc =
            ProtoFieldBinding.getMessageField(clazz, FieldBinding.ID_FIELD)

        def idOf(obj: Obj) = obj.asInstanceOf[Message].getField(idFieldDesc)
    }

    private class PojoIdGetter(clazz: Class[_]) extends IdGetter {
        val idField = clazz.getDeclaredField(FieldBinding.ID_FIELD)
        idField.setAccessible(true)

        def idOf(obj: Obj) = idField.get(obj)
    }

    private def getObjectId(obj: Obj) = classToIdGetter(obj.getClass).idOf(obj)
}

object ZookeeperObjectMapper {
    private case class Key[T](clazz: Class[T], id: ObjId)
    private case class ObjWithVersion[T](obj: T, version: Int)

    protected val log = LoggerFactory.getLogger(ZookeeperObjectMapper.getClass)

    private val jsonFactory = new JsonFactory(new ObjectMapper())

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
