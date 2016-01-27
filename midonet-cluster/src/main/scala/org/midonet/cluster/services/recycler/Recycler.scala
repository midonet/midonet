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

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{TimeUnit, _}

import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal

import com.google.inject.Inject
import com.google.inject.name.Named
import com.typesafe.scalalogging.Logger

import org.slf4j.LoggerFactory

import rx.subjects.BehaviorSubject

import org.midonet.cluster.data.storage.ZookeeperObjectMapper
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.recycler.Recycler.MinimumInterval
import org.midonet.cluster.{ClusterConfig, RecyclerLog}
import org.midonet.minion.MinionService.TargetNode
import org.midonet.minion.{Context, Minion, MinionService}
import org.midonet.util.UnixClock
import org.midonet.util.functors.makeRunnable

object Recycler {

    protected[recycler] final val MinimumInterval = 1 minute
    protected[recycler] final val Data = Array[Byte](1)

}

/**
  * This service does garbage collection in NSDB.
  *
  * The use cases are multiple.  The prominent example are persistent nodes
  * created in ZooKeeper to host a subtree of ephemeral nodes for state (the
  * parent cannot be ephemeral as well as ZK wouldn't allow children on it).
  *
  * This Minion is responsible for doing the necessary cleanup.
  */
@MinionService(name = "recycler", runsOn = TargetNode.CLUSTER)
class Recycler @Inject()(context: Context, backend: MidonetBackend,
                         @Named("cluster-pool") executor: ScheduledExecutorService,
                         config: ClusterConfig)
    extends Minion(context) {

    private val log = Logger(LoggerFactory.getLogger(RecyclerLog))

    private val recyclingInterval = config.recycler.interval max MinimumInterval
    private val runInterval = (recyclingInterval / 10) max MinimumInterval

    private val store: ZookeeperObjectMapper = getStore
    private val curator = backend.curator
    private val clock = UnixClock()

    private val currentContext = new AtomicReference[RecyclingContext]()

    private val recycleTask = makeRunnable { recycle() }
    @volatile private var taskFuture: ScheduledFuture[_] = null

    private val tasksSubject = BehaviorSubject.create[Try[RecyclingContext]]

    /**
      * @return Returns the last recycling result.
      */
    def tasks = tasksSubject.asObservable()

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

        val context = currentContext.get()
        if (context ne null) {
            context.cancel()
            if (!context.awaitCompletion(config.recycler.shutdownTimeout.toMillis,
                                         TimeUnit.MILLISECONDS)) {
                log warn "Failed to stop the current recycling operation in " +
                         s"${config.recycler.shutdownTimeout.toMillis} " +
                         "milliseconds"
            }
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
        log info s"Scheduling recycling task in $delay"
        val future = taskFuture
        if (future ne null) {
            future.cancel(false)
        }
        taskFuture = executor.schedule(recycleTask, delay.toMinutes,
                                       TimeUnit.MINUTES)
    }

    /**
      * Begins an asynchronous recycling task. If successful, the recycling
      * tasks will consist of the the following steps, which are executed
      * asynchronously:
      * 1. Collects the current hosts to determine the state namespaces that are
      *    in use.
      * 2. Collects the current namespaces that were created before the start of
      *    the recycling operation.
      * 3. Deletes the orphan namespaces.
      * 4. Deletes the orphan state for deleted objects.
      */
    private def recycle(): Unit = {
        // Create a new private recycling context for this recycling task. Each
        // tasks uses its own recycling context, such that it is possible for
        // two tasks to overlap if one does not complete before initiating the
        // next one.
        val context = new RecyclingContext(config.recycler, curator, store,
                                           executor, clock, log,
                                           recyclingInterval)

        // Verify if a recycling operation is already running, in which case
        // skip the current recycling and reschedule the next.
        if (!currentContext.compareAndSet(null, context)) {
            log warn s"Recycling already running"
            return
        }

        log info "Collecting NSDB recycling information"

        try {
            context.recycle()

            log info "NSDB recycling report [version: " +
                     s"${context.nsdbVersion}] [namespaces: " +
                     s"${context.totalNamespaces} total " +
                     s"${context.deletedNamespaces} deleted " +
                     s"${context.skippedNamespaces} skipped] " +
                     s"[objects: ${context.totalObjects} total " +
                     s"${context.deletedObjects} deleted " +
                     s"${context.skippedObjects} skipped]"

            tasksSubject onNext Success(context)

        } catch {
            case e: RecyclingException if e.isError =>
                log.warn(e.getMessage, e)
                tasksSubject onNext Failure(e)
            case e: RecyclingException =>
                log.debug(e.getMessage)
                tasksSubject onNext Failure(e)
            case NonFatal(e) =>
                log.error("Unhandled exception during NSDB recycling", e)
                tasksSubject onNext Failure(e)
        } finally {
            currentContext.lazySet(null)
            schedule(runInterval)
        }
    }

}