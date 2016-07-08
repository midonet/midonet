/*
 * Copyright 2016 Midokura SARL
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
import java.util.ConcurrentModificationException
import java.util.concurrent.Executors._
import java.util.concurrent.atomic.AtomicLong

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.concurrent.ExecutionContext._
import scala.concurrent.{Future, Promise}
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

import com.codahale.metrics.MetricRegistry
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.common.annotations.VisibleForTesting
import com.google.protobuf.{Message, TextFormat}

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.api.transaction.CuratorTransactionFinal
import org.apache.curator.framework.api.{BackgroundCallback, CuratorEvent, CuratorEventType}
import org.apache.curator.framework.recipes.cache.ChildData
import org.apache.curator.utils.ZKPaths
import org.apache.zookeeper.KeeperException._
import org.apache.zookeeper.OpResult.ErrorResult
import org.apache.zookeeper._
import org.apache.zookeeper.data.Stat
import org.slf4j.LoggerFactory

import rx.Notification._
import rx.Observable.OnSubscribe
import rx.functions.Func1
import rx.{Notification, Observable, Subscriber}

import org.midonet.cluster.data.storage.CuratorUtil._
import org.midonet.cluster.data.storage.TransactionManager._
import org.midonet.cluster.data.{Obj, ObjId}
import org.midonet.cluster.services.state.client.StateTableClient
import org.midonet.cluster.util.{NodeObservable, NodeObservableClosedException, PathCacheClosedException}
import org.midonet.util.concurrent.NamedThreadFactory
import org.midonet.util.eventloop.Reactor
import org.midonet.util.functors.makeFunc1

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
 */
