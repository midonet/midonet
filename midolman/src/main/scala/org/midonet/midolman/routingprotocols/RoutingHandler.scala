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
import java.util.UUID

import scala.collection.mutable
import scala.concurrent.duration._

import akka.actor.{ActorRef, Stash}
import akka.pattern.pipe

import rx.{Subscription, Observable}

import org.midonet.cluster.client.BGPListBuilder
import org.midonet.cluster.data.{AdRoute, Route}
import org.midonet.cluster.{Client, DataClient}
import org.midonet.cluster.data
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.flows.FlowInvalidator
import org.midonet.midolman.io.{UpcallDatapathConnectionManager, VirtualMachine}
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.midolman.routingprotocols.RoutingManagerActor.BgpStatus
import org.midonet.midolman.state.{StateAccessException, ZkConnectionAwareWatcher}
import org.midonet.midolman.topology.BGPMapper._
import org.midonet.midolman.topology.{BGPMapper, VirtualTopologyActor}
import org.midonet.midolman.topology.VirtualTopologyActor.PortRequest
import org.midonet.midolman._
import org.midonet.midolman.topology.devices.{Port, RouterPort}
import org.midonet.netlink.AfUnix
import org.midonet.odp.ports.NetDevPort
import org.midonet.odp.{Datapath, DpPort}
import org.midonet.packets._
import org.midonet.quagga.ZebraProtocol.RIBType
import org.midonet.quagga._
import org.midonet.sdn.flows.FlowTagger
import org.midonet.util.concurrent.ReactiveActor
import org.midonet.util.eventloop.SelectLoop

object RoutingHandler {

    val BGP_TCP_PORT: Short = 179

    /** A conventional value for Ip prefix of BGP pairs.
     *  172 is the MS byte value and 23 the second byte value.
     *  Last 2 LS bytes are available for assigning BGP pairs. */
    val BGP_IP_INT_PREFIX = 172 * (1<<24) + 23 * (1<<16)

    // BgpdProcess will notify via these messages
    case object BgpdReady
    case object BgpdDead
    case object FetchBgpStatus
    case class BgpdShow(cmd : String)

    case class PortActive(active: Boolean)

    private case class ZookeeperActive(active: Boolean)

    private case class AddPeerRoute(ribType: RIBType.Value, destination: IPv4Subnet,
                                    gateway: IPv4Addr, distance: Byte)

    private case class RemovePeerRoute(ribType: RIBType.Value,
                                       destination: IPv4Subnet,
                                       gateway: IPv4Addr)

