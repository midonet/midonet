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
import java.util.ConcurrentModificationException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}

import com.google.common.annotations.VisibleForTesting
import com.google.protobuf.{Message, TextFormat}

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.api.transaction.CuratorTransactionFinal
import org.apache.curator.framework.api.{BackgroundCallback, CuratorEvent, CuratorEventType}
import org.apache.curator.framework.recipes.cache.ChildData
import org.apache.curator.utils.ZKPaths
import org.apache.zookeeper.KeeperException._
import org.apache.zookeeper.OpResult.ErrorResult
import org.apache.zookeeper.Watcher.Event.EventType.NodeDataChanged
import org.apache.zookeeper._
import org.apache.zookeeper.data.Stat
import org.codehaus.jackson.JsonFactory
import org.codehaus.jackson.map.ObjectMapper
import org.slf4j.LoggerFactory

import rx.functions.Func1
import rx.{Notification, Observable}

import org.midonet.cluster.data.storage.CuratorUtil.asObservable
import org.midonet.cluster.data.storage.TransactionManager._
import org.midonet.cluster.data.{Obj, ObjId}
import org.midonet.cluster.util.{NodeObservable, NodeObservableClosedException}
import org.midonet.util.functors.makeFunc1
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
class ZookeeperObjectMapper(protected override val rootPath: String,
                            protected override val curator: CuratorFramework)
    extends ZookeeperObjectState with Storage {

    import ZookeeperObjectMapper._

    /* Monotonically increasing version number for the data set path under
     * which all the Storage contents are stored. When we "flush" the storage,
     * ZOOM actually just bumps the version number by 1, keeps the old data, and
     * starts persisting data in under the new version path.
     */
    protected override val version = new AtomicLong(InitialZoomDataSetVersion)

    private val versionNodePath = s"$rootPath/$VersionNode"

    private def basePath(version: Long) = s"$rootPath/$version"

    private def locksPath(version: Long) = s"$rootPath/$version/zoomlocks/lock"

    private val executor = Executors.newSingleThreadExecutor()
    private implicit val executionContext =
        ExecutionContext.fromExecutorService(executor)

    private val simpleNameToClass = new mutable.HashMap[String, Class[_]]()

    private val objectObservables = new TrieMap[Key, Observable[_]]
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
    private class ZoomTransactionManager(val version: Long)
            extends TransactionManager(classInfo.toMap, allBindings)
            with StateTransactionManager {

        import ZookeeperObjectMapper._

        protected override def executorService = executor

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
            ZookeeperObjectMapper.this.getObjectPath(clazz, id, version)
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

            if (stat.getMzxid > zxid) {
                throw new ConcurrentModificationException(
                    s"${clazz.getSimpleName} with ID $id was modified " +
                    "during the transaction.")
            }

            ObjSnapshot(deserialize(data, clazz).asInstanceOf[Obj],
                        stat.getVersion)
        }

        override def commit(): Unit = {
            val ops = flattenOps ++ createStateOps
            var txn =
                curator.inTransaction().asInstanceOf[CuratorTransactionFinal]

            for ((Key(clazz, id), txOp) <- ops) txn = txOp match {
                case TxCreate(obj) =>
                    txn.create.forPath(getPath(clazz, id), serialize(obj)).and
                case TxUpdate(obj, ver) =>
                    txn.setData().withVersion(ver)
                        .forPath(getPath(clazz, id), serialize(obj)).and
                case TxDelete(ver) =>
                    txn.delete.withVersion(ver).forPath(getPath(clazz, id)).and
                case TxCreateNode(value) =>
                    txn.create.forPath(id, asBytes(value)).and
                case TxUpdateNode(value) =>
                    txn.setData().forPath(id, asBytes(value)).and
                case TxDeleteNode =>
                    txn.delete.forPath(id).and
                case TxNodeExists =>
                    throw new InternalObjectMapperException(
                        "TxNodeExists should have been filtered by flattenOps.")
            }

            try txn.commit() catch {
                case bve: BadVersionException =>
                    // NoNodeException is assumed to be due to concurrent delete
                    // operation because we already successfully fetched any
                    // objects that are being updated.
                    throw new ConcurrentModificationException(bve)
                case nee: NodeExistsException => rethrowException(ops, nee)
                case nne: NoNodeException => rethrowException(ops, nne)
                case nee: NotEmptyException => rethrowException(ops, nee)
                case rce: ReferenceConflictException => throw rce
                case ex: Exception =>
                    throw new InternalObjectMapperException(ex)
            }

            deleteState()
        }

        override protected def nodeExists(path: String): Boolean =
            curator.checkExists.forPath(path) != null

        override protected def childrenOf(path: String): Seq[String] = {
            val prefix = if (path == "/") path else path + "/"
            try {
                curator.getChildren.forPath(path).asScala.map(prefix + _)
            } catch {
                case nne: NoNodeException => Seq.empty
            }
        }

        def releaseLock(): Unit = try {
            curator.delete().forPath(lockPath)
        } catch {
            // Not much we can do. Fortunately, it's ephemeral.
            case ex: Exception => log.warn(
                s"Could not delete TransactionManager lock node $lockPath.", ex)
        }

        /** Get a string as bytes, or null if the string is null. */
        private def asBytes(s: String) = if (s != null) s.getBytes else null

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
         * Given a [[NodeExistsException]] and the list of operations submitted
         * for the transaction that produced the exception, throws an
         * appropriate exception, depending on the operation that caused the
         * exception.
         *
         * @throws ObjectExistsException if object creation failed.
         * @throws StorageNodeExistsException if node creation failed.
         * @throws InternalObjectMapperException for all other cases.
         */
        @throws[ObjectExistsException]
        @throws[StorageNodeExistsException]
        private def rethrowException(ops: Seq[(Key, TxOp)],
                                     e: NodeExistsException): Unit = {
            opForException(ops, e) match {
                case (Key(_, path), cm: TxCreateNode) =>
                    throw new StorageNodeExistsException(path)
                case (key: Key, _) =>
                    throw new ObjectExistsException(key.clazz, key.id)
                case _ => throw new InternalObjectMapperException(e)
            }
        }

        /**
         * Given a [[NoNodeException]] and the list of operations submitted
         * for the transaction that produced the exception, throws an
         * appropriate exception, depending on the operation that caused the
         * exception.
         *
         * @throws StorageNodeNotFoundException if a node was not found.
         * @throws ConcurrentModificationException for all other cases.
         */
        private def rethrowException(ops: Seq[(Key, TxOp)],
                                     e: NoNodeException): Unit = {
            opForException(ops, e) match {
                case (Key(_, path), _: TxUpdateNode) =>
                    throw new StorageNodeNotFoundException(path)
                case (Key(_, path), TxDeleteNode) =>
                    throw new StorageNodeNotFoundException(path)
                case _ => throw new ConcurrentModificationException(e)
            }
        }

        /**
         * Given a [[NotEmptyException]] and the list of operations submitted
         * for the transaction that produced the exception, throws an
         * appropriate exception, depending on the operation that caused the
         * exception.
         *
         * @throws ConcurrentModificationException if map deletion failed.
         * @throws InternalObjectMapperException for all other cases.
         */
        private def rethrowException(ops: Seq[(Key, TxOp)],
                                     e: NotEmptyException): Unit = {
            opForException(ops, e) match {
                case (_, TxDeleteNode) =>
                    // We added operations to delete all descendants, so this
                    // should only happen if there was a concurrent
                    // modification.
                    throw new ConcurrentModificationException(e)
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
        assert(!isBuilt)
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

        classInfo(clazz) = makeInfo(clazz)
        stateInfo(clazz) = new StateInfo
    }

    override def isRegistered(clazz: Class[_]) = {
        val registered = classInfo.contains(clazz)
        if (!registered)
            log.warn(s"Class ${clazz.getSimpleName} is not registered.")
        registered
    }

    override def build(): Unit = {
        initVersionNumber()
        ensureClassNodes()
        super.build()
    }

    private def getVersionNumberFromZkAndWatch: Long = {
        val watcher = new Watcher() {
            override def process(event: WatchedEvent) {
                event.getType match {
                    case NodeDataChanged => setVersionNumberAndWatch()
                    case _ =>  // Do nothing.
                }
            }
        }
        JLong.parseLong(new String(
                curator.getData.usingWatcher(watcher).forPath(versionNodePath)))
    }

    private def setVersionNumberAndWatch(): Unit = {
        version.set(getVersionNumberFromZkAndWatch)
    }

    private def initVersionNumber(): Unit = {
        try {
            ZKPaths.mkdirs(curator.getZookeeperClient.getZooKeeper,
                           versionNodePath, false)
            curator.create.forPath(versionNodePath, version.toString.getBytes)
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

    /**
     * Ensures that the class nodes in Zookeeper for each provided class exist,
     * creating them if needed.
     */
    private def ensureClassNodes(): Unit = {
        val classes = classInfo.keySet
        assert(classes.forall(isRegistered))

        // First try a multi-check for all the classes. If they already exist,
        // as they usually will except on the first startup, we can verify this
        // in a single round trip to Zookeeper.
        var txn = curator.inTransaction().asInstanceOf[CuratorTransactionFinal]
        for (clazz <- classes) {
            txn = txn.check().forPath(getClassPath(clazz)).and()
            txn = txn.check().forPath(getStateClassPath(clazz)).and()
        }
        try {
            txn.commit()
            return
        } catch {
            case ex: Exception =>
                log.info("Could not confirm existence of all class nodes in " +
                         "Zookeeper. Creating missing class node(s).")
        }

        // One or more didn't exist, so we'll have to check them individually.
        try {
            for (clazz <- classes) {
                ZKPaths.mkdirs(curator.getZookeeperClient.getZooKeeper,
                               getClassPath(clazz))
                ZKPaths.mkdirs(curator.getZookeeperClient.getZooKeeper,
                               getStateClassPath(clazz))
            }
        } catch {
            case ex: Exception => throw new InternalObjectMapperException(ex)
        }
    }

    @throws[ServiceUnavailableException]
    override def get[T](clazz: Class[T], id: ObjId): Future[T] = {
        assertBuilt()
        assert(isRegistered(clazz))

        val key = Key(clazz, getIdString(clazz, id))
        val path = getObjectPath(clazz, id)

        objectObservables.get(key) match {
            case Some(observable) =>
                observable.asInstanceOf[Observable[T]].asFuture
            case None => asObservable {
                curator.getData.inBackground(_).forPath(path)
            }.map[Notification[T]](makeFunc1 { event =>
                if (event.getResultCode == Code.OK.intValue()) {
                    Notification.createOnNext(deserialize(event.getData, clazz))
                } else {
                    Notification.createOnError(new NotFoundException(clazz, id))
                }
            }).dematerialize() asFuture
        }
    }

    @throws[ServiceUnavailableException]
    override def getAll[T](clazz: Class[T], ids: Seq[_ <: ObjId])
    : Future[Seq[T]] = {
        assertBuilt()
        assert(isRegistered(clazz))
        Future.sequence(ids.map(get(clazz, _)))
    }

    /**
     * Gets all instances of the specified class from Zookeeper.
     */
    @throws[ServiceUnavailableException]
    override def getAll[T](clazz: Class[T]): Future[Seq[T]] = {
        assertBuilt()
        assert(isRegistered(clazz))

        val all = Promise[Seq[T]]()
        val cb = new BackgroundCallback {
            override def processResult(client: CuratorFramework,
                                       evt: CuratorEvent): Unit = {
                assert(CuratorEventType.CHILDREN == evt.getType)
                getAll(clazz, evt.getChildren).onComplete {
                    case Success(l) => all trySuccess l
                    case Failure(t) => all tryFailure t
                }
            }
        }

        val path = getClassPath(clazz)
        try {
            curator.getChildren.inBackground(cb).forPath(path)
        } catch {
            case ex: Exception =>
                // Should have created this during class registration.
                throw new InternalObjectMapperException(
                    s"Node $path does not exist in Zookeeper.", ex)
        }
        all.future
    }

    /**
     * Returns true if the specified object exists in Zookeeper.
     */
    @throws[ServiceUnavailableException]
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
            curator.checkExists().inBackground(cb)
                   .forPath(getObjectPath(clazz, id))
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
    @throws[ServiceUnavailableException]
    override def multi(ops: Seq[PersistenceOp]): Unit = {
        assertBuilt()
        if (ops.isEmpty) return

        val manager = new ZoomTransactionManager(version.longValue())
        ops.foreach {
            case CreateOp(obj) =>
                manager.create(obj)
            case UpdateOp(obj, validator) =>
                manager.update(obj, validator)
            case DeleteOp(clazz, id, ignoresNeo) =>
                manager.delete(clazz, id, ignoresNeo)
            case CreateNodeOp(path, value) =>
                manager.createNode(path, value)
            case UpdateNodeOp(path, value) =>
                manager.updateNode(path, value)
            case DeleteNodeOp(path) =>
                manager.deleteNode(path)
        }

        try manager.commit() finally { manager.releaseLock() }
    }

    @throws[ServiceUnavailableException]
    override def observable[T](clazz: Class[T], id: ObjId): Observable[T] = {
        assertBuilt()
        assert(isRegistered(clazz))

        val key = Key(clazz, getIdString(clazz, id))
        val path = getObjectPath(clazz, id)

        objectObservables.getOrElse(key, {
            val obs = NodeObservable.create(curator, path,
                                            completeOnDelete = true)
                .map[Notification[T]](deserializerOf(clazz))
                .dematerialize().asInstanceOf[Observable[T]]
                .onErrorResumeNext(makeFunc1((t: Throwable) => t match {
                case e: NodeObservableClosedException =>
                    objectObservables.remove(key)
                    observable(clazz, id)
                case e: NoNodeException =>
                    Observable.error(new NotFoundException(clazz, id))
                case e: Throwable =>
                    Observable.error(e)
            }))
            objectObservables.putIfAbsent(key, obs).getOrElse(obs)
        }).asInstanceOf[Observable[T]]
    }

    /**
     * Refer to the interface documentation for functionality.
     *
     * This implementation involves a BLOCKING call when the observable is first
     * created, as we initialize the connection to ZK.
     */
    @throws[ServiceUnavailableException]
    override def observable[T](clazz: Class[T]): Observable[Observable[T]] = {
        assertBuilt()
        assert(isRegistered(clazz))

        classCaches.getOrElse(clazz, {
            val onLastUnsubscribe: ClassSubscriptionCache[_] => Unit = c => {
                classCaches.remove(clazz)
            }
            val cc = new ClassSubscriptionCache(clazz, getClassPath(clazz),
                                                curator, onLastUnsubscribe)
            classCaches.putIfAbsent(clazz, cc).getOrElse(cc)
        }).asInstanceOf[ClassSubscriptionCache[T]].observable
    }

    // We should have public subscription methods, but we don't currently
    // need them, and this is easier to implement for testing.
    @VisibleForTesting
    protected[storage] def getNodeValue(path: String): String = {
        val data = curator.getData.forPath(path)
        if (data == null) null else new String(data)
    }

    @VisibleForTesting
    protected[storage] def getNodeChildren(path: String): Seq[String] = {
        curator.getChildren.forPath(path).asScala
    }

    /**
     * @return The number of subscriptions to the given class. If the
     *         corresponding entry does not exist, None is returned.
     */
    @VisibleForTesting
    protected[storage] def subscriptionCount[T](clazz: Class[T]): Option[Int] = {
        classCaches.get(clazz).map(_.subscriptionCount)
    }

    @inline
    private[storage] def getClassPath(clazz: Class[_],
                                      version: Long = version.longValue())
    : String = {
        basePath(version) + "/" + clazz.getSimpleName
    }

    @inline
    private[storage] def getObjectPath(clazz: Class[_], id: ObjId,
                                       version: Long = version.longValue())
    : String = {
        getClassPath(clazz, version) + "/" + getIdString(clazz, id)
    }

}

object ZookeeperObjectMapper {

    private val VersionNode = "dataset_version"
    private val InitialZoomDataSetVersion = 1

    private[storage] final class MessageClassInfo(clazz: Class[_])
        extends ClassInfo(clazz) {

        val idFieldDesc =
            ProtoFieldBinding.getMessageField(clazz, FieldBinding.ID_FIELD)

        def idOf(obj: Obj) = obj.asInstanceOf[Message].getField(idFieldDesc)
    }

    private [storage] final class JavaClassInfo(clazz: Class[_])
        extends ClassInfo(clazz) {

        val idField = clazz.getDeclaredField(FieldBinding.ID_FIELD)

        idField.setAccessible(true)

        def idOf(obj: Obj) = idField.get(obj)
    }

    protected val log = LoggerFactory.getLogger(ZookeeperObjectMapper.getClass)

    private val jsonFactory = new JsonFactory(new ObjectMapper())
    private val deserializers =
        new TrieMap[Class[_], Func1[ChildData, Notification[_]]]

    private[storage] def makeInfo(clazz: Class[_])
    : ClassInfo = {
        try {
            if (classOf[Message].isAssignableFrom(clazz)) {
                new MessageClassInfo(clazz)
            } else {
                new JavaClassInfo(clazz)
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

    private def serializeMessage(msg: Message): Array[Byte] = {
        msg.toString.getBytes
    }

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

    private[storage] def deserialize[T](data: Array[Byte],  clazz: Class[T])
    : T = {
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

    private def deserializerOf[T](clazz: Class[T])
    : Func1[ChildData, Notification[T]] = {
        deserializers.getOrElseUpdate(
            clazz,
            makeFunc1((data: ChildData) => data match {
                case null =>
                    Notification.createOnError[T](new NotFoundException(clazz,
                                                                        None))
                case cd =>
                    Notification.createOnNext[T](deserialize(cd.getData, clazz))
            })).asInstanceOf[Func1[ChildData, Notification[T]]]
    }

}