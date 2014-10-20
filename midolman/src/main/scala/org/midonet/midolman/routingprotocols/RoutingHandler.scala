/*
 * Copyright (c) 2013 Midokura SARL, All Rights Reserved.
 */
package org.midonet.midolman.routingprotocols

import java.io.File
import java.util.{ArrayList, UUID}
import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.util.{Failure, Success}

import akka.actor.{Stash, Actor, ActorRef}

import org.midonet.cluster.client.{Port, RouterPort, BGPListBuilder}
import org.midonet.cluster.data.{Route, AdRoute, BGP}
import org.midonet.cluster.{Client, DataClient}
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.FlowController.AddWildcardFlow
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.midolman.routingprotocols.RoutingManagerActor.BgpStatus
import org.midonet.midolman.simulation.PacketContext
import org.midonet.midolman.state.{ZkConnectionAwareWatcher, StateAccessException}
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.midolman.topology.VirtualTopologyActor.PortRequest
import org.midonet.midolman._
import org.midonet.netlink.AfUnix
import org.midonet.odp.flows.FlowAction
import org.midonet.odp.flows.FlowActions.{output, userspace}
import org.midonet.odp.ports.NetDevPort
import org.midonet.packets._
import org.midonet.quagga.ZebraProtocol.RIBType
import org.midonet.quagga._
import org.midonet.sdn.flows.{FlowTagger, WildcardFlow, WildcardMatch}
import org.midonet.sdn.flows.VirtualActions.FlowActionOutputToVrnPort
import org.midonet.util.eventloop.SelectLoop
import org.midonet.util.functors.Callback0
import org.midonet.util.process.ProcessHelper

object RoutingHandler {

    /** A conventional value for Ip prefix of BGP pairs.
     *  172 is the MS byte value and 23 the second byte value.
     *  Last 2 LS bytes are available for assigning BGP pairs. */
    val BGP_IP_INT_PREFIX = 172 * (1<<24) + 23 * (1<<16)

    // BgpdProcess will notify via these messages
    case object BGPD_READY
    case object BGPD_DEAD
    case class BGPD_SHOW(cmd : String)

    case class PortActive(active: Boolean)

    private case class ZookeeperActive(active: Boolean)

    private case class NewBgpSession(bgp: BGP)

    private case class ModifyBgpSession(bgp: BGP)

    private case class RemoveBgpSession(bgpID: UUID)

    private case class AdvertiseRoute(route: AdRoute)

    private case class StopAdvertisingRoute(route: AdRoute)

    private case class AddPeerRoute(ribType: RIBType.Value,
                                    destination: IPv4Subnet, gateway: IPv4Addr)

    private case class RemovePeerRoute(ribType: RIBType.Value,
                                       destination: IPv4Subnet,
                                       gateway: IPv4Addr)

    // For testing
    case class BGPD_STATUS(port: UUID, isActive: Boolean)