    private case class DpPortCreateSuccess(port: DpPort, pid: Int)
    private case class DpPortDeleteSuccess(port: DpPort)
    private case class DpPortError(port: String, ex: Throwable)

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
                     val datapath: Datapath,
                     val flowInvalidator: FlowInvalidator,
                     val dpState: DatapathState,
                     val upcallConnManager: UpcallDatapathConnectionManager,
                     val client: Client, val dataClient: DataClient,
                     val config: MidolmanConfig,
                     val connWatcher: ZkConnectionAwareWatcher,
                     val selectLoop: SelectLoop)
    extends ReactiveActor[BGPUpdate] with ActorLogWithoutPath with Stash
    with FlowTranslator {

    import RoutingHandler._

    override protected implicit val system = context.system

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
    private var bgpVty: BgpConnection = null

    private var theBgpSession: Option[BGP] = None

    // TODO
    //private var adRoutes = Set[AdRoute]()

    //private val bgps = mutable.Map[UUID, BGP]()
    private val bgpRoutes = mutable.Set[BGPRoute]()
    private val peerRoutes = mutable.Map[Route, UUID]()
    private var socketAddress: AfUnix.Address = null

    // At this moment we only support one bgpd process
    private var bgpdProcess: BgpdProcess = null

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
            bgp => dataClient.bgpSetStatus(bgp.id, next.toString)
        }
        _phase = next
        context.become(receive)
    }

    var zookeeperActive = true
    var portActive = true

    private var bgpMapperSubscription: Subscription = null

    override def preStart() {
        log.info("({}) Starting, port {}.", phase, rport.id)

        super.preStart()

        if (rport == null) {
            log.error("({}) port is null", phase)
            return
        }

        if (client == null) {
            log.error("({}) client is null", phase)
            return
        }

        if (dataClient == null) {
            log.error("({}) dataClient is null", phase)
            return
        }

        if (config.zookeeper.useNewStack) {
            // If the new cluster stack is enabled, subscribe to an observable
            // on the BGP mapper that emits BGP updates.
            bgpMapperSubscription = Observable.create(new BGPMapper(rport.id))
                                              .subscribe(this)
        } else {
            // Watch the BGP session information for this port.
            // In the future we may also watch for session configurations of
            // other routing protocols.
            client.subscribeBgp(rport.id, new BGPListBuilder {
                def addBGP(bgp: data.BGP) {
                    self ! BGPAdded(BGP.from(bgp))
                }

                def updateBGP(bgp: data.BGP) {
                    self ! BGPUpdated(BGP.from(bgp))
                }

                def removeBGP(bgpID: UUID) {
                    self ! BGPRemoved(bgpID)
                }

                def addAdvertisedRoute(route: AdRoute) {
                    self ! BGPRouteAdded(BGPRoute.from(route))
                }

                def removeAdvertisedRoute(route: AdRoute) {
                    self ! BGPRouteRemoved(BGPRoute.from(route))
                }
            })

            connWatcher.scheduleOnDisconnect(new Runnable() {
                override def run() {
                    self ! ZookeeperActive(active = false)
                    connWatcher.scheduleOnDisconnect(this)
                }
            })

            connWatcher.scheduleOnReconnect(new Runnable() {
                override def run() {
                    self ! ZookeeperActive(active = true)
                    connWatcher.scheduleOnReconnect(this)
                }
            })

            // Subscribe to the VTA for updates to the Port configuration.
            VirtualTopologyActor ! PortRequest(rport.id, update = true)
        }

        system.scheduler.schedule(2 seconds, 5 seconds, self, FetchBgpStatus)

        log.debug("({}) Started", phase)
    }

    override def postStop() {
        super.postStop()
        if (bgpMapperSubscription ne null) {
            bgpMapperSubscription.unsubscribe()
            bgpMapperSubscription = null
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

        case port: RouterPort if port.isExterior =>
            rport = port

        case port: Port =>
            log.error("({}) Cannot run BGP on anything but an exterior " +
                      "virtual router port. We got {}", phase, port)

        case BGPPort(port) if port.isExterior =>
            if (phase == Starting) stash()
            else rport = port

        case BGPPort(port) =>
            log.error("({}) Cannot run BGP on anything but an exterior " +
                      "virtual router port. We got {}", phase, port)

        case BGPAdded(bgp) =>
            log.info(s"($phase) New BGP session.")
            stash()

        case BGPUpdated(bgp) =>
            //TODO(abel) case not implemented (yet)
            log.info("({}) Not implemented: ModifyBgpSession", phase)

        case BGPRemoved(bgpID) =>
            stash()

        case DpPortDeleteSuccess(netdevPort) =>
            log.info("({}) Datapath port was deleted: {}", phase,
                     netdevPort.getName)
            dpPorts.remove(netdevPort.getName)

        case DpPortError(port, ex) =>
            // Only log errors, do nothing
            log.error("({}) Datapath port operation failed: {}", phase, port, ex)

        case BgpdDead =>
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

        case FetchBgpStatus => // ignored

        case BgpdShow(cmd) =>
            if (bgpVty != null)
                sender ! BgpStatus(bgpVty.showGeneric(cmd).toArray)
            else
                sender ! BgpStatus(Array[String]("BGP session is not ready"))

        case BGPRouteAdded(route) =>
            log.info("({}) Add BGP route: {}", phase, route.subnet)
            stash()

        case BGPRouteRemoved(route) =>
            log.info("({}) Remove BGP route: {}", phase, route.subnet)
            stash()

        case PortActive(true) =>
            log.info("({}) Port became active", phase)
            portActive = true
            if (zookeeperActive)
                enable()

        case ZookeeperActive(true) =>
            log.info("({}) Reconnected to Zookeeper", phase)
            zookeeperActive = true
            if (portActive)
                enable()

        case PortActive(false) =>
            log.info("({}) Port became inactive", phase)
            portActive = false
            disable()

        case ZookeeperActive(false) =>
            log.info("({}) Disconnected from Zookeeper", phase)
            zookeeperActive = false
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

        case BgpdReady =>
            log.debug("({}) BGPD_READY", phase)
            transition(Started)
            unstashAll()

            bgpVty = new BgpVtyConnection(
                addr = BGP_VTY_MIRROR_IP.getAddress.toString,
                port = BGP_VTY_PORT,
                password = "zebra_password",
                keepAliveTime = config.bgpKeepAlive,
                holdTime = config.bgpHoldTime,
                connectRetryTime = config.bgpConnectRetry)

            bgpVty.setLogFile("/var/log/quagga/bgpd." + BGP_VTY_PORT + ".log")
            if (log.underlying.isDebugEnabled)
                bgpVty.setDebug(isEnabled = true)

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
        case BGPAdded(bgp) => theBgpSession match {
            case Some(bgpSession) =>
                if (bgpSession.id != bgp.id) {
                    log.warn("({}) Ignoring BGP session {}, only one BGP "+
                        "session per port is supported", phase, bgp.id)
                    stash()
                } else if (bgpSession.localAs != bgp.localAs) {
                    // The first bgp will enforce the AS
                    // for the other bgps. For a given routingHandler, we
                    // only have one bgpd process and all the BGP configs
                    // that specify different peers must have that field
                    // in common.
                    log.error(s"($phase) new sessions must have same AS")
                } else {
                    theBgpSession = Some(bgp)
                    bgpVty.setPeer(bgp.localAs, bgp.peerAddress, bgp.peerAs)
                }

            case None =>
                log.error("({}) This shouldn't be the first bgp "+
                    "connection no other connections have been set "+
                    "up so far", phase)
        }

        case FetchBgpStatus if bgpdProcess ne null =>
            theBgpSession foreach {
                case bgp =>
                    log.debug("querying bgpd neighbour information")
                    try {
                        var status: String = "bgpd daemon is not ready"
                        if ((bgpVty ne null) && bgpdProcess.isAlive) {
                            status = bgpVty.showGeneric("nei").
                                filterNot(_.startsWith("bgpd#")).
                                filterNot(_.startsWith("show ip bgp nei")).
                                foldRight("")((a, b) => s"$a\n$b")
                        }
                        dataClient.bgpSetStatus(bgp.id, status)
                    } catch {
                        case e: Exception =>
                            log.warn("Failed to check bgpd status", e)
                    }
            }

        case BGPRemoved(bgpId) => theBgpSession match {
            case Some(bgp) if bgp.id == bgpId =>
                log.info("({}) Removing BGP session {}", phase, bgpId)
                transition(Stopping)
                theBgpSession = None

                bgpVty.deletePeer(bgp.localAs, bgp.peerAddress)
                RoutingWorkflow.routerPortToBgp.remove(bgp.portId)
                RoutingWorkflow.inputPortToBgp.remove(bgp.quaggaPortNumber)
                invalidateFlows(bgp)

                // If this is the last BGP for ths port, tear everything down.
                peerRoutes.values foreach {
                    routeId => deleteRoute(routeId)
                }
                peerRoutes.clear()
                stopBGP()

            case Some(bgp) =>
                log.warn("({}) Remove BGP session {} unknown ID", phase, bgpId)

            case None =>
                log.warn("({}) Remove BGP session {} unknown ID", phase, bgpId)
        }

        case BGPRouteAdded(route) => theBgpSession match {
            case Some(bgp) if bgp.id == route.bgpId =>
                log.info("({}) Advertise BGP route: {}", phase, route.subnet)
                bgpRoutes += route
                bgpVty.setNetwork(bgp.localAs, route.subnet.getAddress.toString,
                                  route.subnet.getPrefixLen)

            case Some(bgp) =>
                log.error("Ignoring advertised route, belongs to another BGP "+
                          "session (mine={}, theirs={})", bgp.id, route.bgpId)

            case None =>
                log.error("Ignoring advertised route, no BGP session")
        }

        case BGPRouteRemoved(route) =>
            log.info("({}) Stop advertising BGP route: {}", phase, route.subnet)
            bgpRoutes -= route
            theBgpSession match {
                case Some(bgp) if bgp.id == route.bgpId =>
                    bgpVty.deleteNetwork(bgp.localAs,
                                         route.subnet.getAddress.toString,
                                         route.subnet.getPrefixLen)
                case Some(bgp) =>
                    log.error("Ignoring stop-advertise route, belongs to " +
                              "another BGP (mine={}, theirs={})", bgp.id,
                              route.bgpId)

                case None =>
                    log.error("Ignoring stop-advertised route, no BGP session")
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
            val routeId = dataClient.routesCreateEphemeral(route)
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
                case Some(routeId) => deleteRoute(routeId)
                case None =>
            }
    }

    val NotStartedState: Receive = {

        case BGPAdded(bgp) => theBgpSession match {
            case Some(_) =>
                log.error("({}) This should be the first BGP connection but " +
                          "there were already other connections configured",
                          phase)

            case None =>
                log.info("({}) New BGP session {}", phase, bgp)
                theBgpSession = Some(bgp)
                bgpRoutes.retain(bgp.id == _.bgpId)
                bgpdProcess = new BgpdProcess(bgpIdx,
                                    BGP_VTY_LOCAL_IP, BGP_VTY_MIRROR_IP,
                                    rport.portAddr, rport.portMac,
                                    BGP_VTY_PORT, config)
                startBGP()
                transition(Starting)
        }

        case BGPRemoved(bgpId) =>
            // This probably shouldn't happen. A BGP config is being
            // removed, implying we knew about it - we shouldn't be
            // NotStarted...
            log.error("({}) Remove BGP session {}: unexpected", phase, bgpId)

        case BGPRouteAdded(route) =>
            log.info("({}) Advertise BGP route: {}", phase, route.subnet)
            log.warn("({}) Advertise BGP route: unexpected", phase)

        case BGPRouteRemoved(route) =>
            log.info("({}) Stop advertising BGP route: {}", phase, route.subnet)
            log.warn("({}) Stop advertising BGP route: unexpected", phase)
    }

    override def receive = NotStartedState

    def enable() {
        if (!portActive || !zookeeperActive) {
            log.warn("({}) enable() invoked in incorrect state zkActive:{} " +
                     "portActive:{}", phase, Boolean.box(zookeeperActive),
                     Boolean.box(portActive))
            return
        }
        phase match {
            case Disabled => theBgpSession match {
                case Some(bgp) =>
                    log.info("({}) Enabling BGP link: {}", phase, bgp.id)
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
                log.info("({}) Disabling BGP link: {}", phase, bgp.id)
                transition(Disabling)

                RoutingWorkflow.routerPortToBgp.remove(bgp.portId)
                RoutingWorkflow.inputPortToBgp.remove(bgp.quaggaPortNumber)
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
                peerRoutes.values foreach {
                    routeId => deleteRoute(routeId)
                }
                peerRoutes.clear()

            case _ =>
                transition(Disabled)
        }
    }

    def deleteRoute(routeId: UUID) {
        try {
            dataClient.routesDelete(routeId)
        } catch {
            case e: StateAccessException =>
                log.error(s"({$phase}) Exception", e)
                val retry = new Runnable() {
                    @Override
                    override def run() {
                        dataClient.routesDelete(routeId)
                    }
                }

                connWatcher.handleError("BGP delete route: " + routeId, retry, e)
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
        bgpdProcess.prepare()

        log.debug(s"($phase) Adding port to datapath: $BGP_NETDEV_PORT_NAME")
        createDpPort(BGP_NETDEV_PORT_NAME)

        log.debug(s"($phase) prepared environment for bgpd")
    }

    private def stopBGP() {
        log.debug("({}) stopping zebra server", phase)
        context.stop(zebra)

        log.debug("({}) stopping bgpd", phase)
        bgpdProcess.stop()

        // Delete port from datapath
        dpPorts.get(BGP_NETDEV_PORT_NAME) foreach removeDpPort

        self ! BgpdDead
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
        bgp.quaggaPortNumber = newPort.getPortNo
        bgp.uplinkPid = uplinkPid
        RoutingWorkflow.routerPortToBgp.put(bgp.portId, bgp)
        RoutingWorkflow.inputPortToBgp.put(bgp.quaggaPortNumber, bgp)
        invalidateFlows(bgp)

        if (bgpdProcess.start())
            self ! BgpdReady
    }

    private def createSession(localAddr: IPAddr, bgp: BGP) {
        log.debug("({}) Create BGP session to peer AS {} at {}",
                  phase, Int.box(bgp.peerAs), bgp.peerAddress)
        bgpVty.setAs(bgp.localAs)
        bgpVty.setLocalNw(bgp.localAs, localAddr)
        bgpVty.setPeer(bgp.localAs, bgp.peerAddress, bgp.peerAs)

        for (bgpRoute <- bgpRoutes) {
            // If an BGP route is already configured in BGP, it will be
            // silently ignored
            bgpVty.setNetwork(bgp.localAs, bgpRoute.subnet.getAddress.toString,
                              bgpRoute.subnet.getPrefixLen)
            log.debug("({}) added advertised route: {}", phase, bgpRoute)
        }
    }

    private def createDpPort(port: String): Unit = {
        log.debug("Creating port {}", port)
        val f = upcallConnManager.createAndHookDpPort(datapath,
                                                      new NetDevPort(port),
                                                      VirtualMachine)
        f map { case (dpPort, pid) =>
            DpPortCreateSuccess(dpPort, pid)
        } recover { case e => DpPortError(port, e) } pipeTo self
    }

    private def removeDpPort(port: DpPort): Unit = {
        log.debug("Removing port {}", port.getName)
        upcallConnManager.deleteDpPort(datapath, port) map { _ =>
            DpPortDeleteSuccess(port)
        } recover { case e => DpPortError(port.getName, e) } pipeTo self
    }

    private def invalidateFlows(bgp: BGP): Unit = {
        val tag = FlowTagger.tagForDpPort(bgp.quaggaPortNumber)
        flowInvalidator.scheduleInvalidationFor(tag)
    }
}

