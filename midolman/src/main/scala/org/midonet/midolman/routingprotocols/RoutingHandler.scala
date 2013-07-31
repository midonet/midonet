/*
 * Copyright 2012 Midokura Europe SARL
 */

package org.midonet.midolman.routingprotocols

import akka.actor.{ActorRef, Props, UntypedActorWithStash}
import collection.mutable
import java.io.File
import java.util.UUID

import org.midonet.midolman.{PortOperation, DatapathController, FlowController}
import org.midonet.midolman.DatapathController.{PortNetdevOpReply, CreatePortNetdev}
import org.midonet.midolman.PacketWorkflow.AddVirtualWildcardFlow
import org.midonet.midolman.datapath.FlowActionOutputToVrnPort
import org.midonet.midolman.topology.VirtualTopologyActor.PortRequest
import org.midonet.midolman.topology.{FlowTagger, VirtualTopologyActor}
import org.midonet.cluster.{Client, DataClient}
import org.midonet.cluster.client.{Port, ExteriorRouterPort, BGPListBuilder}
import org.midonet.cluster.data.{Route, AdRoute, BGP}
import org.midonet.odp.Ports
import org.midonet.odp.flows.{FlowActionUserspace, FlowActions}
import org.midonet.odp.ports.NetDevPort
import org.midonet.packets._
import org.midonet.quagga._
import org.midonet.quagga.ZebraProtocol.RIBType
import org.midonet.sdn.flows.{WildcardFlow, WildcardMatch}
import org.midonet.util.process.ProcessHelper
import scala.collection.JavaConversions._
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.state.{ZkConnectionAwareWatcher, StateAccessException}
import org.midonet.util.eventloop.SelectLoop
import org.midonet.netlink.AfUnix

object RoutingHandler {

    /** A conventional value for Ip prefix of BGP pairs.
     *  173 is the MS byte value and 23 the second byte value.
     *  Last 2 LS bytes are available for assigning BGP pairs. */
    val BGP_IP_PREFIX = 173 * (1<<24) + 23 * (1<<16)