class ZookeeperObjectMapper(protected override val rootPath: String,
                            protected override val namespace: String,
                            protected override val curator: CuratorFramework,
                            protected override val failFastCurator: CuratorFramework,
                            protected override val stateTables: StateTableClient,
                            protected override val reactor: Reactor,
                            metricsRegistry: MetricRegistry = null)
    extends ZookeeperObjectState with ZookeeperStateTable with Storage
    with StorageInternals {

    import ZookeeperObjectMapper._

    protected[storage] override val version = new AtomicLong(0)
    protected[storage] override val zoomPath = s"$rootPath/zoom"

    private[storage] val basePath = s"$zoomPath/" + version.get
    private[storage] val locksPath = basePath + s"/zoomlocks/lock"
    private[storage] val modelPath = basePath + s"/models"

    private val executor = newSingleThreadExecutor(
        new NamedThreadFactory("zoom", isDaemon = true))
    private implicit val executionContext = fromExecutorService(executor)

    private val objectObservableRef = new AtomicLong()

    private val simpleNameToClass = new mutable.HashMap[String, Class[_]]()
    private val objectObservables = new TrieMap[Key, ObjectObservable]
    private val classObservables = new TrieMap[Class[_], ClassObservable]

    /* Functions and variables to expose metrics using JMX in class
       ZoomMetrics. */
    implicit protected override val metrics = metricsRegistry match {
        case null => BlackHoleZoomMetrics
        case registry => new JmxZoomMetrics(this, registry)
    }

    curator.getConnectionStateListenable
           .addListener(metrics.zkConnectionStateListener())

    private[storage] def totalObjectObservableCount: Int =
        objectObservables.size
    private[storage] def totalClassObservableCount: Int =
        classObservables.size

    private[storage] def startedObjectObservableCount: Int =
        objectObservables.values.count(_.nodeObservable.isStarted)
    private[storage] def startedClassObservableCount: Int =
        classObservables.values.count(_.cache.isStarted)

    private[storage] def objectObservableCounters: Map[Class[_], Int] = {
        val map = new mutable.HashMap[Class[_], Int]
        val it = objectObservables.iterator
        while (it.hasNext) {
            val (key, obs) = it.next()
            if (obs.nodeObservable.isStarted) {
                map(key.clazz) = map.getOrElse(key.clazz, 0) + 1
            }
        }
        map.toMap
    }

    private[storage] def allClassObservables: Set[Class[_]] =
        classObservables.keySet.toSet
    private[storage] def startedClassObservables: Set[Class[_]] =
        classObservables.filter(_._2.cache.isStarted).keySet.toSet

    private[storage] def zkConnectionState: String =
        curator.getZookeeperClient.getZooKeeper.getState.toString
    /* End of functions and variable used for JMX monitoring. */

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
            with StateTableTransactionManager {

        import ZookeeperObjectMapper._

        protected override def executorService = executor

        // Create an ephemeral node so that we can get Zookeeper's current
        // ZXID. This will allow us to determine if any of the nodes we read
        // have been modified since the TransactionManager was created, allowing
        // us to ensure a consistent read across multiple nodes.
        private val (lockPath: String, zxid: Long) = try {
            val path = curator.create().creatingParentsIfNeeded()
                              .withMode(CreateMode.EPHEMERAL_SEQUENTIAL)
                              .forPath(locksPath)
            val stat = new Stat()
            curator.getData.storingStatIn(stat).forPath(path)
            (path, stat.getCzxid)
        } catch {
            case ex: Exception => throw new InternalObjectMapperException(
                "Could not acquire current zxid.", ex)
        }

        private def getPath(clazz: Class[_], id: ObjId) = {
            ZookeeperObjectMapper.this.objectPath(clazz, id, version)
        }

        override def isRegistered(clazz: Class[_]): Boolean = {
            ZookeeperObjectMapper.this.isRegistered(clazz)
        }

        override def getSnapshot(clazz: Class[_], id: ObjId)
        : Observable[ObjSnapshot] = {
            val path = getPath(clazz, id)

            asObservable {
                curator.getData.inBackground(_).forPath(path)
            } map[Notification[ObjSnapshot]] makeFunc1 { event =>
                if (event.getResultCode == Code.OK.intValue()) {
                    if (event.getStat.getMzxid > zxid) {
                        createOnError(new ConcurrentModificationException(
                            s"${clazz.getSimpleName} with ID " +
                            s"${getIdString(clazz, id)} was modified during " +
                            s"the transaction."))
                    } else {
                        createOnNext(
                            ObjSnapshot(deserialize(event.getData, clazz).asInstanceOf[Obj],
                                        event.getStat.getVersion))
                    }
                } else if (event.getResultCode == Code.NONODE.intValue()) {
                    metrics.zkNoNodeTriggered()
                    createOnError(new NotFoundException(clazz, id))
                } else {
                    createOnError(new InternalObjectMapperException(
                        KeeperException.create(Code.get(event.getResultCode), path)))
                }
            } dematerialize()
        }

        override def getIds(clazz: Class[_]): Observable[Seq[ObjId]] = {
            val path = classPath(clazz)

            asObservable {
                curator.getChildren.inBackground(_).forPath(path)
            } map[Notification[Seq[ObjId]]] makeFunc1 { event =>
                if (event.getResultCode == Code.OK.intValue()) {
                    createOnNext(event.getChildren.asScala)
                } else {
                    createOnError(new InternalObjectMapperException(
                        KeeperException.create(Code.get(event.getResultCode), path)))
                }
            } dematerialize()
        }

        /** Commits the operations from the current transaction to the storage
          * backend. */
        @throws[InternalObjectMapperException]
        @throws[ConcurrentModificationException]
        @throws[ReferenceConflictException]
        @throws[ObjectExistsException]
        @throws[StorageNodeExistsException]
        @throws[StorageNodeNotFoundException]
        override def commit(): Unit = {
            val ops = flattenOps ++ createStateTableOps
            var txn =
                curator.inTransaction().asInstanceOf[CuratorTransactionFinal]

            for ((Key(clazz, id), txOp) <- ops) txn = txOp match {
                case TxCreate(obj) =>
                    val path = getPath(clazz, id)
                    log.debug(s"Create: $path")
                    txn.create.forPath(path, serialize(obj)).and
                case TxUpdate(obj, ver) =>
                    val path = getPath(clazz, id)
                    log.debug(s"Update ($ver): $path")
                    txn.setData().withVersion(ver)
                        .forPath(path, serialize(obj)).and
                case TxDelete(ver) =>
                    val path = getPath(clazz, id)
                    log.debug(s"Delete ($ver): $path")
                    txn.delete.withVersion(ver).forPath(path).and
                case TxCreateNode(value) =>
                    log.debug(s"Create node: $id")
                    txn.create.forPath(id, asBytes(value)).and
                case TxUpdateNode(value) =>
                    log.debug(s"Update node: $id")
                    txn.setData().forPath(id, asBytes(value)).and
                case TxDeleteNode =>
                    log.debug(s"Delete node: $id")
                    txn.delete.forPath(id).and
                case TxNodeExists =>
                    throw new InternalObjectMapperException(
                        "TxNodeExists should have been filtered by flattenOps.")
            }

            val startTime = System.nanoTime()
            try {
                txn.commit()
            } catch {
                case bve: BadVersionException =>
                    // NoNodeException is assumed to be due to concurrent delete
                    // operation because we already successfully fetched any
                    // objects that are being updated.
                    throw new ConcurrentModificationException(bve)
                case e: KeeperException =>
                    metrics.count(e)
                    rethrowException(ops, e)
                case rce: ReferenceConflictException => throw rce
                case NonFatal(ex) => throw new InternalObjectMapperException(ex)
            } finally {
                metrics.addMultiLatency(System.nanoTime() - startTime)
            }

            deleteStateTables()
        }

        override protected def nodeExists(path: String): Boolean =
            curator.checkExists.forPath(path) != null

        override protected def childrenOf(path: String): Seq[String] = {
            val prefix = if (path == "/") path else path + "/"
            try {
                curator.getChildren.forPath(path).asScala.map(prefix + _)
            } catch {
                case nne: NoNodeException =>
                    metrics.count(nne)
                    Seq.empty
            }
        }

        def releaseLock(): Unit = try {
            curator.delete().forPath(lockPath)
        } catch {
            // Not much we can do. Fortunately, it's ephemeral.
            case NonFatal(e) => log.warn(
                s"Could not delete TransactionManager lock node $lockPath.", e)
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
         * Given a [[Throwable]] and the list of operations submitted
         * for the transaction that produced the exception, throws an
         * appropriate exception, depending on the operation that caused the
         * exception.
         *
         * @throws ObjectExistsException if object creation failed.
         * @throws StorageNodeExistsException if node creation failed.
         * @throws StorageNodeNotFoundException if a node was not found.
         * @throws ConcurrentModificationException for all other cases.
         * @throws InternalObjectMapperException for all other cases.
         */
        private def rethrowException(ops: Seq[(Key, TxOp)], e: Throwable)
        : Unit = e match {
            case e: NodeExistsException => opForException(ops, e) match {
                    case (Key(_, path), cm: TxCreateNode) =>
                        throw new StorageNodeExistsException(path)
                    case (key: Key, _) =>
                        throw new ObjectExistsException(key.clazz, key.id)
                    case _ => throw new InternalObjectMapperException(e)
                }
            case e: NoNodeException => opForException(ops, e) match {
                    case (Key(_, path), _: TxUpdateNode) =>
                        throw new StorageNodeNotFoundException(path)
                    case (Key(_, path), TxDeleteNode) =>
                        throw new StorageNodeNotFoundException(path)
                    case _ => throw new ConcurrentModificationException(e)
                }
            case e: NotEmptyException => opForException(ops, e) match {
                case (_, TxDeleteNode) =>
                    // We added operations to delete all descendants, so this
                    // should only happen if there was a concurrent
                    // modification.
                    throw new ConcurrentModificationException(e)
                case _ => throw new InternalObjectMapperException(e)
            }
            case _ => throw new InternalObjectMapperException(e)
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
        tableInfo(clazz) = new TableInfo
    }

    override def isRegistered(clazz: Class[_]) = {
        val registered = classInfo.contains(clazz)
        if (!registered)
            log.warn(s"Class ${clazz.getSimpleName} is not registered.")
        registered
    }

    override def build(): Unit = {
        ensureClassNodes()
        super.build()
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
            txn = txn.check().forPath(classPath(clazz)).and()
            txn = txn.check().forPath(stateClassPath(namespace, clazz)).and()
            txn = txn.check().forPath(tablesClassPath(clazz)).and()
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
                               classPath(clazz))
                ZKPaths.mkdirs(curator.getZookeeperClient.getZooKeeper,
                               stateClassPath(namespace, clazz))
                ZKPaths.mkdirs(curator.getZookeeperClient.getZooKeeper,
                               tablesClassPath(clazz))
            }
        } catch {
            case ex: Exception => throw new InternalObjectMapperException(ex)
        }
    }

    /** Produce the instance of [[T]] deserialized from the event.
      *
      * Metrics-aware.
      */
    private def tryDeserialize[T](clazz: Class[T], id: ObjId,
                                  event: CuratorEvent): T = {
        if (event.getResultCode == Code.OK.intValue()) {
            deserialize(event.getData, clazz)
        } else {
            if (event.getResultCode == Code.NONODE.intValue()) {
                metrics.zkNoNodeTriggered()
            }
            throw new NotFoundException(clazz, id)
        }
    }

    @throws[ServiceUnavailableException]
    override def get[T](clazz: Class[T], id: ObjId): Future[T] = {
        assertBuilt()
        assert(isRegistered(clazz))
        val path = objectPath(clazz, id)
        val p = Promise[T]()
        val start = System.nanoTime()
        val cb = new BackgroundCallback {
            override def processResult(client: CuratorFramework,
                                       event: CuratorEvent): Unit = {
                metrics.addLatency(event.getType, System.nanoTime() - start)
                try {
                    p.trySuccess(tryDeserialize(clazz, id, event))
                } catch {
                    case NonFatal(t) => p.tryFailure(t)
                }
            }
        }
        curator.getData.inBackground(cb).forPath(path)
        p.future
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
        val start = System.nanoTime()
        val cb = new BackgroundCallback {
            override def processResult(client: CuratorFramework,
                                       evt: CuratorEvent): Unit = {
                val end = System.nanoTime()
                metrics.addReadChildrenLatency(end-start)
                assert(CuratorEventType.CHILDREN == evt.getType)
                getAll(clazz, evt.getChildren).onComplete {
                    case Success(l) => all trySuccess l
                    case Failure(t) => all tryFailure t
                }
            }
        }

        val path = classPath(clazz)
        try {
            curator.getChildren.inBackground(cb).forPath(path)
        } catch {
            case ex: Exception => // Should have been created on build()
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
                   .forPath(objectPath(clazz, id))
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

        try manager.commit() finally manager.releaseLock()
    }

    /**
      * Creates a new storage transaction that allows multiple read and write
      * operations to be executed atomically. The transaction guarantees that
      * the value of an object is not modified until the transaction is
      * completed or that the transaction will fail with a
      * [[java.util.ConcurrentModificationException]].
      */
    @throws[ServiceUnavailableException]
    override def transaction(): Transaction = {
        assertBuilt()
        new ZoomTransactionManager(version.longValue())
    }

    /**
      * @see [[Storage.observable()]]
      */
    @throws[ServiceUnavailableException]
    override def observable[T](clazz: Class[T], id: ObjId): Observable[T] = {
        assertBuilt()
        assert(isRegistered(clazz))

        Observable.create(new OnSubscribe[T] {
            override def call(child: Subscriber[_ >: T]): Unit = {
                // Only request and subscribe to the internal, cache-able
                // observable when a child subscribes.
                internalObservable[T](clazz, id, version.get, OnCloseDefault)
                    .subscribe(child)
            }
        })
    }

    /**
      * Returns a cache-able, recoverable observable for the specified object.
      * If an observable for the object already exists in the cache, then
      * the method returns the same observable. Otherwise, the method creates
      * a new [[NodeObservable]] with an error handler and caches it, where the
      * close handler removes it from the cache.
      */
    protected override def internalObservable[T](clazz: Class[T], id: ObjId,
                                                 version: Long,
                                                 onClose: => Unit)
    : Observable[T] = {
        val key = Key(clazz, getIdString(clazz, id))
        val path = objectPath(clazz, id, version)

        objectObservables.getOrElse(key, {
            val ref = objectObservableRef.getAndIncrement()

            val nodeObservable = NodeObservable.create(
                curator, path, completeOnDelete = true, metrics, {
                    objectObservables.remove(key, ObjectObservable(ref))
                    onClose
                })

            val objectObservable = nodeObservable
                .map[Notification[T]](deserializerOf(clazz))
                .dematerialize().asInstanceOf[Observable[T]]
                .onErrorResumeNext(makeFunc1((t: Throwable) => t match {
                    case e: NodeObservableClosedException =>
                        metrics.count(e)
                        internalObservable(clazz, id, version, OnCloseDefault)
                    case e: NoNodeException =>
                        metrics.count(e)
                        Observable.error(new NotFoundException(clazz, id))
                    case e: Throwable =>
                        metrics.count(e)
                        Observable.error(e)
                }))

            val entry = ObjectObservable(ref, nodeObservable, objectObservable)

            objectObservables.putIfAbsent(key, entry).getOrElse(entry)
        }).objectObservable.asInstanceOf[Observable[T]]
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

        classObservables.getOrElse(clazz, {
            val cache = new ClassSubscriptionCache(clazz, classPath(clazz),
                                                   curator, metrics)
            val obs = cache.observable
                .onErrorResumeNext(makeFunc1((t: Throwable) => t match {
                    case e: PathCacheClosedException =>
                        metrics.count(e)
                        classObservables.remove(clazz, ClassObservable(cache))
                        observable(clazz)
                    case e: Throwable =>
                        metrics.count(e)
                        Observable.error(e)
                }))
            val entry = ClassObservable(cache, obs)
            classObservables.putIfAbsent(clazz, entry).getOrElse(entry)
        }).clazz.asInstanceOf[Observable[Observable[T]]]
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

    @inline
    private[storage] def classPath(clazz: Class[_]): String = {
        modelPath + "/" + clazz.getSimpleName
    }

    @inline
    protected[storage] override def objectPath(clazz: Class[_], id: ObjId,
                                               version: Long = version.longValue())
    : String = {
        classPath(clazz) + "/" + getIdString(clazz, id)
    }

}

object ZookeeperObjectMapper {
    private[storage] final class MessageClassInfo(clazz: Class[_])
        extends ClassInfo(clazz) {

        val idFieldDesc =
            ProtoFieldBinding.getMessageField(clazz, FieldBinding.ID_FIELD)

        def idOf(obj: Obj) = obj.asInstanceOf[Message].getField(idFieldDesc)
    }

    private[storage] final class JavaClassInfo(clazz: Class[_])
        extends ClassInfo(clazz) {

        val idField = clazz.getDeclaredField(FieldBinding.ID_FIELD)

        idField.setAccessible(true)

        def idOf(obj: Obj) = idField.get(obj)
    }

    private case class ObjectObservable(ref: Long,
                                        nodeObservable: NodeObservable = null,
                                        objectObservable: Observable[_] = null) {
        override def equals(other: Any): Boolean = other match {
            case o: ObjectObservable => o.ref == ref
            case _ => false
        }
        override def hashCode: Int = ref.hashCode
    }

    private case class ClassObservable(cache: ClassSubscriptionCache[_],
                                       clazz: Observable[_] = null) {
        override def equals(other: Any): Boolean = other match {
            case o: ClassObservable => o.cache eq cache
            case _ => false
        }
        override def hashCode: Int = cache.hashCode
    }

    protected val log = LoggerFactory.getLogger("org.midonet.nsdb")

    private val OnCloseDefault = { }
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
            val generator = jsonFactory.createGenerator(writer)
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
        val parser = jsonFactory.createParser(json)
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
