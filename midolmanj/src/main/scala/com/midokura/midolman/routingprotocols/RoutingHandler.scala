/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midolman.routingprotocols

import akka.actor.{UntypedActorWithStash, ActorLogging}
import com.google.inject.Inject
import com.midokura.midonet.cluster.{Client, DataClient}
import com.midokura.midolman.topology.VirtualTopologyActor
import java.util.UUID
import com.midokura.midonet.cluster.client.{Port, ExteriorRouterPort, BGPListBuilder}
import com.midokura.midonet.cluster.data.{AdRoute, BGP}
import collection.mutable
import java.io.{IOException, File}
import org.newsclub.net.unix.{AFUNIXSocketAddress, AFUNIXServerSocket}
import com.midokura.quagga.{BgpConnection, BgpVtyConnection,
                            ZebraServer, ZebraServerImpl}
import com.midokura.midolman.{PortOperation, DatapathController, FlowController}
import com.midokura.midolman.util.Sudo
import akka.util.Duration
import com.midokura.sdn.dp.Ports
import com.midokura.sdn.flows.{WildcardFlow, WildcardMatch}
import com.midokura.packets._
import com.midokura.midolman.datapath.FlowActionOutputToVrnPort
import com.midokura.sdn.dp.flows.{FlowActionUserspace, FlowActions}
import com.midokura.sdn.dp.ports.InternalPort
import com.midokura.midolman.topology.VirtualTopologyActor.PortRequest
import com.midokura.midolman.DatapathController.{CreatePortInternal, PortInternalOpReply}
import java.util.concurrent.TimeUnit
import com.midokura.midolman.FlowController.AddWildcardFlow
import com.midokura.quagga.ZebraProtocol.RIBType

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
 * RoutingHandlers for different virtual ports of the same router.
 *
 * @param rport
 * @param bgpIdx
 */
