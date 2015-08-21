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

import java.util.UUID
import java.util.concurrent.Executors.newSingleThreadExecutor
import java.util.concurrent.TimeUnit.SECONDS

import scala.collection.JavaConversions._

import com.codahale.metrics.MetricRegistry
import com.google.inject.Inject
import org.apache.curator.framework.CuratorFramework
import org.slf4j.LoggerFactory
import rx.schedulers.Schedulers
import rx.{Observable, Observer, Subscription}

import org.midonet.cluster._
import org.midonet.cluster.models.Topology
import org.midonet.cluster.models.Topology.Vtep
import org.midonet.cluster.services.{ClusterService, MidonetBackend, Minion}
import org.midonet.cluster.util.{UUIDUtil, selfHealingTypeObservable}
import org.midonet.midolman.state.ZookeeperConnectionWatcher
import org.midonet.southbound.vtep.OvsdbVtepDataClient
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
@ClusterService(name = "vxgw")
class VxlanGatewayService @Inject()(nodeCtx: ClusterNode.Context,
                                    dataClient: DataClient,
                                    backend: MidonetBackend,
                                    zkConnWatcher: ZookeeperConnectionWatcher,
                                    curator: CuratorFramework,
                                    metrics: MetricRegistry,
                                    conf: ClusterConfig)
    extends Minion(nodeCtx) {

    private val log = LoggerFactory.getLogger(vxgwLog)

    private val fpManager: FloodingProxyManager =
        new FloodingProxyManager(backend)
    private val vtepSyncers = new java.util.HashMap[UUID,Subscription]()

    // Executor on which we schedule tasks to release the ZK event thread.
    private val executor = newSingleThreadExecutor(
        new NamedThreadFactory("vxgw-gateway-initializer")
    )

    private val alertOverflow = makeAction0 {
        log.error("VTEP buffer overflow (>10000) - terminating service: ")
        notifyFailed(new IllegalStateException("Using more than 1000 VTEPs"))
        shutdown()
    }

    override def isEnabled = conf.vxgw.isEnabled

    private def watchVtep = new Observer[Vtep] {
        var vtepId: UUID = null
        override def onCompleted(): Unit = {}
        override def onError(e: Throwable): Unit = onCompleted()
        override def onNext(vtep: Vtep): Unit = {
            if (vtepId == null) {
                vtepId = UUIDUtil.fromProto(vtep.getId)
                startVtepSync(vtepId)
            }
        }
    }

    override def doStart(): Unit = {
        log.info("Starting service")
        // TODO: the FP manager should run with a latch
        fpManager.start()
        selfHealingTypeObservable[Topology.Vtep](backend.store)
            .onBackpressureBuffer(10000, alertOverflow)
            .observeOn(Schedulers.from(executor))
            .subscribe(new Observer[Observable[Topology.Vtep]] {
                override def onCompleted(): Unit = {}
                override def onError(e: Throwable): Unit = {}
                override def onNext(v: Observable[Vtep]): Unit = {
                    v.subscribe(watchVtep)
                }
            })
        notifyStarted()
    }

    override def doStop(): Unit = {
        try {
            shutdown()
            vtepSyncers.keySet().foreach(stopVtepSync)
            notifyStopped()
        } catch {
            case t: Throwable =>
                log.error("Failed to shutdown executor", t)
                Thread.currentThread().interrupt()
                notifyFailed(t)
        }
    }

    private def shutdown(): Unit = {
        executor.shutdown()
        if (!executor.awaitTermination(5, SECONDS)) {
            log.warn("Failed to stop executor orderly, insisting..")
            executor.shutdownNow()
        }
    }

    private def startVtepSync(vtepId: UUID): Unit = {
        val syncer = new VtepSynchronizer(vtepId, nodeCtx.nodeId,
                                          backend.store, backend.stateStore,
                                          dataClient, fpManager.herald,
                                          OvsdbVtepDataClient.apply)
        vtepSyncers.put(
            vtepId,
            backend.store.observable(classOf[Vtep], vtepId).subscribe(syncer)
        )
    }

    private def stopVtepSync(vtepId: UUID): Unit = {
        vtepSyncers.remove(vtepId) match {
            case null =>
            case syncer =>
                log.info("Stop managing VTEP $vtepId")
                syncer.unsubscribe()
        }
    }
}