    case class PEER_ROUTE_ADDED(router: UUID, route: Route)
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
                     val dpState: DatapathState,
                     val client: Client, val dataClient: DataClient,
                     val config: MidolmanConfig,
                     val connWatcher: ZkConnectionAwareWatcher,
                     val selectLoop: SelectLoop)
    extends Actor with ActorLogWithoutPath with Stash with FlowTranslator {

    import RoutingHandler._
    import DatapathController._

    override protected implicit val system = context.system

    override def logSource = s"org.midonet.routing.bgp.bgp-$bgpIdx"

    private final val BGP_NETDEV_PORT_NAME: String =
        "mbgp%d".format(bgpIdx)
    private final val BGP_NETDEV_PORT_MIRROR_NAME: String =
        "mbgp%d_m".format(bgpIdx)
    private final val BGP_VTY_PORT_NAME: String =
        "mbgp%d_vty".format(bgpIdx)
    private final val BGP_VTY_PORT_MIRROR_NAME: String =
        "mbgp%d_vtym".format(bgpIdx)
    private final val BGP_VTY_BRIDGE_NAME: String =
        "mbgp%d_br".format(bgpIdx)
    private final val BGP_VTY_LOCAL_IP: String =
        IPv4Addr.intToString(BGP_IP_INT_PREFIX + 1 + 4 * bgpIdx)
    private final val BGP_VTY_MIRROR_IP: String =
        IPv4Addr.intToString(BGP_IP_INT_PREFIX + 2 + 4 * bgpIdx)
    private final val BGP_VTY_MASK_LEN: Int = 30
    private final val BGP_NETWORK_NAMESPACE: String =
        "mbgp%d_ns".format(bgpIdx)
    private final val ZSERVE_API_SOCKET =
        "/var/run/quagga/zserv%d.api".format(bgpIdx)
    private final val BGP_VTY_PORT: Int = 2605 + bgpIdx
    private final val BGP_TCP_PORT: Short = 179

    private var zebra: ActorRef = null
    private var bgpVty: BgpConnection = null

    private val bgps = mutable.Map[UUID, BGP]()
    private val adRoutes = mutable.Set[AdRoute]()
    private val peerRoutes = mutable.Map[Route, UUID]()
    private var socketAddress: AfUnix.Address = null

    private val dpPorts = mutable.Map[String, NetDevPort]()

    // At this moment we only support one bgpd process
    private var bgpdProcess: BgpdProcess = null

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

    var phase = NotStarted

    var zookeeperActive = true
    var portActive = true

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

        connWatcher.scheduleOnDisconnect(new Runnable() {
            override def run() {
                self ! ZookeeperActive(false)
                connWatcher.scheduleOnDisconnect(this)
            }
        })

        connWatcher.scheduleOnReconnect(new Runnable() {
            override def run() {
                self ! ZookeeperActive(true)
                connWatcher.scheduleOnReconnect(this)
            }
        })

        // Subscribe to the VTA for updates to the Port configuration.
        VirtualTopologyActor ! PortRequest(rport.id, update = true)

        log.debug("({}) Started", phase)
    }

    override def postStop() {
        super.postStop()
        disable()
        log.debug("({}) Stopped", phase)
    }

    private val handler = new ZebraProtocolHandler {
        def addRoute(ribType: RIBType.Value, destination: IPv4Subnet,
                     gateway: IPv4Addr) {
            self ! AddPeerRoute(ribType, destination, gateway)
        }

        def removeRoute(ribType: RIBType.Value, destination: IPv4Subnet,
                        gateway: IPv4Addr) {
            self ! RemovePeerRoute(ribType, destination, gateway)
        }
    }

    override def receive = {
        case port: RouterPort if port.isExterior =>
            var store = true
            phase match {
                case Starting =>
                    store = false
                    stash()
                case Started =>
                // TODO(pino): reconfigure the internal port now
                case _ => // fall through
            }
            if (store)
                rport = port
        case port: Port =>
            log.error("({}) Cannot run BGP on anything but an exterior " +
                "virtual router port. We got {}", phase, port)

        case NewBgpSession(bgp) =>
            log.info("({}) New BGP session.", phase)
            phase match {
                case NotStarted if bgps.size != 0 =>
                    // This must be the first bgp we learn about.
                    log.error("({}) This should be the first BGP connection but" +
                        " there were already other connections configured", phase)

                case NotStarted =>
                    bgps.put(bgp.getId, bgp)
                    startBGP()
                    phase = Starting

                case Starting =>
                    stash()

                case Started if bgps.size != 0 =>
                    log.error("({}) Ignoring BGP session {}, only one BGP "+
                        "session per port is supported", phase, bgp.getId)

                case Started =>
                    // The first bgp will enforce the AS
                    // for the other bgps. For a given routingHandler, we
                    // only have one bgpd process and all the BGP configs
                    // that specify different peers must have that field
                    // in common.
                    log.error("({}) This shouldn't be the first bgp "+
                        "connection no other connections have been set "+
                        "up so far", phase)

                    val bgpPair = bgps.toList.head
                    val originalBgp = bgpPair._2

                    if (bgp.getLocalAS != originalBgp.getLocalAS) {
                        log.error("({}) new sessions must have same AS", phase)
                    } else {
                        if (bgps.contains(bgp.getId))
                            bgps.remove(bgp.getId)
                        bgps.put(bgp.getId, bgp)
                        bgpVty.setPeer(bgp.getLocalAS,
                                       bgp.getPeerAddr,
                                       bgp.getPeerAS)
                    }

                case _ =>
                    stash()
            }

        case ModifyBgpSession(bgp) =>
            //TODO(abel) case not implemented (yet)
            log.info("({}) Not implemented: ModifyBgpSession", phase)

        case RemoveBgpSession(bgpID) =>
            phase match {
                case NotStarted =>
                    // This probably shouldn't happen. A BGP config is being
                    // removed, implying we knew about it - we shouldn't be
                    // NotStarted...
                    log.error("({}) RemoveBgpSession({}): unexpected", phase, bgpID)
                case Starting =>
                    stash()
                case Started if bgps.contains(bgpID) =>
                    // TODO(pino): Use bgpVty to remove this BGP and its routes
                    log.info("({}) Removing BGP session {}", phase, bgpID)
                    val Some(bgp) = bgps.remove(bgpID)
                    bgpVty.deletePeer(bgp.getLocalAS, bgp.getPeerAddr)
                    // Remove all the flows for this BGP link
                    FlowController !
                        FlowController.InvalidateFlowsByTag(FlowTagger.tagForBgp(bgpID))

                    // If this is the last BGP for ths port, tear everything down.
                    if (bgps.size == 0) {
                        phase = Stopping
                        peerRoutes.values foreach {
                            routeId => deleteRoute(routeId)
                        }
                        peerRoutes.clear()
                        stopBGP()
                    }
                case Started =>
                    log.warn("({}) RemoveBgpSession({}) unknown id", phase, bgpID)

                case _ =>
                    stash()
            }

        case DpPortDeleteSuccess(_, netdevPort) =>
            log.info("({}) Datapath port was deleted: {} ", phase, netdevPort.getName)
            dpPorts.remove(netdevPort.getName)

        case DpPortCreateSuccess(_, netdevPort, pid) =>
            log.info("({}) Datapath port was created: {}", phase, netdevPort.getName)
            phase match {
                case Starting =>
                    netdevPortReady(netdevPort.asInstanceOf[NetDevPort], pid)
                case _ =>
                    log.error("({}) PortNetdevOpReply expected only while " +
                        "Starting", phase)
            }

        case DpPortError(DpPortCreateNetdev(port, _), ex) =>
            // Only log errors, do nothing
            log.error(s"({$phase}) Datapath port creation failed: ${port.getName}", ex)

        case BGPD_READY =>
            log.debug("({}) BGPD_READY", phase)
            phase match {
                case Starting =>
                    phase = Started
                    unstashAll()

                    bgpVty = new BgpVtyConnection(
                        addr = BGP_VTY_MIRROR_IP,
                        port = BGP_VTY_PORT,
                        password = "zebra_password",
                        keepAliveTime = config.getMidolmanBGPKeepAlive,
                        holdTime = config.getMidolmanBGPHoldtime,
                        connectRetryTime = config.getMidolmanBGPConnectRetry)

                    bgpVty.setLogFile("/var/log/quagga/bgpd." + BGP_VTY_PORT + ".log")
                    if (log.underlying.isDebugEnabled)
                        bgpVty.setDebug(isEnabled = true)

                    for (bgp <- bgps.values) {
                        // Use the bgpVty to set up sessions with all these peers
                        create(rport.portAddr.getAddress, bgp)
                    }

                    // BGP routes are added on:
                    // - create method
                    // - received from BGPListBuilder

                    log.debug("({}) announcing we are BGPD_STATUS active", phase)
                    context.system.eventStream.publish(
                        BGPD_STATUS(rport.id, true))

                case _ =>
                    log.error("({}) BGP_READY expected only while Starting", phase)
            }

        case BGPD_DEAD =>
            log.debug("({}) BGPD_DEAD", phase)
            phase match {
                case Enabling =>
                    phase = Disabled
                    enable()
                case Disabling =>
                    phase = Disabled
                case Disabled =>
                case Stopping =>
                    phase = NotStarted
                    unstashAll()
                case _ =>
                    log.error("({}) unexpected BGPD_DEAD message", phase)
            }
        case BGPD_SHOW(cmd) =>
            if (bgpVty != null)
                sender ! BgpStatus(bgpVty.showGeneric(cmd).toArray)
            else
                sender ! BgpStatus(Array[String]("BGP session is not ready"))

        case AdvertiseRoute(rt) =>
            log.info(s"({$phase}) Advertise: ${rt.getNwPrefix}/${rt.getPrefixLength}")
            phase match {
                case NotStarted =>
                    log.warn("({}) Advertise: unexpected", phase)
                case Starting =>
                    log.debug("({}) Advertise: stashing", phase)
                    stash()
                case Started =>
                    adRoutes.add(rt)
                    val bgp = bgps.get(rt.getBgpId)
                    bgp match {
                        case Some(b) =>
                            log.debug(s"({$phase}) Advertise: local AS is ${b.getLocalAS}")
                            val as = b.getLocalAS
                            bgpVty.setNetwork(as, rt.getNwPrefix.getHostAddress, rt.getPrefixLength)
                        case None =>
                            log.debug("({}) Advertise: unknown BGP {}",
                                      phase, rt.getBgpId)
                    }

                case _ =>
                    log.debug("({}) Advertise: stashing", phase)
                    stash()
            }

        case StopAdvertisingRoute(rt) =>
            log.info(s"({$phase}) StopAdvertising: " +
                     s"${rt.getNwPrefix}/${rt.getPrefixLength}")
            phase match {
                case NotStarted =>
                    log.warn(s"({$phase}) StopAdvertising: unexpected")
                case Starting =>
                    log.debug(s"({$phase}) StopAdvertising: stashing")
                    stash()
                case Started =>
                    adRoutes.remove(rt)
                    bgps.get(rt.getBgpId) foreach { b =>
                        bgpVty.deleteNetwork(b.getLocalAS,
                                             rt.getNwPrefix.getHostAddress,
                                             rt.getPrefixLength)
                    }

                case _ =>
                    log.debug("({}) StopAdvertising: stashing", phase)
                    stash()
            }

        case AddPeerRoute(ribType, destination, gateway) =>
            log.info("({}) AddPeerRoute: {}, {}, {}",
                     phase, ribType, destination, gateway)
            phase match {
                case NotStarted =>
                    log.error("({}) AddPeerRoute: unexpected", phase)
                case Starting =>
                    log.debug("({}) AddPeerRoute: stashing", phase)
                    stash()
                case Started if peerRoutes.size > 100 =>
                    /* TODO(abel) in order not to overwhelm the cluster, we will
                     * limit the max amount of routes we store at least for this
                     * version of the code. Note that peer routes, if not
                     * limited by bgpd or by the peer, can grow to hundreds of
                     * thousands of entries.
                     * I won't use a constant for this number because this
                     * problem should be tackled in a more elegant way and it's
                     * not used elsewhere. */
                    log.error("({}) Max no. of peer routes reached (100)", phase)

                case Started =>
                    val route = new Route()
                    route.setRouterId(rport.deviceID)
                    route.setDstNetworkAddr(destination.getAddress.toString)
                    route.setDstNetworkLength(destination.getPrefixLen)
                    route.setNextHopGateway(gateway.toString)
                    route.setNextHop(org.midonet.midolman.layer3.Route.NextHop.PORT)
                    route.setNextHopPort(rport.id)
                    val routeId = dataClient.routesCreateEphemeral(route)
                    peerRoutes.put(route, routeId)
                    log.debug("({}) announcing we've added a peer route", phase)
                    context.system.eventStream.publish(
                        new PEER_ROUTE_ADDED(rport.deviceID, route))

                case _ =>
                    log.debug("({}) AddPeerRoute: ignoring", phase)
                    // ignore
            }

        case RemovePeerRoute(ribType, destination, gateway) =>
            log.info("({}) RemovePeerRoute: {}, {}, {}",
                     phase, ribType, destination, gateway)
            phase match {
                case NotStarted =>
                    log.error("({}) RemovePeerRoute: unexpected", phase)
                case Starting =>
                    log.debug("({}) RemovePeerRoute: stashing", phase)
                    stash()
                case Started =>
                    val route = new Route()
                    route.setRouterId(rport.deviceID)
                    route.setDstNetworkAddr(destination.getAddress.toString)
                    route.setDstNetworkLength(destination.getPrefixLen)
                    route.setNextHopGateway(gateway.toString)
                    route.setNextHop(org.midonet.midolman.layer3.Route.NextHop.PORT)
                    route.setNextHopPort(rport.id)
                    peerRoutes.remove(route) match {
                        case Some(routeId) => deleteRoute(routeId)
                        case None =>
                    }
                case _ =>
                    // ignore
                    log.debug("({}) RemovePeerRoute: ignoring", phase)
            }

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
            zookeeperActive = true
            disable()

        case m =>
            log.warn(s"({$phase}) Unexpected message $m")
    }

    def enable() {
        if (!portActive || !zookeeperActive) {
            log.warn(s"({$phase}) enable() invoked in incorrect state " +
                     s"zkActive:$zookeeperActive portActive:$portActive")
            return
        }
        phase match {
            case Disabled =>
                if (bgps.size > 0) {
                    log.info("({}) Enabling BGP link: {}", phase, bgps.head._1)
                    startBGP()
                    phase = Starting
                } else {
                    phase = NotStarted
                    unstashAll()
                }
            case Disabling =>
                phase = Enabling
            case _ =>
                log.warn("({}) enable() unexpected call", phase)
        }
    }

    def disable() {
        phase match {
            case Disabled =>
            case Disabling =>
            case Enabling =>
                phase = Disabled
            case _ if bgps.size > 0 =>
                log.info("({}) Disabling BGP link: {}", phase, bgps.head._1)
                phase = Disabling

                FlowController !
                    FlowController.InvalidateFlowsByTag(FlowTagger.tagForBgp(bgps.head._1))
                stopBGP()

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
                phase = Disabled
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

        // In Linux interface names can be at most 15 characters long
        List(
            BGP_NETDEV_PORT_NAME,
            BGP_NETDEV_PORT_MIRROR_NAME,
            BGP_VTY_PORT_NAME,
            BGP_VTY_PORT_MIRROR_NAME
        ).withFilter{ _.length > 15 }.foreach { itf =>
            log.error("({}) Interface name can be at most 15 chars: {}", phase, itf)
            return
        }

        // If there was a bgpd running in a previous execution and wasn't
        // properly stopped, it may still be running -> kill it
        val bgpdPidFile = "/var/run/quagga/bgpd." + BGP_VTY_PORT + ".pid"
        val file = new File(bgpdPidFile)
        if (file.exists()) {
            log.debug("({}) found bgpd pid file", phase)
            val source = scala.io.Source.fromFile(bgpdPidFile)
            val lines = source.getLines()

            // we only expect one pid stored here
            if (lines.hasNext) {
                val cmdLine = "kill -9 " + lines.next()
                log.debug("({}) killing lingering bgpd: {}", phase, cmdLine)
                ProcessHelper.executeCommandLine(cmdLine)
            }
        }

        val socketFile = new File(ZSERVE_API_SOCKET)
        val socketDir = socketFile.getParentFile
        if (!socketDir.exists()) {
            log.debug("({}) Creating socket dir at {}", phase, socketDir.getAbsolutePath)
            socketDir.mkdirs()
            // Set permission to let quagga daemons write.
            socketDir.setWritable(true, false)
        }

        if (socketFile.exists()) {
            log.debug("({}) Deleting socket file at {}", phase, socketFile.getAbsolutePath)
            socketFile.delete()
        }

        log.debug("({}) Starting zebra server actor", phase)
        socketAddress = new AfUnix.Address(socketFile.getAbsolutePath)
        zebra = ZebraServer(socketAddress, handler, rport.portAddr.getAddress,
                            BGP_NETDEV_PORT_MIRROR_NAME, selectLoop)


        /* Bgp namespace configuration */

        val bgpNS = BGP_NETWORK_NAMESPACE

        log.debug("({}) Creating namespace: {}", phase, bgpNS)
        IP.ensureNamespace(bgpNS)

        /* Bgp interface configuration */
        log.debug("({}) Setting up bgp interface: {}", phase, BGP_NETDEV_PORT_NAME)

        val bgpPort = BGP_NETDEV_PORT_NAME
        val bgpMirror = BGP_NETDEV_PORT_MIRROR_NAME
        val (bgpMac, bgpIp) = (rport.portMac.toString, rport.portAddr.toString)

        IP.ensureNoInterface(bgpPort)
        IP.preparePair(bgpPort, bgpMirror, bgpNS)
        IP.configureMac(bgpMirror, bgpMac, bgpNS)
        IP.configureIp(bgpMirror, bgpIp, bgpNS)

        // wake up loopback inside network namespace
        IP.execIn(bgpNS, " ifconfig lo up")
        IP.configureUp(bgpPort)

        // Add port to datapath
        log.debug("({}) Adding port to datapath: {}", phase, BGP_NETDEV_PORT_NAME)
        DatapathController !
            DpPortCreateNetdev(new NetDevPort(BGP_NETDEV_PORT_NAME), null)

        /* VTY interface configuration */
        log.debug("({}) Setting up vty interface: {}", phase, BGP_VTY_PORT_NAME)

        val vtyPort = BGP_VTY_PORT_NAME
        val vtyMirror = BGP_VTY_PORT_MIRROR_NAME
        val vtyLocalIp = BGP_VTY_LOCAL_IP + "/" + BGP_VTY_MASK_LEN
        val vtyMirrorIp = BGP_VTY_MIRROR_IP + "/" + BGP_VTY_MASK_LEN

        IP.ensureNoInterface(vtyPort)
        IP.preparePair(vtyPort, vtyMirror, bgpNS)
        IP.configureUp(vtyMirror, bgpNS)
        IP.configureIp(vtyMirror, vtyMirrorIp, bgpNS)
        IP.configureUp(vtyPort)

        /* bridge configuration */
        log.debug("({}) Setting up bridge for vty: {}", phase, BGP_VTY_BRIDGE_NAME)
        val bgpBridge = BGP_VTY_BRIDGE_NAME

        // Create a bridge for VTY communication
        BRCTL.addBr(bgpBridge)

        // Add VTY interface to VTY bridge
        BRCTL.addItf(bgpBridge, vtyPort)

        // Set up bridge as local VTY interface
        IP.configureIp(bgpBridge, vtyLocalIp)

        // Bring up bridge interface for VTY
        IP.configureUp(bgpBridge)

        log.debug("({}) prepared environment for bgpd", phase)
    }

    private def stopBGP() {
        log.debug("({}) stopping bgpd", phase)

        if (bgpdProcess != null) {
            log.debug("({}) bgpd is running, stop", phase)
            bgpdProcess.stop()
        }

        log.debug("({}) stopping zebra server", phase)
        context.stop(zebra)

        log.debug("({}) cleaning up bgpd environment", phase)
        // delete vty interface pair
        IP.deleteItf(BGP_VTY_PORT_NAME)

        // get vty bridge down
        IP.configureDown(BGP_VTY_BRIDGE_NAME )

        // delete vty bridge
        BRCTL.deleteBr(BGP_VTY_BRIDGE_NAME)

        // delete bgp interface pair
        IP.deleteItf(BGP_NETDEV_PORT_NAME)

        // delete network namespace
        IP.deleteNS(BGP_NETWORK_NAMESPACE)

        // Delete port from datapath
        dpPorts.get(BGP_NETDEV_PORT_NAME) foreach {
            DatapathController ! DpPortDeleteNetdev(_, null)
        }

        log.debug("({}) announcing BGPD_STATUS inactive", phase)
        context.system.eventStream.publish(BGPD_STATUS(rport.id, false))

        self ! BGPD_DEAD
    }

    private def netdevPortReady(newPort: NetDevPort, uplinkPid: Int) {
        log.debug("({}) datapath port is ready, starting bgpd", phase)

        dpPorts.put(newPort.getName, newPort)

        // The internal port is ready. Set up the flows
        if (bgps.size <= 0) {
            log.warn("({}) No BGPs configured for this port: {}", phase, newPort)
            return
        }

        // Of all the possible BGPs configured for this port, we only consider
        // the very first one to create the BGPd process
        val bgpPair = bgps.toList.head
        val bgp = bgpPair._2
        setBGPFlows(newPort.getPortNo.shortValue(), bgp, rport, uplinkPid)

        bgpdProcess = new BgpdProcess(self, BGP_VTY_PORT,
            rport.portAddr.getAddress.toString, socketAddress,
            BGP_NETWORK_NAMESPACE, config)
        val didStart = bgpdProcess.start()
        if (didStart) {
            self ! BGPD_READY
            log.debug("({}) bgpd process did start", phase)
        } else {
            log.debug("({}) bgpd process did not start", phase)
        }
    }

    def create(localAddr: IPAddr, bgp: BGP) {
        log.debug(s"({$phase}) create bgp session to peer " +
                  s"AS ${bgp.getPeerAS} at ${bgp.getPeerAddr}")
        bgpVty.setAs(bgp.getLocalAS)
        bgpVty.setLocalNw(bgp.getLocalAS, localAddr)
        bgpVty.setPeer(bgp.getLocalAS, bgp.getPeerAddr, bgp.getPeerAS)

        for (adRoute <- adRoutes) {
            // If an adRoute is already configured in bgp, it will be
            // silently ignored
            bgpVty.setNetwork(bgp.getLocalAS, adRoute.getNwPrefix.getHostAddress,
                adRoute.getPrefixLength)
            adRoutes.add(adRoute)
            log.debug("({}) added advertised route: {}", phase, adRoute)
        }
    }

    def setBGPFlows(localPortNum: Short, bgp: BGP,
                    bgpPort: RouterPort, uplinkPid: Int) {

        log.debug("({}) setting up wildcard flows for bgpd", phase)

        def addVirtualWildcardFlow(wcMatch: WildcardMatch,
                                   actions: List[FlowAction],
                                   inputPort: UUID = null): Unit = {
            val pktCtx = new PacketContext(Left(-1), null, None, wcMatch)
            pktCtx.addFlowTag(FlowTagger.tagForBgp(bgp.getId))
            pktCtx.inputPort = inputPort
            try {
                val translated = translateActions(pktCtx, actions)
                val flow = WildcardFlow(wcMatch, translated.toList)
                log.debug("({}) Installing: {}", phase, flow)
                FlowController ! AddWildcardFlow(flow, null,
                                                 new ArrayList[Callback0],
                                                 pktCtx.flowTags)
            } catch { case NotYetException(f, _) =>
                f.onComplete {
                    case Success(_) =>
                        addVirtualWildcardFlow(wcMatch, actions) // Thread-safe
                    case Failure(ex) =>
                        log.error(s"({$phase}) AddVirtualWildcardFlow failed to complete", ex)
                    }
            }
        }

        // TCP4:->179 bgpd->link
        var wildcardMatch = new WildcardMatch()
            .setInputPortNumber(localPortNum)
            .setEtherType(IPv4.ETHERTYPE)
            .setNetworkProtocol(TCP.PROTOCOL_NUMBER)
            .setNetworkSource(bgpPort.portAddr.getAddress)
            .setNetworkDestination(bgp.getPeerAddr)
            .setTransportDestination(BGP_TCP_PORT)
        addVirtualWildcardFlow(wildcardMatch, List(FlowActionOutputToVrnPort(bgpPort.id)))

        // TCP4:179-> bgpd->link
        wildcardMatch = new WildcardMatch()
            .setInputPortNumber(localPortNum)
            .setEtherType(IPv4.ETHERTYPE)
            .setNetworkProtocol(TCP.PROTOCOL_NUMBER)
            .setNetworkSource(bgpPort.portAddr.getAddress)
            .setNetworkDestination(bgp.getPeerAddr)
            .setTransportSource(BGP_TCP_PORT)
        addVirtualWildcardFlow(wildcardMatch, List(FlowActionOutputToVrnPort(bgpPort.id)))

        // TCP4:->179 link->bgpd
        wildcardMatch = new WildcardMatch()
            .setEtherType(IPv4.ETHERTYPE)
            .setNetworkProtocol(TCP.PROTOCOL_NUMBER)
            .setNetworkSource(bgp.getPeerAddr)
            .setNetworkDestination(bgpPort.portAddr.getAddress)
            .setTransportDestination(BGP_TCP_PORT)
        addVirtualWildcardFlow(wildcardMatch, List(output(localPortNum)), bgpPort.id)

        // TCP4:179-> link->bgpd
        wildcardMatch = new WildcardMatch()
            .setEtherType(IPv4.ETHERTYPE)
            .setNetworkProtocol(TCP.PROTOCOL_NUMBER)
            .setNetworkSource(bgp.getPeerAddr)
            .setNetworkDestination(bgpPort.portAddr.getAddress)
            .setTransportSource(BGP_TCP_PORT)
        addVirtualWildcardFlow(wildcardMatch, List(output(localPortNum)), bgpPort.id)

        // ARP bgpd->link
        wildcardMatch = new WildcardMatch()
            .setInputPortNumber(localPortNum)
            .setEtherType(ARP.ETHERTYPE)
        addVirtualWildcardFlow(wildcardMatch, List(FlowActionOutputToVrnPort(bgpPort.id)))

        // ARP link->bgpd, link->midolman
        // Both MM and bgpd need to know the peer's MAC address, so we install
        // a wildcard flow that sends the ARP replies to both MM and bgpd.
        wildcardMatch = new WildcardMatch()
            .setEtherType(ARP.ETHERTYPE)
            .setEthernetDestination(bgpPort.portMac)
            // nwProto is overloaded in WildcardMatch to store the arp op type.
            .setNetworkProtocol(ARP.OP_REPLY.toByte)
            // nwSrc/nwDst are overloaded to store the arp sip and tip.
            .setNetworkSource(bgp.getPeerAddr)
            .setNetworkDestination(bgpPort.portAddr.getAddress)
        addVirtualWildcardFlow(wildcardMatch, List(output(localPortNum),
                                                   userspace(uplinkPid)), bgpPort.id)

        // ICMP4 bgpd->link
        wildcardMatch = new WildcardMatch()
            .setInputPortNumber(localPortNum)
            .setEtherType(IPv4.ETHERTYPE)
            .setNetworkProtocol(ICMP.PROTOCOL_NUMBER)
            .setNetworkSource(bgpPort.portAddr.getAddress)
            .setNetworkDestination(bgp.getPeerAddr)
        addVirtualWildcardFlow(wildcardMatch, List(FlowActionOutputToVrnPort(bgpPort.id)))

        // ICMP4 link->bgpd
        wildcardMatch = new WildcardMatch()
            .setEtherType(IPv4.ETHERTYPE)
            .setNetworkProtocol(ICMP.PROTOCOL_NUMBER)
            .setNetworkSource(bgp.getPeerAddr)
            .setNetworkDestination(bgpPort.portAddr.getAddress)
        addVirtualWildcardFlow(wildcardMatch, List(output(localPortNum)), bgpPort.id)
    }

}