class RoutingHandler(var rport: ExteriorRouterPort, val bgpIdx: Int)
    extends UntypedActorWithStash with ActorLogging {

    import context._

    @Inject
    var dataClient: DataClient = null
    @Inject
    var client: Client = null

    private final val BGP_INTERNAL_PORT_NAME: String =
        "midobgp%d".format(bgpIdx)
    private final val ZSERVE_API_SOCKET =
        "/var/run/quagga/zserv%d.api".format(bgpIdx)
    private final val BGP_VTY_PORT: Int = 26050 + bgpIdx
    private final val BGP_TCP_PORT: Short = 179

    private var zebra: ZebraServerImpl = null
    private var bgpVty: BgpConnection = null

    private val bgps = mutable.Map[UUID, BGP]()
    private val adRoutes = mutable.Set[AdRoute]()
    private var internalPort: InternalPort = null

    private case class NewBgpSession(bgp: BGP)
    private case class ModifyBgpSession(bgp: BGP)
    private case class RemoveBgpSession(bgpID: UUID)
    private case class AdvertiseRoute(route: AdRoute)
    private case class StopAdvertisingRoute(route: AdRoute)
    override def preStart() {
        super.preStart()
        // Watch the BGP session information for this port.
        // In the future we may also watch for session configurations of
        // other routing protocols.
        client.getPortBGPList(rport.id, new BGPListBuilder {
            def addBGP(bgp: BGP) {
                self ! NewBgpSession(bgp)
            }

            def updateBGP(bgp: BGP) {
                self ! ModifyBgpSession(bgp)
            }

            def removeBGP(bgpID: UUID) {
                self ! RemoveBgpSession(bgpID)
            }

            def addAdvertisedRoute(route: AdRoute) = {
                self ! AdvertiseRoute(route)
            }

            def removeAdvertisedRoute(route: AdRoute) {
                self ! StopAdvertisingRoute(route)
            }
        })

        // Subscribe to the VTA for updates to the Port configuration.
        VirtualTopologyActor.getRef() ! PortRequest(rport.id, true)
    }

    private case class AddPeerRoute(ribType: RIBType.Value,
                                   destination: IntIPv4, gateway: IntIPv4)
    private case class RemovePeerRoute(ribType: RIBType.Value,
                                       destination: IntIPv4, gateway: IntIPv4)
    private val handler = new ZebraProtocolHandler {
        def addRoute(ribType: RIBType.Value, destination: IntIPv4,
                     gateway: IntIPv4) {
            self ! AddPeerRoute(ribType, destination, gateway)
        }

        def removeRoute(ribType: RIBType.Value, destination: IntIPv4,
                        gateway: IntIPv4) {
            self ! RemovePeerRoute(ribType, destination, gateway)
        }
    }
    /**
     * This actor can be in these phases:
     * NotStarted: bgp has not been started (no known bgp configs on the port)
     * Starting: waiting for bgpd to come up (and maybe the internal port)
     * Started: zebra, bgpVty and bgpd are up
     * Stopping: we're in the process of stopping the bgpd
     *
     * The transitions are:
     * NotStarted -> Starting : when we learn about the first BGP config
     * Starting -> Started : when the bgpd has come up
     * Started -> Starting : if the bgpd crashes or needs a restart
     * Started -> Stopping : when all the bgp configs have been removed
     */
    object Phase extends Enumeration {
        type Phase = Value
        val NotStarted = Value("NotStarted")
        val Starting = Value("Starting")
        val Started = Value("Started")
        val Stopping = Value("Stopping")
    }
    import Phase._
    var phase = NotStarted

    private case class BGPD_READY()
    private case class BGPD_DEAD()

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
                phase match {
                    case NotStarted =>
                        // This must be the first bgp we learn about.
                        bgps.put(bgp.getId, bgp)
                        startBGP
                        phase = Starting
                    case Starting =>
                        stash()
                    case Started =>
                    // TODO(pino): use vtyBgp to configure the bgp session.
                    // TODO(pino): distinguish between new and modified bgp.
                    case Stopping =>
                        stash()
                }

            case ModifyBgpSession(bgp) => // TODO(pino): implement me!

            case PortInternalOpReply(iport, PortOperation.Create,
            false, null, null) =>
                phase match {
                    case Starting =>
                        internalPortReady(iport)
                    case _ =>
                        log.error("PortInternalOpReply expected only while " +
                            "Starting - we're now in {}", phase)
                }

            case PortInternalOpReply(_, _, _, _, _) => // Do nothing

            case BGPD_READY =>
                phase match {
                    case Starting =>
                        phase = Started
                        unstashAll()
                        for (bgp <- bgps.values) {
                            // Use the bgpVty to set up sessions with all these peers
                            create(rport.nwAddr(), bgp)
                        }
                        for (route <- adRoutes) {
                            // Use the bgpVty to add all these routes/networks
                        }
                    case _ =>
                        log.error("BGP_READY expected only while " +
                            "Starting - we're now in {}", phase)
                }

            case RemoveBgpSession(bgpID) =>
                phase match {
                    case NotStarted =>
                        // This probably shouldn't happen. A BGP config is being
                        // removed, implying we knew about it - we shouldn't be
                        // NotStarted...
                        log.error("KillBgp not expected in phase NotStarted")
                    case Starting =>
                        stash()
                    case Started =>
                        // TODO(pino): Use bgpVty to remove this BGP and its routes
                        val bgp = bgps.remove(bgpID)
                        // Remove all the flows for this BGP link
                        FlowController.getRef().tell(
                            FlowController.InvalidateFlowsByTag(bgpID))

                        // If this is the last BGP for ths port, tear everything down.
                        if (bgps.size == 0) {
                            phase = Stopping
                            stopBGP
                        }
                    case Stopping =>
                        stash()
                }

            case BGPD_DEAD =>
                phase match {
                    case Stopping =>
                        phase = NotStarted
                        unstashAll()
                    case _ =>
                        log.error("BGP_DEAD expected only while " +
                            "Stopping - we're now in {}", phase)
                }

            case AdvertiseRoute(rt) =>
                phase match {
                    case NotStarted =>
                        log.error("AddRoute not expected in phase NotStarted")
                    case Starting =>
                        stash()
                    case Started =>
                        adRoutes.add(rt)
                    // TODO(pino): use bgpVty to advertise the route
                    case Stopping =>
                        stash()
                }

            case StopAdvertisingRoute(rt) =>
                phase match {
                    case NotStarted =>
                        log.error("RemoveRoute not expected in phase NotStarted")
                    case Starting =>
                        stash()
                    case Started =>
                        adRoutes.remove(rt)
                    // TODO(pino): use bgpVty to stop advertising the route
                    case Stopping =>
                        stash()
                }

            case AddPeerRoute(ribType, destination, gateway) =>
            // TODO(pino): implement me!

            case RemovePeerRoute(ribType, destination, gateway) =>
            // TODO(pino): implement me!
        }
    }

    private def startBGP() = {
        val socketFile = new File(ZSERVE_API_SOCKET)
        val socketDir = socketFile.getParentFile
        if (!socketDir.exists()) {
            socketDir.mkdirs()
            // Set permission to let quagga daemons write.
            socketDir.setWritable(true, false)
        }

        if (socketFile.exists())
            socketFile.delete()

        val server = AFUNIXServerSocket.newInstance()
        val address = new AFUNIXSocketAddress(socketFile)

        zebra = new ZebraServerImpl(
            server, address, handler, rport.nwAddr(), BGP_INTERNAL_PORT_NAME)
        zebra.start()

        bgpVty = new BgpVtyConnection(
            addr = "localhost",
            port = BGP_VTY_PORT,
            password = "zebra_password")

        // Create the interface bgpd will run on.
        log.info("Adding internal port {} for BGP link", BGP_INTERNAL_PORT_NAME)
        DatapathController.getRef() !
            CreatePortInternal(
                Ports.newInternalPort(BGP_INTERNAL_PORT_NAME)
                    .setAddress(rport.portMac.getAddress), null)
    }

    private def stopBGP() = {
        // Kill our bgpd
        try {
            Sudo.sudoExec("killall bgpd")
        } catch {
            case e: InterruptedException =>
                log.warning("exception killing bgpd: ", e)
        }
        // TODO(pino): kill the bgpVty and zebra
        // TODO(pino): figure out when the bgpd is dead.
        // For now, just schedule a DEAD message in 2 seconds.
        system.scheduler.scheduleOnce(
            Duration.create(3000, TimeUnit.MILLISECONDS),
            self,
            BGPD_DEAD)
    }

    private def internalPortReady(newPort: InternalPort) {
        internalPort = newPort
        // The internal port is ready. Set up the flows
        for (bgp <- bgps.values)
            setBGPFlows(internalPort.getPortNo.shortValue(), bgp, rport)
        // And start bgpd
        Runtime.getRuntime.exec("sudo /usr/lib/quagga/bgpd")
        // TODO(pino): need to pass BGP_VTY_PORT in the -P option
        // TODO(pino): neet to pass BGP_TCP_PORT in the -p option

        //TODO(abel) make into a future
        Runtime.getRuntime addShutdownHook new Thread {
            new Runnable() {
                override def run() {
                    log.info("killing bgpd")
                    // Calling killall because bgpdProcess.destroy()
                    // doesn't seem to work.
                    try {
                        Sudo.sudoExec("killall bgpd")
                    } catch {
                        case e: IOException =>
                            log.warning("killall bgpd", e)
                        case e: InterruptedException =>
                            log.warning("killall bgpd", e)
                    }
                }
            }
        }
        // TODO(pino): figure out when the bgpd is ready.
        // For now, just schedule a READY message in 2 seconds.
        system.scheduler.scheduleOnce(
            Duration.create(3000, TimeUnit.MILLISECONDS),
            self,
            BGPD_READY)
    }

    // TODO(pino): remove these watchers after assimilating what they do.
    /*private class AdRouteWatcher(val localAS: Int, val adRouteUUID: UUID,
                                 val oldConfig: AdRouteConfig,
                                 val adRouteZk: AdRouteZkManager)
        extends Runnable {
        override def run() {
            // Whether this event is update or delete, we have to
            // delete the old config first.
            deleteNetwork(localAS, oldConfig.nwPrefix.getHostAddress,
                oldConfig.prefixLength)
            try {
                val adRoute = adRouteZk.get(adRouteUUID, this)
                if (adRoute != null) {
                    setNetwork(localAS, adRoute.nwPrefix.getHostAddress,
                        adRoute.prefixLength)
                }
            } catch {
                case e: NoStatePathException => {
                    log.warn("AdRouteWatcher: node already deleted")
                }
            }
        }
    }

    private class BgpWatcher(val localAddr: InetAddress, var bgpUUID: UUID,
                             var oldConfig: BGP, val adRoutes: Set[UUID],
                             val bgpZk: BgpZkManager,
                             val adRouteZk: AdRouteZkManager)
        extends Runnable {
        override def run() {
            // Compare the length of adRoutes and only handle
            // adRoute events when routes are added.
            try {
                if (adRoutes.size < adRouteZk.list(bgpUUID).size) {
                    val bgp = bgpZk.getBGP(bgpUUID, this)
                    if (bgp != null) {
                        this.bgpUUID = bgpUUID
                        this.oldConfig = bgp
                        create(localAddr, bgpUUID, bgp)
                    }
                }
            } catch {
                case e: NoStatePathException => {
                    log.warn("BgpWatcher: node already deleted")
                    deleteAs(oldConfig.getLocalAS)
                }
            }
        }
    }*/

    def create(localAddr: IntIPv4, bgp: BGP) {
        bgpVty.setAs(bgp.getLocalAS)
        bgpVty.setLocalNw(bgp.getLocalAS, localAddr)
        bgpVty.setPeer(bgp.getLocalAS, bgp.getPeerAddr, bgp.getPeerAS)

        //val adRoutes = Set[UUID]()
        //val bgpWatcher = new BgpWatcher(localAddr, bgpUUID, bgp, adRoutes,
        //    bgpZk, adRouteZk)

        for (adRoute <- adRoutes) {
            bgpVty.setNetwork(bgp.getLocalAS, adRoute.getNwPrefix.getHostAddress,
                adRoute.getPrefixLength)
            adRoutes.add(adRoute)
            // Register AdRouteWatcher.
            //adRouteZk.get(adRouteUUID,
            //    new AdRouteWatcher(bgp.getLocalAS, adRouteUUID, adRoute,
            //        adRouteZk))
        }
    }

    def setBGPFlows(localPortNum: Short, bgp: BGP,
                    bgpPort: ExteriorRouterPort) {

        // Set the BGP ID in a set to use as a tag for the datapath flows
        // For some reason AddWilcardFlow needs a mutable set so this
        // construction is needed although I'm sure you can find a better one.
        val bgpTagSet = Set[AnyRef](bgp.getId)

        // TCP4:->179 bgpd->link
        var wildcardMatch = new WildcardMatch()
            .setInputPortNumber(localPortNum)
            .setEtherType(IPv4.ETHERTYPE)
            .setNetworkProtocol(TCP.PROTOCOL_NUMBER)
            .setNetworkSource(bgpPort.portAddr)
            .setNetworkDestination(bgp.getPeerAddr)
            .setTransportDestination(BGP_TCP_PORT)

        var wildcardFlow = new WildcardFlow()
            .setMatch(wildcardMatch)
            .addAction(new FlowActionOutputToVrnPort(bgpPort.id))

        DatapathController.getRef.tell(AddWildcardFlow(
            wildcardFlow, None, null, bgpTagSet))

        // TCP4:179-> bgpd->link
        wildcardMatch = new WildcardMatch()
            .setInputPortNumber(localPortNum)
            .setEtherType(IPv4.ETHERTYPE)
            .setNetworkProtocol(TCP.PROTOCOL_NUMBER)
            .setNetworkSource(bgpPort.portAddr)
            .setNetworkDestination(bgp.getPeerAddr)
            .setTransportSource(BGP_TCP_PORT)

        wildcardFlow = new WildcardFlow()
            .setMatch(wildcardMatch)
            .addAction(new FlowActionOutputToVrnPort(bgpPort.id))

        DatapathController.getRef.tell(AddWildcardFlow(
            wildcardFlow, None, null, bgpTagSet))

        // TCP4:->179 link->bgpd
        wildcardMatch = new WildcardMatch()
            .setInputPortUUID(bgpPort.id)
            .setEtherType(IPv4.ETHERTYPE)
            .setNetworkProtocol(TCP.PROTOCOL_NUMBER)
            .setNetworkSource(bgp.getPeerAddr)
            .setNetworkDestination(bgpPort.portAddr)
            .setTransportDestination(BGP_TCP_PORT)

        wildcardFlow = new WildcardFlow()
            .setMatch(wildcardMatch)
            .addAction(FlowActions.output(localPortNum))

        DatapathController.getRef.tell(AddWildcardFlow(
            wildcardFlow, None, null, bgpTagSet))

        // TCP4:179-> link->bgpd
        wildcardMatch = new WildcardMatch()
            .setInputPortUUID(bgpPort.id)
            .setEtherType(IPv4.ETHERTYPE)
            .setNetworkProtocol(TCP.PROTOCOL_NUMBER)
            .setNetworkSource(bgp.getPeerAddr)
            .setNetworkDestination(bgpPort.portAddr)
            .setTransportSource(BGP_TCP_PORT)

        wildcardFlow = new WildcardFlow()
            .setMatch(wildcardMatch)
            .addAction(FlowActions.output(localPortNum))

        DatapathController.getRef.tell(AddWildcardFlow(
            wildcardFlow, None, null, bgpTagSet))

        // ARP bgpd->link
        wildcardMatch = new WildcardMatch()
            .setInputPortNumber(localPortNum)
            .setEtherType(ARP.ETHERTYPE)

        wildcardFlow = new WildcardFlow()
            .setMatch(wildcardMatch)
            .addAction(new FlowActionOutputToVrnPort(bgpPort.id))

        DatapathController.getRef.tell(AddWildcardFlow(
            wildcardFlow, None, null, bgpTagSet))

        // ARP link->bgpd, link->midolman
        // TODO(abel) send ARP from link to both ports only if it's an ARP reply
        wildcardMatch = new WildcardMatch()
            .setInputPortNumber(localPortNum)
            .setEtherType(ARP.ETHERTYPE)

        wildcardFlow = new WildcardFlow()
            .setMatch(wildcardMatch)
            .addAction(FlowActions.output(localPortNum))
            .addAction(new FlowActionUserspace)

        DatapathController.getRef.tell(AddWildcardFlow(
            wildcardFlow, None, null, bgpTagSet))

        // ICMP4 bgpd->link
        wildcardMatch = new WildcardMatch()
            .setInputPortNumber(localPortNum)
            .setEtherType(IPv4.ETHERTYPE)
            .setNetworkProtocol(ICMP.PROTOCOL_NUMBER)
            .setNetworkSource(bgpPort.portAddr)
            .setNetworkDestination(bgp.getPeerAddr)

        wildcardFlow = new WildcardFlow()
            .setMatch(wildcardMatch)
            .addAction(new FlowActionOutputToVrnPort(bgpPort.id))

        DatapathController.getRef.tell(AddWildcardFlow(
            wildcardFlow, None, null, bgpTagSet))

        // ICMP4 link->bgpd
        wildcardMatch = new WildcardMatch()
            .setInputPortUUID(bgpPort.id)
            .setEtherType(IPv4.ETHERTYPE)
            .setNetworkProtocol(ICMP.PROTOCOL_NUMBER)
            .setNetworkSource(bgp.getPeerAddr)
            .setNetworkDestination(bgpPort.portAddr)

        wildcardFlow = new WildcardFlow()
            .setMatch(wildcardMatch)
            .addAction(FlowActions.output(localPortNum))

        DatapathController.getRef.tell(AddWildcardFlow(
            wildcardFlow, None, null, bgpTagSet))
    }

}