    // BgpdProcess will notify via these messages
    case object BGPD_READY
    case object BGPD_DEAD

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
class RoutingHandler(var rport: ExteriorRouterPort, val bgpIdx: Int,
                     val client: Client, val dataClient: DataClient,
                     val config: MidolmanConfig,
                     val connWatcher: ZkConnectionAwareWatcher,
                     val selectLoop: SelectLoop)
    extends UntypedActorWithStash with ActorLogWithoutPath {

    import RoutingHandler._

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
        IPv4Addr.intToIpStr(BGP_IP_PREFIX + 1 + 4 * bgpIdx)
    private final val BGP_VTY_MIRROR_IP: String =
        IPv4Addr.intToIpStr(BGP_IP_PREFIX + 2 + 4 * bgpIdx)
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
        log.debug("Starting routingHandler for port {}.", rport.id)

        super.preStart()

        if (rport == null) {
            log.error("port is null")
            return
        }

        if (client == null) {
            log.error("client is null")
            return
        }

        if (dataClient == null) {
            log.error("dataClient is null")
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
        VirtualTopologyActor.getRef() ! PortRequest(rport.id, update = true)

        log.debug("RoutingHandler started.")
    }

    override def postStop() {
        super.postStop()
        disable()
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

    @scala.throws(classOf[Exception])
    def onReceive(message: Any) {
        message match {
            case port: ExteriorRouterPort =>
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

            case port: Port[_] =>
                log.error("Cannot run BGP on anything but an exterior " +
                    "virtual router port. We got {}", port)

            case NewBgpSession(bgp) =>
                log.debug("NewBgpSession called for port {}.", rport.id)
                phase match {
                    case NotStarted =>
                        // This must be the first bgp we learn about.
                        if (bgps.size != 0) {
                            log.error("This should be the first bgp connection" +
                                " but there was already other connections configured")
                            return
                        }
                        bgps.put(bgp.getId, bgp)
                        startBGP()
                        phase = Starting
                    case Starting =>
                        stash()
                    case Started =>
                        // The first bgp will enforce the AS
                        // for the other bgps. For a given routingHandler, we
                        // only have one bgpd process and all the BGP configs
                        // that specify different peers must have that field
                        // in common.
                        if (bgps.size == 0) {
                            log.error("This shouldn't be the first bgp connection" +
                                " no other connections have been set up so far")

                            val bgpPair = bgps.toList.head
                            val originalBgp = bgpPair._2

                            if (bgp.getLocalAS != originalBgp.getLocalAS) {
                                log.error("new BGP connections must have same AS")
                                return
                            }

                            if (bgps.contains(bgp.getId))
                                bgps.remove(bgp.getId)
                            bgps.put(bgp.getId, bgp)
                            bgpVty.setPeer(bgp.getLocalAS,
                                           IPv4Addr.fromIntIPv4(bgp.getPeerAddr),
                                           bgp.getPeerAS)
                        } else {
                            log.error("Ignoring BGP session {}, only one BGP "+
                                      "session per port is supported", bgp.getId)
                        }

                    case Stopping =>
                        stash()
                    case Disabled =>
                        stash()
                }

            case ModifyBgpSession(bgp) =>
                //TODO(abel) case not implemented (yet)
                phase match {
                    case _ => log.debug("message not implemented: ModifyBgpSession")
                }

            case RemoveBgpSession(bgpID) =>
                phase match {
                    case NotStarted =>
                        // This probably shouldn't happen. A BGP config is being
                        // removed, implying we knew about it - we shouldn't be
                        // NotStarted...
                        log.error("KillBgp not expected in phase NotStarted for port {}",
                            rport.id)
                    case Starting =>
                        stash()
                    case Started =>
                        // TODO(pino): Use bgpVty to remove this BGP and its routes
                        val Some(bgp) = bgps.remove(bgpID)
                        bgpVty.deletePeer(bgp.getLocalAS,
                                          IPv4Addr.fromIntIPv4(bgp.getPeerAddr))
                        // Remove all the flows for this BGP link
                        FlowController.getRef().tell(
                            FlowController.InvalidateFlowsByTag(FlowTagger.invalidateByBgp(bgpID)))

                        // If this is the last BGP for ths port, tear everything down.
                        if (bgps.size == 0) {
                            phase = Stopping
                            peerRoutes.values foreach {
                                routeId => deleteRoute(routeId)
                            }
                            peerRoutes.clear()
                            stopBGP()
                        }
                    case Stopping =>
                        stash()
                    case Disabled =>
                        stash()
                }

            case PortNetdevOpReply(netdevPort, PortOperation.Create, false, null, null) =>
                log.debug("PortNetdevOpReply - create, for port {}", rport.id)
                phase match {
                    case Starting =>
                        netdevPortReady(netdevPort)
                    case _ =>
                        log.error("PortNetdevOpReply expected only while " +
                            "Starting - we're now in {}", phase)
                }

            case PortNetdevOpReply(port, op, timeout, ex, tag) => // Do nothing
                log.debug("PortNetdevOpReply - unknown")
                log.debug("port: {}", port)
                log.debug("op: {}", op)
                log.debug("timeout: {}", timeout)
                log.debug("ex: {}", ex)
                log.debug("tag: {}", tag)

            case BGPD_READY =>
                log.debug("BGPD_READY for port {}", rport.id)
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
                        if (log.isDebugEnabled)
                            bgpVty.setDebug(isEnabled = true)

                        for (bgp <- bgps.values) {
                            // Use the bgpVty to set up sessions with all these peers
                            create(rport.portAddr.getAddress, bgp)
                        }

                        // BGP routes are added on:
                        // - create method
                        // - received from BGPListBuilder

                        log.debug("announcing we are BGPD_STATUS active")
                        context.system.eventStream.publish(
                            new BGPD_STATUS(rport.id, isActive = true))

                    case _ =>
                        log.error("BGP_READY expected only while " +
                            "Starting - we're now in {}", phase)
                }

            case BGPD_DEAD =>
                log.debug("BGPD_DEAD")
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
                        log.error("unexpected BGPD_DEAD message, now in {} state", phase)
                }

            case AdvertiseRoute(rt) =>
                log.debug("AdvertiseRoute: {}, phase: {}", rt, phase)
                phase match {
                    case NotStarted =>
                        log.error("AddRoute not expected in phase NotStarted")
                    case Starting =>
                        log.debug("AdvertiseRoute, starting phase")
                        stash()
                    case Started =>
                        log.debug("AdvertiseRoute, started phase")
                        adRoutes.add(rt)
                        val bgp = bgps.get(rt.getBgpId)
                        log.debug("AdvertiseRoute - bgp: {}", bgp)
                        bgp match {
                            case Some(b) =>
                                log.debug("AdvertiseRoute - we got a BGP object: {}", b)
                                val as = b.getLocalAS
                                bgpVty.setNetwork(as, rt.getNwPrefix.getHostAddress, rt.getPrefixLength)
                            case unknownObject =>
                                log.debug("AdvertiseRoute - we got an unknown object: {}", unknownObject)
                        }
                    case Stopping =>
                        log.debug("AdvertiseRoute, stopping phase")
                        //TODO(abel) do we need to stash the message when stopping?
                        stash()
                    case Disabled =>
                        stash()
                }

            case StopAdvertisingRoute(rt) =>
                log.debug("StopAdvertisingRoute: {}", rt)
                phase match {
                    case NotStarted =>
                        log.error("RemoveRoute not expected in phase NotStarted")
                    case Starting =>
                        stash()
                    case Started =>
                        adRoutes.remove(rt)
                        val bgp = bgps.get(rt.getBgpId)
                        bgp match {
                            case b: BGP => val as = b.getLocalAS
                            bgpVty.deleteNetwork(as, rt.getNwPrefix.getHostAddress, rt.getPrefixLength)
                            case _ =>
                        }
                    case Stopping =>
                        //TODO(abel) do we need to stash the message when stopping?
                        stash()
                    case Disabled =>
                        stash()
                }

            case AddPeerRoute(ribType, destination, gateway) =>
                log.debug("AddPeerRoute: {}, {}, {}", ribType, destination, gateway)
                phase match {
                    case NotStarted =>
                        log.error("AddPeerRoute not expected in phase NotStarted")
                    case Starting =>
                        stash()
                    case Started =>
                        if (peerRoutes.size > 100) {
                            /*
                             * TODO(abel) in order not to overwhelm the cluster,
                             * we will limit the max amount of routes we store
                             * at least for this version of the code.
                             * Note that peer routes, if not limited by bgpd
                             * or by the peer, can grow to hundreds of thousands
                             * of entries.
                             * I won't use a constant for this number because
                             * this problem should be tackled in a more elegant
                             * way and it's not used elsewhere.
                             */
                            log.error("Max amount of peer routes reached (100)")
                            return
                        }

                        val route = new Route()
                        route.setRouterId(rport.deviceID)
                        route.setDstNetworkAddr(destination.getAddress.toString)
                        route.setDstNetworkLength(destination.getPrefixLen)
                        route.setNextHopGateway(gateway.toString)
                        route.setNextHop(org.midonet.midolman.layer3.Route.NextHop.PORT)
                        route.setNextHopPort(rport.id)
                        val routeId = dataClient.routesCreateEphemeral(route)
                        peerRoutes.put(route, routeId)
                        log.debug("announcing we've added a peer route")
                        context.system.eventStream.publish(
                            new PEER_ROUTE_ADDED(rport.deviceID, route))
                    case Stopping =>
                        // ignore
                    case Disabled =>
                        // ignore
                }

            case RemovePeerRoute(ribType, destination, gateway) =>
                log.debug("RemovePeerRoute: {}, {}, {}", ribType, destination, gateway)
                phase match {
                    case NotStarted =>
                        log.error("AddPeerRoute not expected in phase NotStarted")
                    case Starting =>
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
                    case Stopping =>
                        // ignore
                    case Disabled =>
                        // ignore
                    case Disabling =>
                    // ignore
                    case _ =>
                }

            case PortActive(true) =>
                log.info("Port became active")
                portActive = true
                if (zookeeperActive)
                    enable()

            case ZookeeperActive(true) =>
                log.info("Reconnected to Zookeeper")
                zookeeperActive = true
                if (portActive)
                    enable()

            case PortActive(false) =>
                log.info("Port became inactive")
                portActive = false
                disable()

            case ZookeeperActive(false) =>
                log.info("Disconnected from Zookeeper")
                zookeeperActive = true
                disable()

        }
    }

