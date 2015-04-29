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

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors.newSingleThreadExecutor
import java.util.concurrent.TimeUnit.SECONDS
import java.util.{Random, UUID}

import scala.collection.JavaConversions._
import scala.util.{Failure, Success, Try}

import com.google.inject.Inject
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.recipes.leader.{LeaderLatch, LeaderLatchListener}
import org.slf4j.LoggerFactory
import rx.schedulers.Schedulers
import rx.{Observer, Subscription}

import org.midonet.cluster.southbound.vtep.VtepDataClientFactory
import org.midonet.cluster.{ClusterMinion, ClusterNode}
import org.midonet.cluster.EntityIdSetEvent.Type._
import org.midonet.cluster.{DataClient, EntityIdSetEvent}
import org.midonet.midolman.state.Directory.DefaultTypedWatcher
import org.midonet.midolman.state.{StateAccessException, ZookeeperConnectionWatcher}
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
  * take over the management. */
class VxlanGatewayService @Inject()(nodeCtx: ClusterNode.Context,
                                    dataClient: DataClient,
                                    zkConnWatcher: ZookeeperConnectionWatcher,
                                    vtepDataClientFactory: VtepDataClientFactory,
                                    curator: CuratorFramework)
    extends ClusterMinion(nodeCtx) {

    private val log = LoggerFactory.getLogger(vxgwLog)
    private val LEADER_LATCH_PATH = "/midonet/vxgw/leader-latch"

    // Executor on which we schedule tasks to release the ZK event thread.
    private val executor = newSingleThreadExecutor(
        new NamedThreadFactory("vxgw-gateway-initializer")
    )

    // Latch to coordinate active-passive with other instances
    private val leaderLatch = new LeaderLatch(curator, LEADER_LATCH_PATH,
                                              nodeCtx.nodeId.toString)

    // Index of VxLAN Gateway managers for each neutron network with bindings
    // to hardware VTEPs.
    private val managers = new ConcurrentHashMap[UUID, VxlanGatewayManager]()

    // Watch bridge creations and deletions.
    private val networkUpdateMonitor =
        new ConcurrentHashMap[UUID, DefaultTypedWatcher]()

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

    // An observer that bootstraps a new VxLAN Gateway service whenever a
    // neutron network that has bindings to hardware VTEP(s) is created or
    // newly bound to its first VTEP.
    private val bridgesWatcher = new Observer[EntityIdSetEvent[UUID]] {
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
                bootstrapIfInVxlanGateway(t.value) // creation, or startup
            }
            // Deletions are managed by the individual network watcher
        }
    }

    // Reset the monitor so we start watching creations in networks.
    private val monitorReset: Runnable = makeRunnable {
        log.info("Watching for new VxLAN Gateways")
        val monitor = dataClient.bridgesGetUuidSetMonitor(zkConnWatcher)
        networkSub = monitor.getObservable
                            .observeOn(Schedulers.from(executor))
                            .subscribe(bridgesWatcher)
        monitor.notifyState()
    }


    /** Create a watcher for changes on the given network that will detect
      * when it becomes part of a VxLAN Gateway and bootstrap the management
      * process. */
    private def networkWatcher(id: UUID) = new DefaultTypedWatcher {
        override def pathDataChanged(path: String): Unit = {
            log.info(s"Network update notification")
            VxlanGateway.executor submit makeRunnable {
                bootstrapIfInVxlanGateway(id)
            }
        }
        override def pathDeleted(path: String): Unit = removeNetwork(id)
    }

    /** Load the given network and bootstrap the VxGW Manager if it's now
      * part of a VxLAN Gateway.  Otherwise just keep watching updates on the
      * network just in case new bindings to a VTEP are added. */
    private def bootstrapIfInVxlanGateway(id: UUID): Unit = {
        Try (
            // Load the bridge, setting up a watcher if it's the first time seen
            if (networkUpdateMonitor.contains(id)) {
                dataClient.bridgesGet(id)
            } else {
                val watcher = networkWatcher(id)
                networkUpdateMonitor.putIfAbsent(id, watcher)
                dataClient.bridgeGetAndWatch(id, watcher)
            }
        ) match {
            case Success(b) if b.getVxLanPortIds == null
                               || b.getVxLanPortIds.isEmpty =>
                log.info(s"Updated network ${b.getId} not part of a VxGW")
            case Success(b) =>
                initVxlanGatewayManager(b.getId)
            case Failure(e: StateAccessException) =>
                zkConnWatcher.handleError("Retry create VxLAN Gateway manager" +
                                          s" for network $id",
                                          makeRunnable {
                                              bootstrapIfInVxlanGateway(id)
                                          }, e)
            case Failure(e) =>
                log.error(s"Error starting VxGW monitor for network $id", e)
        }
    }

    /** React to the deletion of a network by cleaning any existing watcher as
      * well as the VxLAN Gateway manager, should one exist. */
    private def removeNetwork(id: UUID): Unit = {
        log.info(s"Network $id is deleted")
        networkUpdateMonitor.remove(id)
        val mgr = managers.remove(id)
        if (mgr != null) {
            log.info(s"VxLAN Gateway for network $id terminates")
            mgr.terminate()
        }
    }

    /** Create and run a new VxlanGatewayManager for the given network id */
    private def initVxlanGatewayManager(id: UUID) {
        log.info(s"Network $id is now part of a VxLAN Gateway")
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
            leaderLatch.removeListener(latchListener)
            becomePassive()
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