object IP { /* wrapper to ip commands => TODO: implement with RTNETLINK */

    val exec: String => Int =
        ProcessHelper.executeCommandLine(_).returnValue

    val execNoErrors: String => Int =
        ProcessHelper.executeCommandLine(_, true).returnValue

    val execGetOutput: String => java.util.List[String] =
        ProcessHelper.executeCommandLine(_).consoleOutput

    val link: String => Int =
        s => exec("ip link " + s)

    val ifaceExists: String => Int =
        s => execNoErrors("ip link show " + s)

    val netns: String => Int =
        s => exec("ip netns " + s)

    val netnsGetOutput: String => java.util.List[String] =
        s => execGetOutput("ip netns " + s)

    def execIn(ns: String, cmd: String): Int =
        if (ns == "") exec(cmd) else netns("exec " + ns + " " + cmd)

    def addNS(ns: String) = netns("add " + ns)

    def deleteNS(ns: String) = netns("del " + ns)

    def namespaceExist(ns: String) =
        ProcessHelper.executeCommandLine("ip netns list")
            .consoleOutput.exists(_.contains(ns))

    /** Create a network namespace with name "ns" if it does not already exist.
     *  Do not try to delete an old network namespace with same name, because
     *  trying to do so when there's a process still running or interfaces
     *  still using it, it can lead to namespace corruption.
     */
    def ensureNamespace(ns: String): Int =
        if (!namespaceExist(ns)) addNS(ns) else 0

