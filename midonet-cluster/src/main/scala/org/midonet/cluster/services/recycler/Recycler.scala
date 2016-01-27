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

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

import com.google.inject.Inject
import com.google.inject.name.Named
import com.typesafe.scalalogging.Logger

import org.slf4j.LoggerFactory

import rx.Subscriber
import rx.subjects.BehaviorSubject

import org.midonet.cluster.data.storage.ZookeeperObjectMapper
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.recycler.Recycler.MinimumInterval
import org.midonet.cluster.{ClusterConfig, RecyclerLog}
import org.midonet.minion.MinionService.TargetNode
import org.midonet.minion.{Context, Minion, MinionService}
import org.midonet.util.UnixClock
import org.midonet.util.functors.{makeFunc1, makeRunnable}

object Recycler {

    final val MinimumInterval = 1 minute

    case class RecyclingException(context: RecyclingContext, inner: Throwable)
        extends Exception(s"Recycling failed: ${inner.getMessage}", inner)

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

    private implicit val ec = ExecutionContext.fromExecutor(executor)

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
            if (!context.awaitCompletion(config.threadPoolShutdownTimeoutMs,
                                         TimeUnit.MILLISECONDS)) {
                log warn "Failed to stop the current recycling operation in " +
                         s"${config.threadPoolShutdownTimeoutMs} milliseconds"
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
            log debug s"Recycling already running"
            return
        }

        log info "Collecting NSDB recycling information"

        RecyclerValidator(context) flatMap makeFunc1 {
            HostCollector(_)
        } flatMap makeFunc1 {
            NamespaceCollector(_)
        } flatMap makeFunc1 {
            NamespaceRecycler(_)
        } flatMap makeFunc1 {
            ObjectCollector(_)
        } flatMap makeFunc1 {
            ObjectRecycler(_)
        } subscribe new Subscriber[RecyclingContext]() {
            override def onNext(c: RecyclingContext): Unit = {
                log info "NSDB recycling report [version: " +
                         s"${c.version}] [namespaces: " +
                         s"${c.deletedNamespaces} deleted " +
                         s"${c.failedNamespaces} failed] " +
                         s"[objects: ${c.deletedObjects} deleted " +
                         s"${c.failedObjects} failed]"
                c.executed = true
            }

            override def onError(e: Throwable): Unit = {
                log.warn(s"NSDB recycling failed after ${context.duration} " +
                         "milliseconds", e)
                currentContext.compareAndSet(context, null)
                context.complete()
                tasksSubject onNext Failure(e)
                schedule(runInterval)
            }

            override def onCompleted(): Unit = {
                log info s"NSDB recycling completed in ${context.duration} " +
                         "milliseconds"
                currentContext.compareAndSet(context, null)
                context.complete()
                tasksSubject onNext Success(context)
                schedule(runInterval)
            }
        }
    }

}