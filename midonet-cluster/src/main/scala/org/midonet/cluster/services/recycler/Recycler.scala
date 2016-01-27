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

package org.midonet.cluster.services.recycler

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.{AtomicInteger, AtomicBoolean}
import java.util.concurrent._

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{Future, ExecutionContext, Promise}
import scala.util.{Failure, Success}

import com.google.inject.Inject
import com.google.inject.name.Named
import com.typesafe.scalalogging.Logger

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.api.{BackgroundCallback, CuratorEvent}
import org.apache.curator.utils.ZKPaths
import org.apache.zookeeper.KeeperException
import org.apache.zookeeper.KeeperException.Code
import org.slf4j.LoggerFactory

import org.midonet.cluster.data.storage.ZookeeperObjectMapper
import org.midonet.cluster.models.Topology.Host
import org.midonet.cluster.services.recycler.Recycler.{ChildContext, Data, RecyclingContext, RecyclingException}
import org.midonet.cluster.services.{ClusterService, MidonetBackend, Minion}
import org.midonet.cluster.{ClusterConfig, ClusterNode, recyclerLog}
import org.midonet.util.UnixClock
import org.midonet.util.functors.makeRunnable

object Recycler {

    private val Data = Array[Byte](1)
    private val ClusterNamespaceId = Seq(MidonetBackend.ClusterNamespaceId.toString)

    class RecyclingPromise[T] {
        private val complete = Promise[T]

        def future = complete.future

        def success(t: T): Unit = {
            complete trySuccess t
        }

        def fail(e: Throwable): Unit = {
            complete tryFailure e
        }

        def fail(event: CuratorEvent): Unit = {
            fail(KeeperException.create(Code.get(event.getResultCode),
                                        event.getPath))
        }
    }

    class RecyclingContext extends RecyclingPromise[RecyclingContext] {
        var version = 0
        var timestamp = 0L
        var count = 0L

        var hosts: Set[String] = null
        var namespaces: Set[String] = null

        val deletedNamespaces = new AtomicInteger
        val failedNamespaces = new AtomicInteger

        val deletedObjects = new AtomicInteger
        val failedObjects = new AtomicInteger

        val modelObjects = new ConcurrentHashMap[Class[_], Set[String]]()
        val stateObjects = new ConcurrentHashMap[(String, Class[_]), Seq[String]]()

        def success(): Unit = success(this)

        override def fail(e: Throwable): Unit = {
            if (e.isInstanceOf[RecyclingException]) fail(e)
            else fail(RecyclingException(this, e))
        }
    }

    class ChildContext(val parent: RecyclingContext, val tag: AnyRef = null)
        extends RecyclingPromise[Boolean]

    case class RecyclingException(context: RecyclingContext, inner: Throwable)
        extends Exception(s"Recycling failed: ${inner.getMessage}", inner)

}

/**
  * This service does garbage collection in NSDB.
  *
  * The use cases are multiple.  The prominent example are persistent nodes
  * created in zk to host a subtree of ephemeral nodes for state (the parent
  * cannot be ephemeral as well as ZK wouldn't allow children no it).
  *
  * This Minion is responsible for doing the necessary cleanup on this and
  * other similar cases, for a neat NSDB.
  */
