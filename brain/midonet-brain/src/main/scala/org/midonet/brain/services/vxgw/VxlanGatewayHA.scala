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

package org.midonet.brain.services.vxgw

import java.util.concurrent.Executors.newSingleThreadExecutor
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.{ConcurrentHashMap, ThreadFactory}
import java.util.{Random, UUID}

import scala.collection.JavaConversions._

import com.google.inject.Inject
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.recipes.leader.{LeaderLatchListener, LeaderLatch}
import org.slf4j.LoggerFactory
import rx.{Subscription, Observer}
import rx.schedulers.Schedulers

import org.midonet.brain.ClusterNode
import org.midonet.brain.southbound.vtep.VtepDataClientFactory
import org.midonet.cluster.EntityIdSetEvent.Type
import org.midonet.cluster.EntityIdSetEvent.Type._
import org.midonet.cluster.{DataClient, EntityIdSetEvent, EntityIdSetMonitor}
import org.midonet.midolman.state.{StateAccessException, ZookeeperConnectionWatcher}
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
  * take over the management. */
class VxlanGatewayHA @Inject()(nodeCtx: ClusterNode.Context,
                               dataClient: DataClient,
                               zkConnWatcher: ZookeeperConnectionWatcher,
                               vtepDataClientFactory: VtepDataClientFactory,
                               curator: CuratorFramework)
    extends VxLanGatewayServiceBase(nodeCtx) {

    private val log = LoggerFactory.getLogger(vxgwLog)
    private val LEADER_LATCH_PATH = "/midonet/vxgw/leader-latch"

    // Index of VxLAN Gateway managers for each neutron network with bindings
    // to hardware VTEPs.
    private val managers = new ConcurrentHashMap[UUID, VxlanGatewayManager]()

    // Executor on which we schedule tasks to release the ZK event thread.
    private val executor = newSingleThreadExecutor(new ThreadFactory {
        override def newThread(r: Runnable): Thread = {
            val t = new Thread(r)
            t.setName("vxgw-gateway-initializer")
            t
        }
    })

    // A monitor that notifies on network creation, which we will examine and
    // decide whether we need a new VxLAN Gateway manager.
    private var networkSub: Subscription = _

    // Watch hosts and tunnel zones in order to discover flooding proxies
    private val hostsState = new HostStatePublisher(dataClient, zkConnWatcher)
    private val tzState = new TunnelZoneStatePublisher(dataClient,
                                                       zkConnWatcher,
                                                       hostsState,
                                                       new Random())

    // VTEP controllers
    private val vteps = new VtepPool(nodeCtx.nodeId, dataClient, zkConnWatcher,
                                     tzState, vtepDataClientFactory)

    // Latch to coordinate active-passive with other instances
    private val leaderLatch = new LeaderLatch(curator, LEADER_LATCH_PATH,
                                              nodeCtx.nodeId.toString)

    // Reset the monitor so we start watching creations in networks.
    private val monitorReset: Runnable = new Runnable {
        override def run(): Unit = {
            log.info("Watching for new VxLAN Gateways")
            val monitor = dataClient.bridgesGetUuidSetMonitor(zkConnWatcher)
            networkSub = monitor.getObservable
                                .observeOn(Schedulers.from(executor))
                                .subscribe(bootstrapVxlanGateway)
            monitor.notifyState()
        }
    }

    // An observer that bootstraps a new VxLAN Gateway service whenever a
    // neutron network that has bindings to hardware VTEP(s) is created or
    // newly bound to its first VTEP.
    private val bootstrapVxlanGateway = new Observer[EntityIdSetEvent[UUID]] {
        override def onCompleted(): Unit = {
            log.warn("Unexpected: networks watcher completed, this indicates " +
                     "that all networks were deleted!")
        }
        override def onError(e: Throwable): Unit = {
            log.warn("Error on network stream, retry subscription..", e)
            executor submit monitorReset
        }
        override def onNext(t: EntityIdSetEvent[UUID]): Unit = {
            if (t.`type` == CREATE || t.`type` == STATE) {
                checkNetwork(t.value)
            }
        }
    }

    /** Verifies whether the network with the given UUID is included in a VxLAN
      * Gateway, and in that case start a new VxlanGatewayManager to well..
      * manage it. */
    private def checkNetwork(id: UUID): Unit = {
        try {
            val network = dataClient.bridgesGet(id)
            if (network.getVxLanPortIds.isEmpty) {
                log.debug(s"Network $id is not bound to VTEPs, ignoring")
                return
            }
        } catch {
            case e: StateAccessException =>
                zkConnWatcher.handleError("Retry VxLAN Gateway manager" +
                                          s" bootstrap for network $id",
                                          makeRunnable { checkNetwork(id) }, e)
            case _: Throwable =>
                log.error(s"Error starting VxGW monitor for network $id")
        }

        val nw = new VxlanGatewayManager(id, dataClient, vteps,
                                         tzState, zkConnWatcher,
                                         () => managers.remove(id))
        if (managers.putIfAbsent(id, nw) == null) {
            log.debug(s"Start VxLAN Gateway manager for network $id")
            nw.start()
        } else {
            log.debug(s"VxLAN Gateway manager for network $id already exists")
        }

    }

    // A callback when a change in this node's leadership status happens
    private val latchListener = new LeaderLatchListener {
        override def isLeader(): Unit = {
            log.info("I am the VxLAN Gateway leader! \\o/")
            executor submit monitorReset
        }
        override def notLeader(): Unit = {
            log.info("I am no longer VxLAN Gateway leader, going passive")
            becomePassive()
        }
    }

    override def doStart(): Unit = {
        log.info("Starting service")
        leaderLatch.addListener(latchListener)
        leaderLatch.start()
        notifyStarted()
    }

    /** Makes the node become passive but not stop. */
    private def becomePassive(): Unit = {
        log.info("Node suspends VxLAN Gateway management")
        if (networkSub != null) {
            networkSub.unsubscribe()
        }
        managers.values().foreach {_.terminate() }
    }

    override def doStop(): Unit = {
        executor.shutdown()
        try {
            if (!executor.awaitTermination(5, SECONDS)) {
                log.warn("Failed to stop network monitor orderly, insisting..")
                executor.shutdownNow()
            }
            becomePassive()
            leaderLatch.removeListener(latchListener)
            leaderLatch.close()
            notifyStopped()
        } catch {
            case t: Throwable =>
                log.error("Failed to shutdown executor", t)
                Thread.currentThread().interrupt()
                notifyFailed(t)
        }
    }

}
