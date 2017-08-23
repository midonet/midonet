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

package org.midonet.cluster.services.topology_cache

import java.util.concurrent.{Executors, ScheduledFuture, TimeUnit}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

import com.codahale.metrics.MetricRegistry
import com.google.inject.Inject

import org.apache.curator.framework.CuratorFrameworkFactory
import org.apache.curator.framework.imps.CuratorFrameworkState
import org.apache.curator.retry.ExponentialBackoffRetry

import org.midonet.cluster.TopologyCacheLog
import org.midonet.cluster.cache._
import org.midonet.cluster.conf.ClusterConfig
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.endpoint.users.HttpByteBufferEndpointUser
import org.midonet.minion.MinionService.TargetNode
import org.midonet.minion.{Context, Minion, MinionService}
import org.midonet.util.logging.Logger

object TopologyCache {
    final val ServiceName = "topology-cache"
    final val InitialSnapshotDelaySeconds = 5
    final val SnapshotDelaySeconds = 30
}

/**
  * This service caches all topology objects in NSDB and keeps them updated
  * in the cache. It exposes the cache subservices (object and state for now)
  * as well as means to get a snapshot of the whole topology.
  */
@MinionService(name = "topology-cache", runsOn = TargetNode.CLUSTER)
class TopologyCache @Inject()(context: Context,
                              backend: MidonetBackend,
                              config: ClusterConfig,
                              metrics: MetricRegistry)
    extends Minion(context) with HttpByteBufferEndpointUser {

    import TopologyCache._

    private val log = Logger(TopologyCacheLog)

    private val paths = new ZoomPaths(config.backend)

    private val executor = Executors.newSingleThreadScheduledExecutor()

    implicit private val ec = ExecutionContext.fromExecutor(executor)

    private val curator = CuratorFrameworkFactory.newClient(
        config.backend.hosts,
        new ExponentialBackoffRetry(config.backend.retryMs.toInt,
                                    config.backend.maxRetries))

    private var objectCache: ObjectCache = _

    private var stateCache: StateCache = _

    private var localSnapshotProvider: TopologySnapshotProvider = _

    override def snapshotProvider = localSnapshotProvider

    private var scheduledSnapshot: ScheduledFuture[_] = _

    // From HttpByteBufferEndpointUser
    override val name = Option(ServiceName)
    override val endpointPath: String = ServiceName

    /** Whether the service is enabled on this Cluster node. */
    override def isEnabled: Boolean = config.topologyCache.isEnabled

    override def doStart(): Unit = {
        log.info("Starting NSDB topology cache")
        val timestamp = System.nanoTime()
        // NOTE: Subscription and snapshot requests MUST be handled on the
        // same thread.
        Future {
            try {
                curator.start()
                objectCache = new ObjectCache(curator, paths, metrics)
                stateCache = new StateCache(curator, paths, metrics,
                                            objectCache.observable())

                objectCache.startAsync().awaitRunning()
                stateCache.startAsync().awaitRunning()

                val elapsed = (System.nanoTime() - timestamp) / 1000000
                log.info("NSDB topology cache started in " +
                         s"$elapsed milliseconds")

                localSnapshotProvider = new TopologySnapshotProvider(
                    objectCache, stateCache, executor, log)

                // TODO: make the period between snapshots configurable
                scheduledSnapshot = executor.scheduleWithFixedDelay(
                    snapshotProvider.snapshot, InitialSnapshotDelaySeconds,
                    SnapshotDelaySeconds, TimeUnit.SECONDS)

                notifyStarted()
            } catch {
                case NonFatal(e) =>
                    log.warn("Failed to start topology cache", e)
                    curator.getState match {
                        case CuratorFrameworkState.STARTED =>
                            curator.close()
                        case _ =>
                    }
                    notifyFailed(e)
            }
        }
    }

    override def doStop(): Unit = {
        log.info("Stopping NSDB topology cache")
        val timestamp = System.nanoTime()

        try {
            scheduledSnapshot.cancel(true)
            stateCache.stopAsync().awaitTerminated()
            objectCache.stopAsync().awaitTerminated()
            val elapsed = (System.nanoTime() - timestamp) / 1000000
            log.info("NSDB topology cache stopped in " +
                     s"$elapsed milliseconds")
            notifyStopped()
        } catch {
            case NonFatal(e) =>
                log.warn("Failed to stop topology cache", e)
                notifyFailed(e)
        } finally {
            curator.getState match {
                case CuratorFrameworkState.STARTED =>
                    curator.close()
                case _ =>
            }
        }
    }
}

