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

import java.util.concurrent.{TimeUnit, _}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

import com.google.inject.Inject
import com.google.inject.name.Named
import com.typesafe.scalalogging.Logger

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.api.CuratorEvent
import org.apache.zookeeper.KeeperException
import org.apache.zookeeper.KeeperException.Code
import org.slf4j.LoggerFactory

import org.midonet.cluster.data.storage.ZookeeperObjectMapper
import org.midonet.cluster.services.recycler.Recycler.{MinimumInterval, RecyclingException, RecyclingContext}
import org.midonet.cluster.services.{ClusterService, MidonetBackend, Minion}
import org.midonet.cluster.{ClusterConfig, ClusterNode, recyclerLog}
import org.midonet.util.UnixClock
import org.midonet.util.functors.makeRunnable

object Recycler {

    final val MinimumInterval = 1 minute

    /**
      * Boiler-plate class for a recycling context. It wraps a [[Promise]]
      * and includes utility methods for its completion.
      */
    class RecyclingPromise[T] {
        private val complete = Promise[T]

        val future = complete.future

        /**
          * Completes this promise successfully with the given value.
          */
        def success(t: T): Unit = {
            complete trySuccess t
        }

        /**
          * Fails the current promise with the given throwable.
          */
        def fail(e: Throwable): Unit = {
            complete tryFailure e
        }

        /**
          * Fails the current promise with an exception corresponding to the
          * given [[CuratorEvent]].
          */
        def fail(event: CuratorEvent): Unit = {
            fail(KeeperException.create(Code.get(event.getResultCode),
                                        event.getPath))
        }

        /**
          * Completes the current promise with the specified future.
          */
        def completeWith(future: Future[T]): Unit = {
            complete.completeWith(future)
        }
    }

    /**
      * Contains the context for a recycling operation. An instance of this
      * class contains the state variable for a recycling operation, including
      * the start and finish timestamps, and the NSDB entries that have been
      * recycled (namespaces, objects, state paths).
      *
      * A recycling context extends a [[RecyclingPromise]], returning a future
      * which completes when the recycling operation has completed.
      */
    class RecyclingContext(val curator: CuratorFramework,
                           val store: ZookeeperObjectMapper,
                           val executor: ExecutorService,
                           val clock: UnixClock,
                           val log: Logger,
                           val interval: Duration) {

        implicit val ec = ExecutionContext.fromExecutor(executor)

        val start = clock.time
        var version = 0
        var timestamp = 0L
        var skipped = false

        var hosts: Set[String] = null
        var namespaces: Set[String] = null

        val deletedNamespaces = new AtomicInteger
        val failedNamespaces = new AtomicInteger

        val deletedObjects = new AtomicInteger
        val failedObjects = new AtomicInteger

        val deletedTables = new AtomicInteger
        val failedTables = new AtomicInteger

        val modelObjects = new ConcurrentHashMap[Class[_], Set[String]]()
        val stateObjects = new ConcurrentHashMap[(String, Class[_]), Seq[String]]()
        val tableObjects = new ConcurrentHashMap[Class[_], Set[String]]()

        @volatile private var promise = new RecyclingPromise[RecyclingContext]

        private val stepIndex = new AtomicInteger
        private final val stepCount = 11

        def future = promise.future

        /**
          * Completes the current recycling promise, and resets the promise
          * to a new instance.
          */
        def reset(): this.type = {
            success()
            promise = new RecyclingPromise[RecyclingContext]
            this
        }

        /**
          * Completes this recycling operation corresponding to this context
          * successfully.
          */
        def success(): Unit = {
            promise.success(this)
        }

        /**
          * Fails the recycling operation corresponding to this context.
          */
        def fail(e: Throwable): Unit = {
            e match {
                case _: RecyclingException => promise.fail(e)
                case _ => promise.fail(RecyclingException(this, e))
            }
        }

        /**
          * Fails the current promise with an exception corresponding to the
          * given [[CuratorEvent]].
          */
        def fail(event: CuratorEvent): Unit = {
            promise.fail(event)
        }

        /**
          * Completes the current promise with the specified future.
          */
        def completeWith(future: Future[_]): Unit = {
            promise.completeWith(future.map(_ => this))
        }

        /**
          * Completes this recycling operation as successful but skipped.
          */
        def skip(): Unit = {
            skipped = true
            success()
        }

        /**
          * Returns the current recycling step as string.
          */
        def step(): String = {
            s"(step ${stepIndex.incrementAndGet()} of $stepCount)"
        }

        /**
          * @return The duration of the current recycling operation.
          */
        def duration(): Long = {
            clock.time - start
        }
    }