    /** checks if an interface exists and deletes if it does */
    def ensureNoInterface(itf: String) =
        if (ifaceExists(itf) == 0) IP.deleteItf(itf) else 0

    def deleteItf(itf: String) = link(" delete " + itf)

    def interfaceExistsInNs(ns: String, interface: String): Boolean =
        if (!namespaceExist(ns)) false
        else (netns("exec " + ns + " ip link show " + interface) == 0)

    /** creates an interface anad put a mirror in given network namespace */
    def preparePair(itf: String, mirror: String, ns: String) =
        link("add name " + itf + " type veth peer name " + mirror) |
        link("set " + mirror + " netns " + ns)

    /** wake up local interface with given name */
    def configureUp(itf: String, ns: String = "") =
        execIn(ns, "ip link set dev " + itf + " up")

    /** wake up local interface with given name */
    def configureDown(itf: String, ns: String = "") =
        execIn(ns, "ip link set dev " + itf + " down")

    /** Configure the mac address of given interface */
    def configureMac(itf: String, mac: String, ns: String = "") =
        execIn(ns, " ip link set dev " + itf + " up address " + mac)

    /** Configure the ip address of given interface */
    def configureIp(itf: String, ip: String, ns: String = "") =
        execIn(ns, " ip addr add " + ip + " dev " + itf)

}

object BRCTL { /* wrapper to brctl commands => TODO: implement with RTNETLINK */

    def addBr(br: String) = IP.exec("brctl addbr " + br)

    def deleteBr(br: String) = IP.exec("brctl delbr " + br)

    def addItf(br: String, it: String) = IP.exec("brctl addif " + br + " " + it)

}
