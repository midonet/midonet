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

package org.midonet.cluster.services.vxgw

import java.util
import java.util.UUID
import java.util.concurrent.Executors.newSingleThreadExecutor

import scala.collection.JavaConversions._
import scala.concurrent.duration._

import com.codahale.metrics.MetricRegistry
import com.google.inject.Inject

import org.apache.curator.framework.recipes.leader.{LeaderLatch, LeaderLatchListener}
import org.slf4j.LoggerFactory

import rx.schedulers.Schedulers
import rx.{Observable, Observer, Subscriber}

import org.midonet.cluster._
import org.midonet.cluster.backend.zookeeper.ZookeeperConnectionWatcher
import org.midonet.cluster.models.Topology
import org.midonet.cluster.models.Topology.Vtep
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.MidonetBackend.VtepVxgwManager
import org.midonet.cluster.storage.MidonetBackendConfig
import org.midonet.cluster.util.{Snatcher, UUIDUtil}
import org.midonet.minion.MinionService.TargetNode
import org.midonet.minion.{Context, Minion, MinionService}
import org.midonet.packets.IPv4Addr
import org.midonet.southbound.vtep.{OvsdbVtepConnectionProvider, OvsdbVtepDataClient}
import org.midonet.util.concurrent.NamedThreadFactory
import org.midonet.util.functors._

/** An implementation of the VxLAN Gateway Service that supports high
  * availability on the hardware VTEPs. The service allows making bindings
  * from ports/vlan pairs in different hardware VTEPs forming a single Logical
  * Switch, and synces Mac-Port pairs across both MidoNet and all the VTEPs that
  * participate in the Logical Switch.
  *
  * Instances of the service coordinate in an active-passive configuration using
  * Zookeeper. Only one node will be elected as leader and perform all VxLAN
  * Gateway management. When a node loses leadership (voluntarily, or because
  * of a failure, partition, etc.) a different instance will be elected and
  * take over the management.
  */
@MinionService(name = "vxgw", runsOn = TargetNode.CLUSTER)
class VxlanGatewayService @Inject()(
        nodeCtx: Context,
        dataClient: DataClient,
        backend: MidonetBackend,
        backendCfg: MidonetBackendConfig,
        ovsdbCnxnProvider: OvsdbVtepConnectionProvider,
        zkConnWatcher: ZookeeperConnectionWatcher,
        metrics: MetricRegistry,
        conf: ClusterConfig) extends Minion(nodeCtx) {

    private val log = LoggerFactory.getLogger(VxgwLog)

    private var fpManager: FloodingProxyManager = _
    private val vtepSyncers = new util.HashMap[UUID, Subscriber[Vtep]]()
    private val knownVteps = new util.HashMap[UUID, Snatcher[Vtep]]

    // Executor on which we schedule tasks to release the ZK event thread.
    private val executor = newSingleThreadExecutor(
        new NamedThreadFactory("vxgw-gateway-initializer", isDaemon = true)
    )

    private val maxVtepBuffer = 10000
    private val alertOverflow = makeAction0 {
        log.error(s"VTEP buffer overflow ($maxVtepBuffer) - terminating")
        notifyFailed(new IllegalStateException(s"Using > $maxVtepBuffer VTEPs"))
        shutdown()
    }

    private val fpLatch = new LeaderLatch(backend.curator, backendCfg.rootKey +
                                          "/vxgw/fp-latch")
    private val fpLatchListener = new FloodingProxyLatchListener
    fpLatch.addListener(fpLatchListener)

    private def watchVtep = new Observer[Vtep] {
        var vtepId: UUID = null
        override def onCompleted(): Unit = {
            knownVteps.remove(vtepId) match {
                case null =>
                case snatcher => snatcher.giveUp()
            }
        }
        override def onError(t: Throwable): Unit = {
            log.warn(s"VTEP $vtepId error emitted on update stream, this will " +
                     "stop the synchronization process for this VTEP.")
            onCompleted()
        }
        override def onNext(vtep: Vtep): Unit = {
            if (vtepId == null) {
                vtepId = UUIDUtil.fromProto(vtep.getId)
                val snatcher = Snatcher[Vtep](vtepId, nodeCtx.nodeId,
                                              backend.stateStore,
                                              VtepVxgwManager,
                                              () => startVtepSync(vtepId),
                                              () => stopVtepSync(vtepId))
                knownVteps.put(vtepId, snatcher)
            }
        }
    }

    /** Whether the service is enabled on this Cluster node. */
    override def isEnabled: Boolean = conf.vxgw.isEnabled

    private def startVtepSync(vtepId: UUID): Unit = {

        val loadOvdsbCnxn = (mgmtIp: IPv4Addr, mgmtPort: Int) => {
            OvsdbVtepDataClient.apply (
                ovsdbCnxnProvider.get(mgmtIp, mgmtPort, 10 seconds, 50)
            )
        }
        val syncer = new VtepSynchronizer(vtepId, backend.store,
                                          backend.stateStore,
                                          backend.stateTableStore,
                                          fpManager.herald, loadOvdsbCnxn)
        vtepSyncers.put(vtepId, syncer)
        backend.store.observable(classOf[Vtep], vtepId).subscribe(syncer)
    }

    private def stopVtepSync(vtepId: UUID): Unit = {
        vtepSyncers.remove(vtepId) match {
            case null =>
            case syncer =>
                log.info(s"Stop managing VTEP $vtepId")
                syncer.onCompleted()
                syncer.unsubscribe()
        }
    }

    override def doStart(): Unit = {
        log.info("Starting VxLAN Gateway service")
        backend.store.observable(classOf[Topology.Vtep])
            .onBackpressureBuffer(maxVtepBuffer, alertOverflow)
            .observeOn(Schedulers.from(executor))
            .subscribe(new Observer[Observable[Topology.Vtep]] {
                    override def onCompleted(): Unit = {
                        log.warn("VTEP stream is closed.")
                    }
                    override def onError(e: Throwable): Unit = {
                        log.warn("VTEP stream fails", e)
                    }
                    override def onNext(v: Observable[Vtep]): Unit = {
                        v.subscribe(watchVtep)
                    }
        })
        fpLatch.start()
        log.info("Started VxLAN Gateway service")
        notifyStarted()
    }

    override def doStop(): Unit = {
        try {
            shutdown()
            log.debug("Stop VTEP sync..")
            vtepSyncers.keySet().foreach(stopVtepSync)
            log.debug("Release VTEPs so other nodes can manage them..")
            knownVteps.values.foreach { _.giveUp() }
            knownVteps.clear()
            log.debug("Service is now shut down")
            notifyStopped()
        } catch {
            case t: Throwable =>
                log.error("Failed to shutdown executor", t)
                Thread.currentThread().interrupt()
                notifyFailed(t)
        }
    }

    private def shutdown(): Unit = {
        fpLatch.close()
        fpLatch.removeListener(fpLatchListener)
        executor.shutdown()
        if (!executor.awaitTermination(5, SECONDS)) {
            log.warn("Failed to stop executor orderly, insisting..")
            executor.shutdownNow()
        }
    }

    private class FloodingProxyLatchListener extends LeaderLatchListener {
        override def isLeader(): Unit = {
            fpManager = new FloodingProxyManager(backend)
            fpManager.start()
        }
        override def notLeader(): Unit = {
            val oldFpManager = fpManager
            fpManager = null
            oldFpManager.stop()
        }
    }
}

