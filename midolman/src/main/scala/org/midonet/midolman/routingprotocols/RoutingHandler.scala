/*
 * Copyright 2014 Midokura SARL
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
package org.midonet.midolman.routingprotocols

import java.io.File
import java.net.InetAddress
import java.util.UUID

import scala.collection.mutable
import scala.concurrent.duration._

import akka.actor.{ActorRef, Stash}
import akka.pattern.pipe

import rx.{Observable, Subscription}

import org.midonet.cluster.Client
import org.midonet.cluster.client.BGPListBuilder
import org.midonet.cluster.data.{AdRoute, BGP, Route}
import org.midonet.midolman._
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.io.{UpcallDatapathConnectionManager, VirtualMachine}
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.midolman.routingprotocols.RoutingManagerActor.{BgpStatus, RoutingStorage}
import org.midonet.midolman.state.{StateAccessException, ZkConnectionAwareWatcher}
import org.midonet.midolman.topology.BgpMapper._
import org.midonet.midolman.topology.VirtualTopologyActor.PortRequest
import org.midonet.midolman.topology.devices.{Port, RouterPort}
import org.midonet.midolman.topology.routing.{Bgp, BgpRoute}
import org.midonet.midolman.topology.{BgpMapper, VirtualTopologyActor}
import org.midonet.odp.DpPort
import org.midonet.odp.ports.NetDevPort
import org.midonet.packets._
import org.midonet.quagga.ZebraProtocol.RIBType
import org.midonet.quagga._
import org.midonet.sdn.flows.FlowTagger
import org.midonet.util.concurrent.ReactiveActor
import org.midonet.util.concurrent.ReactiveActor.{OnCompleted, OnError}
import org.midonet.util.eventloop.SelectLoop
import org.midonet.util.functors.makeFunc1
import org.midonet.util.{AfUnix, UnixClock}

object RoutingHandler {
    val BGP_TCP_PORT: Short = 179

    /** A conventional value for Ip prefix of BGP pairs.
     *  172 is the MS byte value and 23 the second byte value.
     *  Last 2 LS bytes are available for assigning BGP pairs. */
    val BGP_IP_INT_PREFIX = 172 * (1<<24) + 23 * (1<<16)

    // BgpdProcess will notify via these messages
    case object BGPD_READY
    case object BGPD_DEAD
    case object FETCH_BGPD_STATUS
    case class BGPD_SHOW(cmd : String)

    case class PortActive(active: Boolean)

    private case class ZookeeperActive(active: Boolean)

    private case class NewBgpSession(bgp: BGP)

    private case class ModifyBgpSession(bgp: BGP)

    private case class RemoveBgpSession(bgpID: UUID)

    private case class AdvertiseRoute(route: AdRoute)

    private case class StopAdvertisingRoute(route: AdRoute)

    private case class AddPeerRoute(ribType: RIBType.Value, destination: IPv4Subnet,
                                    gateway: IPv4Addr, distance: Byte)

    private case class RemovePeerRoute(ribType: RIBType.Value,
                                       destination: IPv4Subnet,
                                       gateway: IPv4Addr)

    private case class DpPortCreateSuccess(port: DpPort, pid: Int)
    private case class DpPortDeleteSuccess(port: DpPort)
    private case class DpPortError(port: String, ex: Throwable)
}