@ClusterService(name = "recycler")
class Recycler @Inject()(context: ClusterNode.Context, backend: MidonetBackend,
                         @Named("cluster-pool") executor: ScheduledExecutorService,
                         config: ClusterConfig)
    extends Minion(context) {

    private val log = Logger(LoggerFactory.getLogger(recyclerLog))

    private implicit val ec = ExecutionContext.fromExecutor(executor)

    private val recyclingInterval = (config.recycler.interval minutes) max minimumInterval
    private val runInterval = (recyclingInterval / 10) max minimumInterval

    private val store: ZookeeperObjectMapper = validateStore
    private val curator = backend.curator
    private val clock = UnixClock()
    private val running = new AtomicBoolean()

    private val recycleTask = makeRunnable { recycle() }
    @volatile private var taskFuture: ScheduledFuture[_] = null

    @volatile private var recyclingStepIndex = 0
    private val recyclingStepCount = 8

    private val isRecyclable = new BackgroundCallback {
        override def processResult(client: CuratorFramework,
                                   event: CuratorEvent): Unit = {
            val context = event.getContext.asInstanceOf[RecyclingContext]
            if (event.getResultCode == Code.OK.intValue()) {
                val time = clock.time
                if (time - event.getStat.getMtime > recyclingInterval.toMillis) {
                    log debug s"Marking NSDB for recycling at $time ${recyclingStep()}"
                    curator.setData()
                           .withVersion(event.getStat.getVersion)
                           .inBackground(isRecycler, context, executor)
                           .forPath(store.basePath, Data)
                } else {
                    log debug "Skipping NSDB recycling: already recycled at " +
                              s"${event.getStat.getMtime} current time is $time"
                    context.success()
                }
            } else {
                context fail event
            }
        }
    }

    private val isRecycler = new BackgroundCallback {
        override def processResult(client: CuratorFramework,
                                   event: CuratorEvent): Unit = {
            val context = event.getContext.asInstanceOf[RecyclingContext]
            if (event.getResultCode == Code.OK.intValue()) {
                log debug s"Collecting current hosts ${recyclingStep()}"
                try {
                    context.version =
                        Integer.parseInt(ZKPaths.getNodeFromPath(event.getPath))
                    log debug s"NSDB version is ${context.version}"
                } catch {
                    case e: NumberFormatException =>
                        log error s"Invalid NSDB version for path ${event.getPath}"
                        context fail RecyclingException(context, e)
                        return
                }
                context.timestamp = event.getStat.getMtime
                curator.getChildren
                       .inBackground(collectHosts, context, executor)
                       .forPath(store.classPath(classOf[Host]))
            } else if (event.getResultCode == Code.BADVERSION.intValue()) {
                log info "NSDB root has been concurrently modified: canceling"
                context.success()
            } else {
                context fail event
            }
        }
    }

    private val collectHosts = new BackgroundCallback {
        override def processResult(client: CuratorFramework,
                                   event: CuratorEvent): Unit = {
            val context = event.getContext.asInstanceOf[RecyclingContext]
            if (event.getResultCode == Code.OK.intValue()) {
                context.hosts = event.getChildren.asScala.toSet
                log debug s"Collected ${context.hosts.size} hosts"
                log debug s"Collecting current state namespaces ${recyclingStep()}"
                curator.getChildren
                       .inBackground(collectNamespaces, context, executor)
                       .forPath(store.statePath(context.version))
            } else {
                context fail event
            }
        }
    }

    private val collectNamespaces = new BackgroundCallback {
        override def processResult(client: CuratorFramework,
                                   event: CuratorEvent): Unit = {
            val context = event.getContext.asInstanceOf[RecyclingContext]
            if (event.getResultCode == Code.OK.intValue()) {
                context.namespaces = event.getChildren.asScala.toSet
                log debug s"Collected ${context.namespaces.size} namespaces"
                deleteNamespaces(context)
            } else {
                context fail event
            }
        }
    }

    private val verifyNamespace = new BackgroundCallback {
        override def processResult(client: CuratorFramework,
                                   event: CuratorEvent): Unit = {
            val context = event.getContext.asInstanceOf[ChildContext]
            if (event.getResultCode == Code.OK.intValue() &&
                event.getStat.getCtime < context.parent.timestamp) {
                // We only delete those namespaces that have been created
                // before the beginning of the recycling task, to ensure that we
                // do not delete the namespace for a new host.
                log debug s"Deleting namespace ${event.getPath} with " +
                          s"timestamp ${event.getStat.getCtime}"
                curator.delete()
                       .deletingChildrenIfNeeded()
                       .withVersion(event.getStat.getVersion)
                       .inBackground(deleteNamespace, context, executor)
                       .forPath(event.getPath)
            } else {
                // Else, we ignore the namespace.
                if (event.getResultCode != Code.OK.intValue()) {
                    context.parent.failedNamespaces.incrementAndGet()
                }
                context success true
            }
        }
    }

    private val deleteNamespace = new BackgroundCallback {
        override def processResult(client: CuratorFramework,
                                   event: CuratorEvent): Unit = {
            val context = event.getContext.asInstanceOf[ChildContext]
            if (event.getResultCode == Code.OK.intValue()) {
                context.parent.deletedNamespaces.incrementAndGet()
            } else {
                context.parent.failedNamespaces.incrementAndGet()
            }
            context success true
        }
    }

    private val collectModelObjects = new BackgroundCallback {
        override def processResult(client: CuratorFramework,
                                   event: CuratorEvent): Unit = {
            val context = event.getContext.asInstanceOf[ChildContext]
            val clazz = context.tag.asInstanceOf[Class[_]]
            if (event.getResultCode == Code.OK.intValue()) {
                log debug s"Collected ${event.getChildren.size} objects for " +
                          s"class ${clazz.getSimpleName}"
                context.parent.modelObjects
                              .putIfAbsent(clazz, event.getChildren.asScala.toSet)
                context success true
            } else {
                context fail event
            }
        }
    }

    private val collectStateObjects = new BackgroundCallback {
        override def processResult(client: CuratorFramework,
                                   event: CuratorEvent): Unit = {
            val context = event.getContext.asInstanceOf[ChildContext]
            val (host, clazz) = context.tag.asInstanceOf[(String, Class[_])]
            if (event.getResultCode == Code.OK.intValue()) {
                log debug s"Collected ${event.getChildren.size} objects for " +
                          s"host $host class ${clazz.getSimpleName}"
                context.parent.stateObjects
                              .putIfAbsent((host, clazz), event.getChildren.asScala)
                context success true

            } else if (event.getResultCode == Code.NONODE.intValue()) {
                // We ignore NONODE errors, because the class state keys are
                // created on demand.
                context success true
            } else {
                context fail event
            }
        }
    }

    private val verifyObject = new BackgroundCallback {
        override def processResult(client: CuratorFramework,
                                   event: CuratorEvent): Unit = {
            val context = event.getContext.asInstanceOf[ChildContext]
            if (event.getResultCode == Code.OK.intValue() &&
                event.getStat.getCtime < context.parent.timestamp) {
                // We only delete those objects that have been created before
                // the beginning of the recycling task, to ensure that we do
                // not delete the state for a new object.
                log debug s"Deleting object ${event.getPath} with timestamp " +
                          s"${event.getStat.getCtime}"
                curator.delete()
                       .deletingChildrenIfNeeded()
                       .withVersion(event.getStat.getVersion)
                       .inBackground(deleteObject, context, executor)
                       .forPath(event.getPath)
            } else {
                if (event.getResultCode != Code.OK.intValue()) {
                    context.parent.failedObjects.incrementAndGet()
                }
                context success true
            }
        }
    }

    private val deleteObject = new BackgroundCallback {
        override def processResult(client: CuratorFramework,
                                   event: CuratorEvent): Unit = {
            val context = event.getContext.asInstanceOf[ChildContext]
            if (event.getResultCode == Code.OK.intValue()) {
                context.parent.deletedObjects.incrementAndGet()
            } else {
                context.parent.failedObjects.incrementAndGet()
            }
            context success true
        }
    }

    //private val paths = new PathBuilder(config.backend.rootKey)

    override def isEnabled = {
        config.recycler.isEnabled
    }

    override def doStart(): Unit = {
        log info "Starting NSDB recycling service"
        if (store ne null) {
            val initialDelay = ThreadLocalRandom.current()
                .nextLong(runInterval.toMinutes)
            schedule(initialDelay minutes)
        } else {
            log warn "The NSDB recycler requires ZOOM backend storage"
        }
        notifyStarted()
    }

    override def doStop(): Unit = {
        log info "Stopping NSDB recycling service"
        val future = taskFuture
        if (future ne null) {
            future.cancel(true)
            taskFuture = null
        }
        notifyStopped()
    }

    protected def minimumInterval = 1 minute

    private def validateStore: ZookeeperObjectMapper = {
        backend.store match {
            case zoom: ZookeeperObjectMapper => zoom
            case _ => null
        }
    }

    private def schedule(delay: Duration): Unit = {
        log debug s"Scheduling recycling task in $delay"
        val future = taskFuture
        if (future ne null) {
            future.cancel(false)
        }
        taskFuture = executor.schedule(recycleTask, delay.toMinutes,
                                       TimeUnit.MINUTES)
    }

    private def recycle(): Unit = {
        // Verify if a recycling operation is already running, in which case
        // skip the current recycling and reschedule the text.
        if (!running.compareAndSet(false, true)) {
            log debug s"Recycling already running"
            return
        }

        log info "Collecting NSDB recycling information"
        log debug s"Verifying if NSDB is recyclable ${recyclingStep()}"
        val context = new RecyclingContext
        curator.getData
               .inBackground(isRecyclable, context, executor)
               .forPath(store.basePath)
        context.future onComplete { result =>
            result match {
                case Success(c) =>
                    log info "NSDB recycling completed successfully: recycled " +
                             s"${c.count} objects"
                case Failure(e: RecyclingException) =>
                    log.warn("NSDB recycling failed", e)
                case Failure(e) =>
                    log.error("NSDB recycling failed", e)
            }
            running.compareAndSet(true, false)
        }
    }

    private def deleteNamespaces(context: RecyclingContext): Unit = {
        log debug s"Deleting orphan namespaces ${recyclingStep()}"
        val namespaces = context.namespaces -- context.hosts --
                         Recycler.ClusterNamespaceId
        val futures = for (namespace <- namespaces) yield {
            val childContext = new ChildContext(context)
            log debug s"Verifying and deleting namespace $namespace"
            curator.getData
                   .inBackground(verifyNamespace, childContext, executor)
                   .forPath(store.stateNamespacePath(namespace, context.version))
            childContext.future
        }

        if (futures.isEmpty) {
            log debug "No namespaces to verify and delete"
        }

        Future.sequence(futures) onComplete {
            case Success(_) => recycleObjects(context)
            case Failure(e) => context fail e
        }
    }

    private def recycleObjects(context: RecyclingContext): Unit = {
        log debug s"Collecting objects ${recyclingStep()}"
        val modelFutures = for (clazz <- store.classes) yield {
            val childContext = new ChildContext(context, clazz)
            curator.getChildren
                   .inBackground(collectModelObjects, childContext, executor)
                   .forPath(store.classPath(clazz))
            childContext.future
        }
        val stateFutures = for (host <- context.hosts; clazz <- store.classes) yield {
            val childContext = new ChildContext(context, (host, clazz))
            curator.getChildren
                   .inBackground(collectStateObjects, childContext, executor)
                   .forPath(store.stateClassPath(host, clazz, context.version))
            childContext.future
        }
        Future.sequence(modelFutures ++ stateFutures) onComplete {
            case Success(_) => verifyObjects(context)
            case Failure(e) => context fail e
        }
    }

    private def verifyObjects(context: RecyclingContext): Unit = {
        log debug s"Recycling object state for ${context.hosts.size} " +
                  s"namespaces ${recyclingStep()}"
        // Recycle the state objects that are not present in the model objects.
        val futures = for ((host, clazz) <- context.stateObjects.keys().asScala;
                           id <- context.stateObjects.get((host, clazz))
                           if !context.modelObjects.get(clazz).contains(id)) yield {
            log debug s"Recycling state object ${clazz.getSimpleName}/$id at " +
                      s"host $host"
            val path = store.stateObjectPath(host, clazz, id, context.version)
            val childContext = new ChildContext(context, path)

            curator.getData
                   .inBackground(verifyObject, childContext, executor)
                   .forPath(path)
            childContext.future
        }

        if (futures.isEmpty) {
            log debug "No objects to verify and delete"
        }

        Future.sequence(futures) onComplete {
            case Success(_) => context.success()
            case Failure(e) => context fail e
        }
    }

    private def recyclingStep(): String = {
        recyclingStepIndex += 1
        s"(step $recyclingStepIndex of $recyclingStepCount)"
    }
}