    /**
      * The context for a recycling sub-task. It keeps a reference to the
      * parent [[RecyclingContext]], and takes an optional tag as argument
      * that can identify the child context.
      */
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

    private val recyclingInterval = config.recycler.interval max MinimumInterval
    private val runInterval = (recyclingInterval / 10) max MinimumInterval

    private val store: ZookeeperObjectMapper = getStore
    private val curator = backend.curator
    private val clock = UnixClock()
    private val running = new AtomicBoolean()

    private val recycleTask = makeRunnable { recycle() }
    @volatile private var taskFuture: ScheduledFuture[_] = null

    @volatile private var lastResult: Try[RecyclingContext] = null

    /**
      * @return Returns the last recycling result.
      */
    def last = lastResult

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
            notifyFailed(new IllegalArgumentException(
                "NSDB recycler requires ZOOM backend storage"))
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

    /**
      * Returns the current backend store as an instance of
      * [[ZookeeperObjectMapper]].
      */
    private def getStore: ZookeeperObjectMapper = {
        backend.store match {
            case zoom: ZookeeperObjectMapper => zoom
            case _ => null
        }
    }

    /**
      * Schedules a recycling task after a specified delay.
      */
    private def schedule(delay: Duration): Unit = {
        log debug s"Scheduling recycling task in $delay"
        val future = taskFuture
        if (future ne null) {
            future.cancel(false)
        }
        taskFuture = executor.schedule(recycleTask, delay.toMinutes,
                                       TimeUnit.MINUTES)
    }

    /**
      * Begins an asynchronous recycling task. If successful, the recycling
      * tasks will consist of the the follwing steps, which are executed
      * asynchronously
      */
    private def recycle(): Unit = {
        // Verify if a recycling operation is already running, in which case
        // skip the current recycling and reschedule the text.
        if (!running.compareAndSet(false, true)) {
            log debug s"Recycling already running"
            return
        }

        log info "Collecting NSDB recycling information"

        // Create a new private recycling context for this recycling task. Each
        // tasks uses its own recycling context, such that it is possible for
        // two tasks to overlap if one does not complete before initiating the
        // next one.
        val context = new RecyclingContext(curator, store, executor, clock, log,
                                           recyclingInterval)

        RecyclerValidator(context) flatMap { c =>
            if (c.skipped) c.future else HostCollector(c.reset())
        } flatMap { c =>
            if (c.skipped) c.future else NamespaceCollector(c.reset())
        } flatMap { c =>
            if (c.skipped) c.future else NamespaceRecycler(c.reset())
        } flatMap { c =>
            if (c.skipped) c.future else ObjectRecycler(c.reset())
        } flatMap { c =>
            if (c.skipped) c.future else TableRecycler(c.reset())
        } onComplete { result =>
            result match {
                case Success(c) if c.skipped =>
                    log info s"NSDB recycling completed in ${c.duration()} " +
                             "milliseconds but was skipped"
                    lastResult = Success(c)
                case Success(c) =>
                    log info "NSDB recycling completed successfully in " +
                             s"${c.duration()} milliseconds [version: " +
                             s"${c.version}] [namespaces: " +
                             s"${c.deletedNamespaces} deleted " +
                             s"${c.failedNamespaces} failed] " +
                             s"[objects: ${c.deletedObjects} deleted " +
                             s"${c.failedObjects} failed]" +
                             s"[tables: ${c.deletedTables} deleted " +
                             s"${c.failedTables} failed]"
                    lastResult = Success(c)
                case Failure(e: RecyclingException) =>
                    log.warn("NSDB recycling failed after " +
                              s"${e.context.duration()} milliseconds", e)
                    lastResult = Failure(e)
                case Failure(e) =>
                    log.warn("NSDB recycling failed", e)
                    lastResult = Failure(e)
            }
            running.compareAndSet(true, false)
        }
    }

}