class LazyZkConnectionMonitor(down: () => Unit,
                              up: () => Unit,
                              connWatcher: ZkConnectionAwareWatcher,
                              downEventDelay: FiniteDuration,
                              clock: UnixClock,
                              schedule: (FiniteDuration, Runnable) => Unit) {

    implicit def f2r(f: () => Unit): Runnable = new Runnable() { def run() = f() }

    private val lock = new Object

    connWatcher.scheduleOnReconnect(onReconnect _)
    connWatcher.scheduleOnDisconnect(onDisconnect _)

    private var lastDisconnection: Long = 0L
    private var connected = true

    private val NOW = 0 seconds

    private def disconnectedFor: Long = clock.time - lastDisconnection

    private def onDelayedDisconnect() = {
        lock.synchronized {
            if (!connected && (disconnectedFor >= downEventDelay.toMillis))
                down()
        }
    }

    private def onDisconnect() {
        schedule(NOW, () => lock.synchronized {
            lastDisconnection = clock.time
            connected = false
        })
        schedule(downEventDelay, onDelayedDisconnect _)
        connWatcher.scheduleOnDisconnect(onDisconnect _)
    }

    private def onReconnect() {
        schedule(NOW, () => lock.synchronized {
            connected = true
            up()
        })
        connWatcher.scheduleOnReconnect(onReconnect _)
    }
}

/**
 * The RoutingHandler manages the routing protocols for a single exterior
 * virtual router port that is local to this MidoNet daemon's physical host.
 * Currently, only BGP is supported, but we may add other routing protocols
 * in later versions. The RoutingHandler can manage multiple BGP sessions on
 * one router port. One RoutingHandler will launch at most one ZebraServer
 * (implementing the server-side of the Zebra Protocol). It will launch at
 * most one Vty connection for each routing protocol configured on the port.
 * Therefore, multiple sessions of a single routing protocol will share the
 * same protocol daemon (e.g. bgpd); and multiple protocols will share
 * the same ZebraServer. This is almost identical to the Quagga architecture
 * except that the RoutingHandler replaces the role of the Zebra daemon.
 *
 * Note that different exterior virtual router ports should have their routing
 * protocol sessions managed by different RoutingHandlers even if the router
 * ports happen to be on the save virtual router. The RoutingHandlers use
 * MidoNet's central cluster to aggregate routes. In the general case two
 * exterior virtual routers may be bound to physical interfaces on different
 * physical hosts, therefore MidoNet must anyway be able to use different
 * RoutingHandlers for different virtual ports of the same router. *
 */