    def enable() {
        if (!portActive || !zookeeperActive) {
            log.warning("enable() invoked in incorrect state zkActive:{} portActive:{}",
                        zookeeperActive, portActive)
            return
        }
        phase match {
            case Disabled =>
                if (bgps.size > 0) {
                    log.info("Enabling BGP link: {}", bgps.head._1)
                    startBGP()
                    phase = Starting
                } else {
                    phase = NotStarted
                    unstashAll()
                }
            case Disabling =>
                phase = Enabling
            case _ =>
                log.warning("enable() invoked while in the wrong state: {}", phase)
        }
    }

    def disable() {
        phase match {
            case Disabled =>
            case Disabling =>
            case Enabling =>
                phase = Disabled
            case _ if bgps.size > 0 =>
                log.info("Disabling BGP link: {}", bgps.head._1)
                phase = Disabling

                FlowController.getRef().tell(
                    FlowController.InvalidateFlowsByTag(FlowTagger.invalidateByBgp(bgps.head._1)))
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
                log.error("Exception: {}", e)
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
        log.debug("startBGP - begin for port {}", rport.id)

        // In Linux interface names can be at most 15 characters long
        val interfaces = List(
            BGP_NETDEV_PORT_NAME,
            BGP_NETDEV_PORT_MIRROR_NAME,
            BGP_VTY_PORT_NAME,
            BGP_VTY_PORT_MIRROR_NAME
        )

        interfaces.foreach(interface => if (interface.length > 15) {
            log.error("Interface name can be at most 15 characters: {}", interface)
            return
        })

        // If there was a bgpd running in a previous execution and wasn't
        // properly stopped, it may still be running -> kill it
        val bgpdPidFile = "/var/run/quagga/bgpd." + BGP_VTY_PORT + ".pid"
        val file = new File(bgpdPidFile)
        if (file.exists()) {
            val source = scala.io.Source.fromFile(bgpdPidFile)
            val lines = source.getLines()

            // we only expect one pid stored here
            if (lines.hasNext) {
                val cmdLine = "sudo kill -9 " + lines.next()
                ProcessHelper.executeCommandLine(cmdLine)
            }
        }

        val socketFile = new File(ZSERVE_API_SOCKET)
        val socketDir = socketFile.getParentFile
        if (!socketDir.exists()) {
            socketDir.mkdirs()
            // Set permission to let quagga daemons write.
            socketDir.setWritable(true, false)
        }

        if (socketFile.exists())
            socketFile.delete()

        socketAddress = new AfUnix.Address(socketFile.getAbsolutePath)
        zebra = ZebraServer(socketAddress, handler, rport.portAddr.getAddress,
                            BGP_NETDEV_PORT_MIRROR_NAME, selectLoop)

        // Does the namespace exist?
        var cmdLine = "sudo ip netns list"
        val foundNamespace = ProcessHelper.executeCommandLine(cmdLine)
            .consoleOutput.exists(line => line.contains(BGP_NETWORK_NAMESPACE))

        if (foundNamespace == false) {
            // Create the network namespace
            cmdLine = "sudo ip netns add " + BGP_NETWORK_NAMESPACE
            ProcessHelper.executeCommandLine(cmdLine)
        } else {
            // do not try to delete the old network namespace, because trying
            // to do so when there's a process still running or interfaces
            // still using it, it can lead to namespace corruption
        }

        // does the bgp interface already exist?
        cmdLine = "sudo ip link"
        val foundBgpPort = ProcessHelper.executeCommandLine(cmdLine)
            .consoleOutput.exists(line => line.contains(BGP_NETDEV_PORT_NAME))

        if (foundBgpPort) {
            // It already existed: delete it
            cmdLine = "sudo ip link delete " + BGP_NETDEV_PORT_NAME
            log.debug("cmdLine: {}", cmdLine)
            ProcessHelper.executeCommandLine(cmdLine)
        }

        // Create the interface bgpd will run on.
        cmdLine = "sudo ip link add name " + BGP_NETDEV_PORT_NAME +
            " type veth peer name " + BGP_NETDEV_PORT_MIRROR_NAME
        ProcessHelper.executeCommandLine(cmdLine)

        // Move the mirror interface inside the network namespace
        cmdLine = "sudo ip link set " + BGP_NETDEV_PORT_MIRROR_NAME +
            " netns " + BGP_NETWORK_NAMESPACE
        ProcessHelper.executeCommandLine(cmdLine)

        // Configure the mirror interface
        cmdLine = "sudo ip netns exec " + BGP_NETWORK_NAMESPACE +
            " ip link set dev " + BGP_NETDEV_PORT_MIRROR_NAME +
            " up" +
            " address " + rport.portMac.toString
        ProcessHelper.executeCommandLine(cmdLine)

        cmdLine = "sudo ip netns exec " + BGP_NETWORK_NAMESPACE +
            " ip addr add " + rport.portAddr.toString + " dev " +
            BGP_NETDEV_PORT_MIRROR_NAME
        ProcessHelper.executeCommandLine(cmdLine)

        // wake up loopback inside network namespace
        cmdLine = "sudo ip netns exec " + BGP_NETWORK_NAMESPACE +
            " ifconfig lo up"
        ProcessHelper.executeCommandLine(cmdLine)

        // wake up local port
        cmdLine = "sudo ip link set dev " + BGP_NETDEV_PORT_NAME + " up"
        ProcessHelper.executeCommandLine(cmdLine)

        // Add port to datapath
        DatapathController.getRef() !
            CreatePortNetdev(
                Ports.newNetDevPort(BGP_NETDEV_PORT_NAME), null)

        // VTY

        // Create the interface pair for VTY communication
        cmdLine = "sudo ip link add name " + BGP_VTY_PORT_NAME +
            " type veth peer name " + BGP_VTY_PORT_MIRROR_NAME
        ProcessHelper.executeCommandLine(cmdLine)

        // Move the mirror VTY interface inside the network namespace
        cmdLine = "sudo ip link set " + BGP_VTY_PORT_MIRROR_NAME +
            " netns " + BGP_NETWORK_NAMESPACE
        ProcessHelper.executeCommandLine(cmdLine)

        // Configure the mirror VTY interface
        cmdLine = "sudo ip netns exec " + BGP_NETWORK_NAMESPACE +
            " ip link set dev " + BGP_VTY_PORT_MIRROR_NAME +
            " up"
        ProcessHelper.executeCommandLine(cmdLine)

        cmdLine = "sudo ip netns exec " + BGP_NETWORK_NAMESPACE +
            " ip addr add " + BGP_VTY_MIRROR_IP +
            "/" + BGP_VTY_MASK_LEN + " dev " +
            BGP_VTY_PORT_MIRROR_NAME
        ProcessHelper.executeCommandLine(cmdLine)

        // Wake up local vty interface
        cmdLine = "sudo ip link set dev " + BGP_VTY_PORT_NAME + " up"
        ProcessHelper.executeCommandLine(cmdLine)

        // Create a bridge for VTY communication
        cmdLine = "sudo brctl addbr  " + BGP_VTY_BRIDGE_NAME
        ProcessHelper.executeCommandLine(cmdLine)

        // Add VTY interface to VTY bridge
        cmdLine = "sudo brctl addif " + BGP_VTY_BRIDGE_NAME +
            " " + BGP_VTY_PORT_NAME
        ProcessHelper.executeCommandLine(cmdLine)

        // Set up bridge as local VTY interface
        cmdLine = "sudo ip addr add " + BGP_VTY_LOCAL_IP +
            "/" + BGP_VTY_MASK_LEN + " dev " +
            BGP_VTY_BRIDGE_NAME
        ProcessHelper.executeCommandLine(cmdLine)

        // Bring up bridge interface for VTY
        cmdLine = "sudo ip link set dev " + BGP_VTY_BRIDGE_NAME + " up"
        ProcessHelper.executeCommandLine(cmdLine)

        log.debug("startBGP - end")
    }

    private def stopBGP() {
        log.debug("stopBGP - begin")

        if (bgpdProcess != null) {
            bgpdProcess.stop()
        }

        context.stop(zebra)

        // delete vty interface pair
        var cmdLine = "sudo ip link del " + BGP_VTY_PORT_NAME
        ProcessHelper.executeCommandLine(cmdLine)

        // get vty bridge down
        cmdLine = "sudo ip link set dev " + BGP_VTY_BRIDGE_NAME + " down"
        ProcessHelper.executeCommandLine(cmdLine)

        // delete vty bridge
        cmdLine = "sudo brctl delbr " + BGP_VTY_BRIDGE_NAME
        ProcessHelper.executeCommandLine(cmdLine)

        // delete bgp interface pair
        cmdLine = "sudo ip link del " + BGP_NETDEV_PORT_NAME
        ProcessHelper.executeCommandLine(cmdLine)

        // delete network namespace
        cmdLine = "sudo ip netns del " + BGP_NETWORK_NAMESPACE
        ProcessHelper.executeCommandLine(cmdLine)

        // Delete port from datapath
        DatapathController.getRef() !
            CreatePortNetdev(
                Ports.newNetDevPort(BGP_NETDEV_PORT_NAME), null)

        log.debug("announcing BGPD_STATUS inactive")
        context.system.eventStream.publish(
            new BGPD_STATUS(rport.id, isActive = false))

        self ! BGPD_DEAD

        log.debug("stopBGP - end")
    }

    private def netdevPortReady(newPort: NetDevPort) {
        log.debug("begin")

        // The internal port is ready. Set up the flows
        if (bgps.size <= 0) {
            log.warning("No BGPs configured for this port: {}", newPort)
            return
        }

        // Of all the possible BGPs configured for this port, we only consider
        // the very first one to create the BGPd process
        val bgpPair = bgps.toList.head
        val bgp = bgpPair._2
        setBGPFlows(newPort.getPortNo.shortValue(), bgp, rport)

        bgpdProcess = new BgpdProcess(self, BGP_VTY_PORT,
            rport.portAddr.getAddress.toString, socketAddress,
            BGP_NETWORK_NAMESPACE)
        val didStart = bgpdProcess.start()
        if (didStart) {
            self ! BGPD_READY
            log.debug("bgpd process did start")
        } else {
            log.debug("bgpd process did not start")
        }

        log.debug("end")
    }

    def create(localAddr: IPAddr, bgp: BGP) {
        log.debug("create - begin")
        bgpVty.setAs(bgp.getLocalAS)
        bgpVty.setLocalNw(bgp.getLocalAS, localAddr)
        bgpVty.setPeer(bgp.getLocalAS, 
                       IPv4Addr.fromIntIPv4(bgp.getPeerAddr),
                       bgp.getPeerAS)

        for (adRoute <- adRoutes) {
            // If an adRoute is already configured in bgp, it will be
            // silently ignored
            bgpVty.setNetwork(bgp.getLocalAS, adRoute.getNwPrefix.getHostAddress,
                adRoute.getPrefixLength)
            adRoutes.add(adRoute)
            log.debug("added adRoute: {}", adRoute)
        }
    }

    def setBGPFlows(localPortNum: Short, bgp: BGP,
                    bgpPort: ExteriorRouterPort) {

        log.debug("setBGPFlows - begin")

        // Set the BGP ID in a set to use as a tag for the datapath flows
        // For some reason AddWilcardFlow needs a mutable set so this
        // construction is needed although I'm sure you can find a better one.
        val bgpTagSet = Set[Any](FlowTagger.invalidateByBgp(bgp.getId))

        // TCP4:->179 bgpd->link
        var wildcardMatch = new WildcardMatch()
            .setInputPortNumber(localPortNum)
            .setEtherType(IPv4.ETHERTYPE)
            .setNetworkProtocol(TCP.PROTOCOL_NUMBER)
            .setNetworkSource(bgpPort.portAddr.getAddress)
            .setNetworkDestination(IPv4Addr.fromIntIPv4(bgp.getPeerAddr.toHostAddress))
            .setTransportDestination(BGP_TCP_PORT)

        var wildcardFlow = WildcardFlow(
            wcmatch = wildcardMatch,
            actions = List(new FlowActionOutputToVrnPort(bgpPort.id)))


        DatapathController.getRef.tell(AddVirtualWildcardFlow(
            wildcardFlow, Set.empty, bgpTagSet))

        // TCP4:179-> bgpd->link
        wildcardMatch = new WildcardMatch()
            .setInputPortNumber(localPortNum)
            .setEtherType(IPv4.ETHERTYPE)
            .setNetworkProtocol(TCP.PROTOCOL_NUMBER)
            .setNetworkSource(bgpPort.portAddr.getAddress)
            .setNetworkDestination(IPv4Addr.fromIntIPv4(bgp.getPeerAddr.toHostAddress))
            .setTransportSource(BGP_TCP_PORT)

        wildcardFlow = WildcardFlow(
            wcmatch = wildcardMatch,
            actions = List(new FlowActionOutputToVrnPort(bgpPort.id)))

        DatapathController.getRef.tell(AddVirtualWildcardFlow(
            wildcardFlow, Set.empty, bgpTagSet))

        // TCP4:->179 link->bgpd
        wildcardMatch = new WildcardMatch()
            .setInputPortUUID(bgpPort.id)
            .setEtherType(IPv4.ETHERTYPE)
            .setNetworkProtocol(TCP.PROTOCOL_NUMBER)
            .setNetworkSource(IPv4Addr.fromIntIPv4(bgp.getPeerAddr.toHostAddress))
            .setNetworkDestination(bgpPort.portAddr.getAddress)
            .setTransportDestination(BGP_TCP_PORT)

        wildcardFlow = WildcardFlow(
            wcmatch = wildcardMatch,
            actions = List(FlowActions.output(localPortNum)))

        DatapathController.getRef.tell(AddVirtualWildcardFlow(
            wildcardFlow, Set.empty, bgpTagSet))

        // TCP4:179-> link->bgpd
        wildcardMatch = new WildcardMatch()
            .setInputPortUUID(bgpPort.id)
            .setEtherType(IPv4.ETHERTYPE)
            .setNetworkProtocol(TCP.PROTOCOL_NUMBER)
            .setNetworkSource(IPv4Addr.fromIntIPv4(bgp.getPeerAddr.toHostAddress))
            .setNetworkDestination(bgpPort.portAddr.getAddress)
            .setTransportSource(BGP_TCP_PORT)

        wildcardFlow = WildcardFlow(
            wcmatch = wildcardMatch,
            actions = List(FlowActions.output(localPortNum)))

        DatapathController.getRef.tell(AddVirtualWildcardFlow(
            wildcardFlow, Set.empty, bgpTagSet))

        // ARP bgpd->link
        wildcardMatch = new WildcardMatch()
            .setInputPortNumber(localPortNum)
            .setEtherType(ARP.ETHERTYPE)

        wildcardFlow = WildcardFlow(
            wcmatch = wildcardMatch,
            actions = List(new FlowActionOutputToVrnPort(bgpPort.id)))

        DatapathController.getRef.tell(AddVirtualWildcardFlow(
            wildcardFlow, Set.empty, bgpTagSet))

        // ARP link->bgpd, link->midolman
        // Both MM and bgpd need to know the peer's MAC address, so we install
        // a wildcard flow that sends the ARP replies to both MM and bgpd.
        wildcardMatch = new WildcardMatch()
            .setInputPortUUID(bgpPort.id)
            .setEtherType(ARP.ETHERTYPE)
            .setEthernetDestination(bgpPort.portMac)
            // nwProto is overloaded in WildcardMatch to store the arp op type.
            .setNetworkProtocol(ARP.OP_REPLY.toByte)
            // nwSrc/nwDst are overloaded to store the arp sip and tip.
            .setNetworkSource(IPv4Addr.fromIntIPv4(bgp.getPeerAddr.toHostAddress))
            .setNetworkDestination(bgpPort.portAddr.getAddress)

        wildcardFlow = WildcardFlow(
            wcmatch = wildcardMatch,
            actions = List(FlowActions.output(localPortNum),
                            new FlowActionUserspace)) // Netlink Pid filled by datapath controller

        DatapathController.getRef.tell(AddVirtualWildcardFlow(
            wildcardFlow, Set.empty, bgpTagSet))

        // ICMP4 bgpd->link
        wildcardMatch = new WildcardMatch()
            .setInputPortNumber(localPortNum)
            .setEtherType(IPv4.ETHERTYPE)
            .setNetworkProtocol(ICMP.PROTOCOL_NUMBER)
            .setNetworkSource(bgpPort.portAddr.getAddress)
            .setNetworkDestination(IPv4Addr.fromIntIPv4(bgp.getPeerAddr.toHostAddress))

        wildcardFlow = WildcardFlow(
            wcmatch = wildcardMatch,
            actions = List(new FlowActionOutputToVrnPort(bgpPort.id)))

        DatapathController.getRef.tell(AddVirtualWildcardFlow(
            wildcardFlow, Set.empty, bgpTagSet))

        // ICMP4 link->bgpd
        wildcardMatch = new WildcardMatch()
            .setInputPortUUID(bgpPort.id)
            .setEtherType(IPv4.ETHERTYPE)
            .setNetworkProtocol(ICMP.PROTOCOL_NUMBER)
            .setNetworkSource(IPv4Addr.fromIntIPv4(bgp.getPeerAddr.toHostAddress))
            .setNetworkDestination(bgpPort.portAddr.getAddress)

        wildcardFlow = WildcardFlow(
            wcmatch = wildcardMatch,
            actions = List(FlowActions.output(localPortNum)))

        DatapathController.getRef.tell(AddVirtualWildcardFlow(
            wildcardFlow, Set.empty, bgpTagSet))

        log.debug("setBGPFlows - end")
    }

}