class RoutingHandler(var rport: RouterPort, val bgpIdx: Int,
                     val flowInvalidator: SimulationBackChannel,
                     val dpState: DatapathState,
                     val upcallConnManager: UpcallDatapathConnectionManager,
                     val client: Client, val routingStorage: RoutingStorage,
                     val config: MidolmanConfig,
                     val connWatcher: ZkConnectionAwareWatcher,
                     val selectLoop: SelectLoop)
    extends ReactiveActor[AnyRef] with ActorLogWithoutPath with Stash {

    import RoutingHandler._

    import context.{dispatcher, system}

    override def logSource = s"org.midonet.routing.bgp.bgp-$bgpIdx"

    private final val BGP_NETDEV_PORT_NAME = s"mbgp$bgpIdx"
    private final val BGP_NETDEV_PORT_MIRROR_NAME = s"mbgp${bgpIdx}_m"

    private final val BGP_VTY_LOCAL_IP =
        new IPv4Subnet(IPv4Addr.fromInt(BGP_IP_INT_PREFIX + 1 + 4 * bgpIdx), 30)

    private final val BGP_VTY_MIRROR_IP =
        new IPv4Subnet(IPv4Addr.fromInt(BGP_IP_INT_PREFIX + 2 + 4 * bgpIdx), 30)

    private final val ZSERVE_API_SOCKET = s"/var/run/quagga/zserv$bgpIdx.api"
    private final val BGP_VTY_PORT = 2605 + bgpIdx

    private var zebra: ActorRef = null

    private var theBgpSession: Option[BGP] = None

    private var adRoutes = Set[AdRoute]()
    private val peerRoutes = mutable.Map[Route, UUID]()
    private var socketAddress: AfUnix.Address = null

    // At this moment we only support one bgpd process
    private var bgpd: BgpdProcess = null

    private val dpPorts = mutable.Map[String, NetDevPort]()

    /**
     * This actor can be in these phases:
     * NotStarted: bgp has not been started (no known bgp configs on the port)
     * Starting: waiting for bgpd and netdev port to come up
     * Started: zebra, bgpVty and bgpd are up
     * Stopping: we're in the process of stopping the bgpd
     * Disabled: bgp is temporarily disabled because the local port became
     *           inactive or we are disconnected from zookeeper
     * Disabling: bgp is being stopped on it's way to 'Disabled' state
     * Enabling: the condition that made the actor go into 'Disabling' has
     *           disappeared, but bgpd is in the processed of being stopped
     *           so the actor is waiting for that work to finish before
     *           enabling bgp again.
     *
     * The transitions are:
     * NotStarted -> Starting : when we learn about the first BGP config
     * Starting -> Started : when the bgpd has come up
     * Started -> Starting : if the bgpd crashes or needs a restart
     * Started -> Stopping : when all the bgp configs have been removed
     * Stopping -> NotStarted : when all the bgp configs have been removed
     * (any) -> Disabling
     * Disabled -> NotStarted: If NotStarted was the state before moving to Disabled
     * Disabled -> Starting
     * Disabled -> Enabling: Enabled msg arrived before BGPD_DEAD
     * Enabling -> Starting
     * Enabling -> NotStarted
     */
    object Phase extends Enumeration {
        type Phase = Value
        val NotStarted = Value("NotStarted")
        val Starting = Value("Starting")
        val Started = Value("Started")
        val Stopping = Value("Stopping")
        val Disabling = Value("Disabling")
        val Enabling = Value("Enabling")
        val Disabled = Value("Disabled")
    }

    import Phase._

    private var _phase = NotStarted
    def phase = _phase

    private def transition(next: Phase): Unit = {
        val receive = next match {
            case NotStarted => NotStartedState orElse AllStates
            case Starting => StartingState orElse AllStates
            case Started => StartedState orElse AllStates
            case Stopping => AllStates
            case Disabling => AllStates
            case Enabling => AllStates
            case Disabled => AllStates
        }
        theBgpSession foreach {
            bgp => routingStorage.setStatus(bgp.getId, next.toString)
        }
        _phase = next
        context.become(receive)
    }

    var zookeeperActive = true
    var portActive = true

    val lazyConnWatcher = new LazyZkConnectionMonitor(
            () => self ! ZookeeperActive(false),
            () => self ! ZookeeperActive(true),
            connWatcher,
            config.router.bgpZookeeperHoldtime seconds,
            UnixClock.DEFAULT,
            (d, r) => system.scheduler.scheduleOnce(d, r)(dispatcher))

    private lazy val bgpObservable =
        Observable.create(new BgpMapper(rport.id))
                  .map[AnyRef](makeFunc1(translateUpdate))
    private var bgpSubscription: Subscription = null

    override def preStart() {
        log.info("({}) Starting, port {}.", phase, rport.id)
        super.preStart()

        if (config.zookeeper.useNewStack) {
            bgpSubscription = bgpObservable.subscribe(this)
        } else {
            // Watch the BGP session information for this port.
            // In the future we may also watch for session configurations of
            // other routing protocols.
            client.subscribeBgp(rport.id, new BGPListBuilder {
                def addBGP(bgp: BGP) {
                    self ! NewBgpSession(bgp)
                }

                def updateBGP(bgp: BGP) {
                    self ! ModifyBgpSession(bgp)
                }

                def removeBGP(bgpID: UUID) {
                    self ! RemoveBgpSession(bgpID)
                }

                def addAdvertisedRoute(route: AdRoute) {
                    self ! AdvertiseRoute(route)
                }

                def removeAdvertisedRoute(route: AdRoute) {
                    self ! StopAdvertisingRoute(route)
                }
            })

            // Subscribe to the VTA for updates to the Port configuration.
            VirtualTopologyActor ! PortRequest(rport.id, update = true)
        }

        system.scheduler.schedule(2 seconds, 5 seconds, self, FETCH_BGPD_STATUS)

        log.debug("({}) Started", phase)
    }

    override def postStop() {
        super.postStop()
        if (bgpSubscription ne null) {
            bgpSubscription.unsubscribe()
            bgpSubscription = null
        }
        disable()
        log.debug("({}) Stopped", phase)
    }

    private val handler = new ZebraProtocolHandler {
        def addRoute(ribType: RIBType.Value, destination: IPv4Subnet,
                     gateway: IPv4Addr, distance: Byte) {
            self ! AddPeerRoute(ribType, destination, gateway, distance)
        }

        def removeRoute(ribType: RIBType.Value, destination: IPv4Subnet,
                        gateway: IPv4Addr) {
            self ! RemovePeerRoute(ribType, destination, gateway)
        }
    }

    val AllStates: Receive = {
        case NewBgpSession(bgp) =>
            log.info(s"($phase) New BGP session.")
            stash()

        case port: RouterPort =>
            rport = port

        case port: Port =>
            log.warn("({}) Cannot run BGP on anything but an exterior " +
                "virtual router port. We got: {}", phase, port)

        case ModifyBgpSession(bgp) =>
            //TODO(abel) case not implemented (yet)
            log.info("({}) Not implemented: ModifyBgpSession", phase)

        case RemoveBgpSession(bgpID) =>
            stash()

        case DpPortDeleteSuccess(netdevPort) =>
            log.info(s"($phase) Datapath port was deleted: ${netdevPort.getName} ")
            dpPorts.remove(netdevPort.getName)

        case DpPortError(port, ex) =>
            // Only log errors, do nothing
            log.error(s"($phase) Datapath port operation failed: $port", ex)

        case BGPD_DEAD =>
            log.debug("({}) BGPD_DEAD", phase)
            phase match {
                case Enabling =>
                    transition(Disabled)
                    enable()
                case Disabling =>
                    transition(Disabled)
                case Disabled =>
                case Stopping =>
                    transition(NotStarted)
                    unstashAll()
                case _ =>
                    log.error("({}) unexpected BGPD_DEAD message", phase)
            }

        case FETCH_BGPD_STATUS => // ignored

        case BGPD_SHOW(cmd) =>
            if (bgpd.isAlive)
                sender ! BgpStatus(bgpd.vty.showGeneric(cmd).toArray)
            else
                sender ! BgpStatus(Array[String]("BGP session is not ready"))

        case AdvertiseRoute(rt) =>
            log.info(s"($phase) Stashing Advertise: ${rt.getNwPrefix}/${rt.getPrefixLength}")
            stash()

        case StopAdvertisingRoute(rt) =>
            log.info(s"($phase) StopAdvertising: " +
                s"${rt.getNwPrefix}/${rt.getPrefixLength}")
            log.debug(s"($phase) StopAdvertising: stashing")
            stash()

        case PortActive(true) =>
            log.info("({}) Port became active", phase)
            portActive = true
            if (zookeeperActive)
                enable()

        case ZookeeperActive(true) if !zookeeperActive =>
            log.info("({}) Reconnected to Zookeeper", phase)
            zookeeperActive = true
            if (portActive)
                enable()

        case ZookeeperActive(true) if zookeeperActive => // ignored

        case PortActive(false) =>
            log.info("({}) Port became inactive", phase)
            portActive = false
            disable()

        case ZookeeperActive(false) =>
            log.info("({}) Disconnected from Zookeeper", phase)
            zookeeperActive = false
            disable()

        case OnCompleted =>
            log.info("({}) Port {} deleted", phase, rport.id)
            disable()

        case OnError(e) =>
            log.error("({}) Port {} error", phase, rport.id, e)
            disable()

        case m =>
            log.warn(s"($phase) $m: ignoring")
    }

    val StartingState: Receive = {
        case port: RouterPort if port.isExterior =>
            stash()

        case DpPortCreateSuccess(netdevPort, pid) =>
            log.info("({}) Datapath port was created: {}", phase, netdevPort.getName)
            netdevPortReady(netdevPort.asInstanceOf[NetDevPort], pid)

        case BGPD_READY =>
            log.debug("({}) BGPD_READY", phase)
            transition(Started)
            unstashAll()

            if (log.underlying.isDebugEnabled)
                bgpd.vty.setDebug(enabled = true)

            // BGP routes are added on:
            // - createSession method
            // - received from BGPListBuilder
            theBgpSession foreach {
                createSession(rport.portAddr.getAddress, _)
            }


        case AddPeerRoute(ribType, destination, gateway, distance) =>
            log.info(s"($phase) AddPeerRoute: $ribType, $destination, $gateway, $distance")
            log.debug(s"($phase) AddPeerRoute: stashing")
            stash()

        case RemovePeerRoute(ribType, destination, gateway) =>
            log.info(s"($phase) RemovePeerRoute: $ribType, $destination, $gateway")
            log.debug(s"($phase) RemovePeerRoute: stashing")
            stash()
    }

    val StartedState: Receive = {
        case NewBgpSession(bgp) => theBgpSession match {
            case Some(bgpSession) =>
                if (bgpSession.getId != bgp.getId) {
                    log.warn("({}) Ignoring BGP session {}, only one BGP "+
                        "session per port is supported", phase, bgp.getId)
                    stash()
                } else if (bgpSession.getLocalAS != bgp.getLocalAS) {
                    // The first bgp will enforce the AS
                    // for the other bgps. For a given routingHandler, we
                    // only have one bgpd process and all the BGP configs
                    // that specify different peers must have that field
                    // in common.
                    log.error(s"($phase) new sessions must have same AS")
                } else {
                    theBgpSession = Some(bgp)
                    bgpd.vty.addPeer(bgp.getLocalAS, bgp.getPeerAddr, bgp.getPeerAS,
                        config.bgpKeepAlive, config.bgpHoldTime, config.bgpConnectRetry)
                }

            case None =>
                log.error("({}) This shouldn't be the first bgp "+
                    "connection no other connections have been set "+
                    "up so far", phase)
        }

        case FETCH_BGPD_STATUS if bgpd ne null =>
            theBgpSession foreach {
                case bgp =>
                    log.debug("querying bgpd neighbour information")
                    try {
                        var status: String = "bgpd daemon is not ready"
                        if (bgpd.isAlive) {
                            status = bgpd.vty.showGeneric("nei").
                                filterNot(_.startsWith("bgpd#")).
                                filterNot(_.startsWith("show ip bgp nei")).
                                foldRight("")((a, b) => s"$a\n$b")
                        }
                        routingStorage.setStatus(bgp.getId, status)
                    } catch {
                        case e: Exception =>
                            log.warn("Failed to check bgpd status", e)
                    }
            }

        case RemoveBgpSession(bgpID) => theBgpSession match {
            case Some(bgp) if bgp.getId == bgpID =>
                log.info(s"($phase) Removing BGP session $bgpID")
                transition(Stopping)
                theBgpSession = None

                bgpd.vty.deletePeer(bgp.getLocalAS, bgp.getPeerAddr)
                RoutingWorkflow.routerPortToBgp.remove(bgp.getPortId)
                RoutingWorkflow.inputPortToBgp.remove(bgp.getQuaggaPortNumber)
                invalidateFlows(bgp)

                // If this is the last BGP for ths port, tear everything down.
                peerRoutes.keys foreach deleteRoute
                peerRoutes.clear()
                stopBGP()

            case Some(bgp) =>
                log.warn(s"($phase) RemoveBgpSession($bgpID) unknown id")

            case None =>
                log.warn(s"($phase) RemoveBgpSession($bgpID) unknown id")
        }

        case AdvertiseRoute(rt) => theBgpSession match {
            case Some(bgp) if bgp.getId == rt.getBgpId =>
                log.info(s"({$phase}) Advertise: ${rt.getNwPrefix}/${rt.getPrefixLength}")
                adRoutes += rt
                bgpd.vty.addNetwork(bgp.getLocalAS, rt.getNwPrefix.getHostAddress, rt.getPrefixLength)

            case Some(bgp) =>
                log.error(s"Ignoring advertised route, belongs to another BGP "+
                          s"(mine=${bgp.getId}, theirs=${rt.getBgpId})")

            case None =>
                log.error("Ignoring advertised route, no BGP known")
        }

        case StopAdvertisingRoute(rt) =>
            log.info(s"({$phase}) StopAdvertising: " +
                     s"${rt.getNwPrefix}/${rt.getPrefixLength}")
            adRoutes -= rt
            theBgpSession match {
                case Some(bgp) if bgp.getId == rt.getBgpId =>
                    bgpd.vty.deleteNetwork(bgp.getLocalAS,
                                           rt.getNwPrefix.getHostAddress,
                                           rt.getPrefixLength)
                case Some(bgp) =>
                    log.error(s"Ignoring stop-advertised route, belongs to another BGP "+
                        s"(mine=${bgp.getId}, theirs=${rt.getBgpId})")

                case None =>
                    log.error("Ignoring stop-advertised route, no BGP known")
            }

        case AddPeerRoute(ribType, destination, gateway, distance) if
                peerRoutes.size > config.router.maxBgpPeerRoutes =>

            log.warn(s"($phase) Max number of peer routes reached " +
                s"(${config.router.maxBgpPeerRoutes}), please check the " +
                "max_bgp_peer_routes config option.")

        case AddPeerRoute(ribType, destination, gateway, distance) =>
            log.info(s"($phase) AddPeerRoute: $ribType, $destination, $gateway, $distance")
            val route = new Route()
            route.setRouterId(rport.deviceId)
            route.setDstNetworkAddr(destination.getAddress.toString)
            route.setDstNetworkLength(destination.getPrefixLen)
            route.setNextHopGateway(gateway.toString)
            route.setNextHop(org.midonet.midolman.layer3.Route.NextHop.PORT)
            route.setNextHopPort(rport.id)
            route.setWeight(distance)
            route.setLearned(true)

            val routeId = routingStorage.addRoute(route)
            route.setId(routeId)
            peerRoutes.put(route, routeId)
            log.debug("({}) announcing we've added a peer route", phase)

        case RemovePeerRoute(ribType, destination, gateway) =>
            log.info(s"($phase) RemovePeerRoute: $ribType, $destination, $gateway")
            val route = new Route()
            route.setRouterId(rport.deviceId)
            route.setDstNetworkAddr(destination.getAddress.toString)
            route.setDstNetworkLength(destination.getPrefixLen)
            route.setNextHopGateway(gateway.toString)
            route.setNextHop(org.midonet.midolman.layer3.Route.NextHop.PORT)
            route.setNextHopPort(rport.id)
            route.setLearned(true)
            peerRoutes.remove(route) match {
                case Some(routeId) => deleteRoute(route)
                case None =>
            }
    }

    val NotStartedState: Receive = {

        case NewBgpSession(bgp) => theBgpSession match {
            case Some(bgp) =>
                log.error(s"($phase) This should be the first BGP connection but" +
                    " there were already other connections configured")

            case None =>
                log.info(s"($phase) New BGP session $bgp")
                theBgpSession = Some(bgp)
                adRoutes = adRoutes filter (bgp.getId == _.getBgpId)
                bgpd = new BgpdProcess(bgpIdx,
                                       BGP_VTY_LOCAL_IP, BGP_VTY_MIRROR_IP,
                                       rport.portAddr, rport.portMac, BGP_VTY_PORT)
                startBGP()
                transition(Starting)
        }

        case RemoveBgpSession(bgpID) =>
            // This probably shouldn't happen. A BGP config is being
            // removed, implying we knew about it - we shouldn't be
            // NotStarted...
            log.error("({}) RemoveBgpSession({}): unexpected", phase, bgpID)

        case AdvertiseRoute(rt) =>
            log.info(s"({$phase}) Advertise: ${rt.getNwPrefix}/${rt.getPrefixLength}")
            log.warn(s"($phase) Advertise: unexpected")

        case StopAdvertisingRoute(rt) =>
            log.info(s"({$phase}) StopAdvertising: " +
                s"${rt.getNwPrefix}/${rt.getPrefixLength}")
            log.warn(s"($phase) StopAdvertising: unexpected")
    }

    override def receive = NotStartedState

    def enable() {
        if (!portActive || !zookeeperActive) {
            log.warn(s"($phase) enable() invoked in incorrect state " +
                     s"zkActive:$zookeeperActive portActive:$portActive")
            return
        }
        phase match {
            case Disabled => theBgpSession match {
                case Some(bgp) =>
                    log.info("({}) Enabling BGP link: {}", phase, bgp.getId)
                    startBGP()
                    transition(Starting)
                case None =>
                    transition(NotStarted)
                    unstashAll()
            }
            case Disabling =>
                transition(Enabling)
            case _ =>
                log.warn("({}) enable() unexpected call", phase)
        }
    }

    def disable() {
        (phase, theBgpSession) match {
            case (Enabling, _) =>
                transition(Disabled)

            case (Disabled, _) =>
            case (Disabling, _) =>

            case (_, Some(bgp)) =>
                log.info("({}) Disabling BGP link: {}", phase, bgp.getId)
                transition(Disabling)

                RoutingWorkflow.routerPortToBgp.remove(bgp.getPortId)
                RoutingWorkflow.inputPortToBgp.remove(bgp.getQuaggaPortNumber)
                stopBGP()
                invalidateFlows(bgp)

                // NOTE(guillermo) the dataClient's write operations (such as
                // deleting a route) are synchronous. That implies that a) these
                // calls, because ZK is disconnected, will only return when
                // the session is restored or finally lost. The actor is
                // effectively 'suspended' for that period of time. and b)
                // these calls should be at the very end of this message
                // handler's code path, after the flow invalidation and
                // bgpd tear down.
                peerRoutes.keys foreach deleteRoute
                peerRoutes.clear()

            case _ =>
                transition(Disabled)
        }
    }

    def deleteRoute(route: Route) {
        try {
            routingStorage.removeRoute(route)
        } catch {
            case e: StateAccessException =>
                log.error(s"({$phase}) Exception", e)
                val retry = new Runnable() {
                    @Override
                    override def run() {
                        routingStorage.removeRoute(route)
                    }
                }

                connWatcher.handleError("BGP delete route: " + route.getId,
                                        retry, e)
        }
    }

    private def startBGP() {
        log.debug("({}) preparing environment for bgpd", phase)

        val socketFile = new File(ZSERVE_API_SOCKET)
        if (socketFile.exists()) {
            log.debug("({}) Deleting socket file at {}", phase, socketFile.getAbsolutePath)
            socketFile.delete()
        }

        log.debug("({}) Starting zebra server actor", phase)
        socketAddress = new AfUnix.Address(socketFile.getAbsolutePath)
        zebra = ZebraServer(socketAddress, handler, rport.portAddr.getAddress,
                            BGP_NETDEV_PORT_MIRROR_NAME, selectLoop)


        /* Bgp namespace configuration */
        bgpd.prepare()

        log.debug(s"($phase) Adding port to datapath: $BGP_NETDEV_PORT_NAME")
        createDpPort(BGP_NETDEV_PORT_NAME)

        log.debug(s"($phase) prepared environment for bgpd")
    }

    private def stopBGP() {
        log.debug("({}) stopping zebra server", phase)
        context.stop(zebra)

        log.debug("({}) stopping bgpd", phase)
        bgpd.stop()

        // Delete port from datapath
        dpPorts.get(BGP_NETDEV_PORT_NAME) foreach removeDpPort

        self ! BGPD_DEAD
    }

    private def netdevPortReady(newPort: NetDevPort, uplinkPid: Int) {
        log.debug("({}) datapath port is ready, starting bgpd", phase)

        dpPorts.put(newPort.getName, newPort)

        // The internal port is ready. Set up the flows
        if (theBgpSession.isEmpty) {
            log.warn("({}) No BGPs configured for this port: {}", phase, newPort)
            return
        }

        // Of all the possible BGPs configured for this port, we only consider
        // the very first one to create the BGPd process
        val bgp = theBgpSession.get
        bgp.setQuaggaPortNumber(newPort.getPortNo)
        bgp.setUplinkPid(uplinkPid)
        RoutingWorkflow.routerPortToBgp.put(bgp.getPortId, bgp)
        RoutingWorkflow.inputPortToBgp.put(bgp.getQuaggaPortNumber, bgp)
        invalidateFlows(bgp)

        if (bgpd.start())
            self ! BGPD_READY
    }

    private def createSession(localAddr: IPAddr, bgp: BGP) {
        log.debug(s"({$phase}) create bgp session to peer " +
                  s"AS ${bgp.getPeerAS} at ${bgp.getPeerAddr}")
        bgpd.vty.setAs(bgp.getLocalAS)
        bgpd.vty.setRouterId(bgp.getLocalAS, localAddr)
        bgpd.vty.addPeer(bgp.getLocalAS, bgp.getPeerAddr, bgp.getPeerAS,
            config.bgpKeepAlive, config.bgpHoldTime, config.bgpConnectRetry)

        for (adRoute <- adRoutes) {
            // If an adRoute is already configured in bgp, it will be
            // silently ignored
            bgpd.vty.addNetwork(bgp.getLocalAS, adRoute.getNwPrefix.getHostAddress,
                adRoute.getPrefixLength)
            log.debug(s"($phase) added advertised route: " +
                s"${adRoute.getData.nwPrefix}/${adRoute.getData.prefixLength}")
        }
    }

    private def createDpPort(port: String): Unit = {
        log.debug(s"Creating port $port")
        val f = upcallConnManager.createAndHookDpPort(dpState.datapath,
                                                      new NetDevPort(port),
                                                      VirtualMachine)
        f map { case (dpPort, pid) =>
            DpPortCreateSuccess(dpPort, pid)
        } recover { case e => DpPortError(port, e) } pipeTo self
    }

    private def removeDpPort(port: DpPort): Unit = {
        log.debug(s"Removing port ${port.getName}")
        upcallConnManager.deleteDpPort(dpState.datapath, port) map { _ =>
            DpPortDeleteSuccess(port)
        } recover { case e => DpPortError(port.getName, e) } pipeTo self
    }

    private def invalidateFlows(bgp: BGP): Unit = {
        val tag = FlowTagger.tagForDpPort(bgp.getQuaggaPortNumber)
        flowInvalidator.tell(tag)
    }

    private def translateUpdate(update: BgpUpdate): AnyRef = update match {
        case BgpPort(port) => port
        case BgpAdded(bgp) => NewBgpSession(translateBgp(bgp))
        case BgpUpdated(bgp) => ModifyBgpSession(translateBgp(bgp))
        case BgpRemoved(bgpId) => RemoveBgpSession(bgpId)
        case BgpRouteAdded(route) => AdvertiseRoute(translateRoute(route))
        case BgpRouteRemoved(route) => StopAdvertisingRoute(translateRoute(route))
    }

    private def translateBgp(bgp: Bgp): BGP = {
        val data = new BGP.Data
        data.localAS = bgp.localAs
        data.peerAS = bgp.peerAs
        data.peerAddr = bgp.peerAddress.asInstanceOf[IPv4Addr]
        data.portId = bgp.portId
        new BGP(bgp.id, data)
    }

    private def translateRoute(route: BgpRoute): AdRoute = {
        val data = new AdRoute.Data
        data.nwPrefix = InetAddress.getByAddress(route.subnet.getAddress
                                                      .asInstanceOf[IPAddr]
                                                      .toBytes)
        data.prefixLength = route.subnet.getPrefixLen.toByte
        data.bgpId = route.bgpId
        new AdRoute(route.id, data)
    }

}

