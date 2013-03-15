// Copyright 2012 Midokura Inc.

package org.midonet.midolman.simulation

import collection.mutable
import collection.JavaConversions._
import java.util.UUID

import akka.actor.{ActorRef, ActorSystem}
import akka.dispatch.{ExecutionContext, Future, Promise}
import akka.pattern.ask
import akka.util.duration._

import org.midonet.cache.Cache
import org.midonet.cluster.client._
import org.midonet.midolman.{DatapathController, FlowController}
import org.midonet.midolman.DatapathController.{EmitGeneratedPacket, SendPacket}
import org.midonet.midolman.FlowController.{AddWildcardFlow, DiscardPacket}
import org.midonet.midolman.datapath.{FlowActionOutputToVrnPort,
                                      FlowActionOutputToVrnPortSet}
import org.midonet.midolman.logging.LoggerFactory
import org.midonet.midolman.rules.RuleResult.{Action => RuleAction}
import org.midonet.midolman.topology._
import org.midonet.midolman.topology.VirtualTopologyActor._
import org.midonet.odp.flows._
import org.midonet.packets.{Ethernet, ICMP, IPv4, IPv4Addr, IPv6Addr, TCP, UDP}
import org.midonet.sdn.flows.{WildcardFlow, WildcardMatch}


object Coordinator {
    trait Action

    // ErrorDropAction is used to signal that the Drop is being requested
    // because of an error, not because the virtual topology justifies it.
    // The resulting Drop rule may be temporary to allow retrying.
    case class ErrorDropAction() extends Action
    case class DropAction() extends Action
    // NotIPv4Action implies a DROP flow. However, it differs from DropAction
    // in that the installed flow match can have all fields >L2 wildcarded.
    // TODO(pino): make the installed flow computation smarter so that it
    // TODO:       wildcards any field that wasn't used by the simulation. Then
    // TODO:       remove NotIPv4Action
    case class NotIPv4Action() extends Action
    case class ConsumedAction() extends Action
    trait ForwardAction extends Action
    case class ToPortAction(outPort: UUID) extends ForwardAction
    case class ToPortSetAction(portSetID: UUID) extends ForwardAction

    trait Device {
        /**
         * Process a packet described by the given match object. Note that the
         * Ethernet packet is the one originally ingressed the virtual network
         * - it does not reflect the changes made by other devices' handling of
         * the packet (whereas the match object does).
         *
         * @param pktContext The context for the simulation of this packet's
         * traversal of the virtual network. Use the context to subscribe
         * for notifications on the removal of any resulting flows, or to tag
         * any resulting flows for indexing.
         * @return An instance of Action that reflects what the device would do
         * after handling this packet (e.g. drop it, consume it, forward it).
         */
        def process(pktContext: PacketContext)
                   (implicit ec: ExecutionContext,
                    actorSystem: ActorSystem): Future[Action]
    }
}

/**
 * Coordinator object to simulate one packet traversing the virtual network.
 *
 * @param origMatch
 * @param origEthernetPkt
 * @param cookie
 * @param generatedPacketEgressPort Only used if this packet was generated
 *                                  by a virtual device. It's the ID of
 *                                  the port via which the packet
 *                                  egresses the device.
 * @param expiry The time (as returned by currentTime) at which to expire
 *               this simulation.
 * @param connectionCache The Cache to use for connection tracking.
 * @param ec
 * @param actorSystem
 */
class Coordinator(val origMatch: WildcardMatch,
                  val origEthernetPkt: Ethernet,
                  val cookie: Option[Int],
                  val generatedPacketEgressPort: Option[UUID],
                  val expiry: Long,
                  val connectionCache: Cache,
                  val parentCookie: Option[Int])
                 (implicit val ec: ExecutionContext,
                  val actorSystem: ActorSystem) extends Runnable {
    import Coordinator._

    val log = LoggerFactory.getSimulationAwareLog(this.getClass)(actorSystem.eventStream)
    private val datapathController = DatapathController.getRef(actorSystem)
    private val flowController = FlowController.getRef(actorSystem)
    private val virtualTopologyManager = VirtualTopologyActor.getRef(actorSystem)
    private val TEMPORARY_DROP_MILLIS = 5 * 1000
    private val IDLE_EXPIRATION_MILLIS = 60 * 1000
    private val RETURN_FLOW_EXPIRATION_MILLIS = 60 * 1000
    private val MAX_DEVICES_TRAVERSED = 12

    // Used to detect loops: devices simulated (with duplicates).
    private var numDevicesSimulated = 0
    private val devicesSimulated = mutable.Map[UUID, Int]()
    implicit private val pktContext = new PacketContext(cookie, origEthernetPkt, expiry,
            connectionCache, generatedPacketEgressPort.isDefined, parentCookie)
    pktContext.setMatch(origMatch)

    private def dropFlow(temporary: Boolean, withTags: Boolean = false) {
        // If the packet is from the datapath, install a temporary Drop flow.
        // Note: a flow with no actions drops matching packets.
        pktContext.freeze()
        cookie match {
            case Some(_) =>
                val wflow = new WildcardFlow().setMatch(origMatch)
                if (temporary)
                    wflow.setHardExpirationMillis(TEMPORARY_DROP_MILLIS)
                else
                    wflow.setIdleExpirationMillis(IDLE_EXPIRATION_MILLIS)

                datapathController.tell(
                    AddWildcardFlow(wflow, cookie, null,
                        pktContext.getFlowRemovedCallbacks,
                        if (withTags) pktContext.getFlowTags else null))

            case None => // Internally-generated packet. Do nothing.
                pktContext.getFlowRemovedCallbacks foreach { cb => cb.call() }
        }
    }

    override def run: Unit = simulate

    /**
     * Simulate the packet moving through the virtual topology. The packet
     * begins its journey through the virtual topology in one of these ways:
     * 1) it ingresses an exterior port of a virtual device (in which case the
     * packet arrives via the datapath switch from an entity outside the
     * virtual topology).
     * 2) it egresses an interior port of a virtual device (in which case the
     * packet was generated by that virtual device).
     *
     * In case 1, the match object for the packet was computed by the
     * FlowController and must contain an inPortID. If a wildcard flow is
     * eventually installed in the FlowController, the match will be a subset
     * of the match originally provided to the simulation. Note that in this
     * case the generatedPacketEgressPort argument should be null and will be
     * ignored.
     *
     * In case 2, the match object for the packet was computed by the Device
     * that emitted the packet and must not contain an inPortID. If the packet
     * is not dropped, it will eventually result in a packet being emitted
     * from one or more of the datapath ports. However, a flow is never
     * installed as a result of such a simulation. Note that in this case the
     * generatedPacketEgressPort argument must not be null.
     *
     * When this method completes, it may send a message to the Datapath
     * Controller to install a flow or send a packet.
     */
    def simulate() {
        log.debug("Simulate a packet {}", origEthernetPkt)

        generatedPacketEgressPort match {
            case None => // This is a packet from the datapath
                origMatch.getInputPortUUID match {
                    case null =>
                        log.error(
                            "Coordinator cannot simulate a flow that " +
                            "NEITHER egressed a virtual device's interior " +
                            "port NOR ingressed a virtual device's exterior " +
                            "port. Match: %s; Packet: %s".format(
                                origMatch.toString, origEthernetPkt.toString))
                        dropFlow(temporary = true)

                    case id: UUID =>
                        if (!dropFragmentedPackets(id))
                            packetIngressesPort(id, getPortGroups = true)
                }

            case Some(egressID) =>
                origMatch.getInputPortUUID match {
                    case null =>
                        packetEgressesPort(egressID)

                    case id: UUID =>
                        log.error(
                            "Coordinator cannot simulate a flow that " +
                            "BOTH egressed a virtual device's interior " +
                            "port AND ingressed a virtual device's exterior " +
                            "port. Match: %s; Packet: %s".format(
                                origMatch.toString, origEthernetPkt.toString))
                        dropFlow(temporary = true)
                }
        }
    }

    private def dropFragmentedPackets(inPort: UUID): Boolean = {
        origMatch.getIpFragmentType match {
            case IPFragmentType.First =>
                origMatch.getEtherType.shortValue match {
                    case IPv4.ETHERTYPE =>
                        sendIpv4FragNeeded(inPort)
                        dropFlow(temporary = true)

                    case ethertype =>
                        log.info("Dropping fragmented packet of unsupported " +
                                 "ethertype={}", ethertype)
                        dropFlow(temporary = false)
                }
                true
            case IPFragmentType.Later =>
                log.info("Dropping non-first fragment at simulation layer")
                val wMatch = new WildcardMatch().
                    setIpFragmentType(IPFragmentType.Later)
                val wFlow = new WildcardFlow().setMatch(wMatch)
                datapathController.tell(
                    AddWildcardFlow(wFlow, None, null, null, null))
                true
            case _ =>
                false
        }
    }

    private def sendIpv4FragNeeded(inPort: UUID) {
        val origPkt: IPv4 = origEthernetPkt.getPayload match {
            case ip: IPv4 => ip
            case _ => null
        }

        if (origPkt == null)
            return

        /* Certain versions of Linux try to guess a lower MTU value if the MTU
         * suggested by the ICMP FRAG_NEEDED error is equal or bigger than the
         * size declared in the IP header found in the ICMP data. This happens
         * when it's the sender host who is fragmenting.
         *
         * In those cases, we can at least prevent the sender from lowering
         * the PMTU by increasing the length in the IP header.
         */
        val mtu = origPkt.getTotalLength
        origPkt.setTotalLength(mtu + 1)
        origPkt.setChecksum(0)

        val icmp = new ICMP()
        icmp.setFragNeeded(mtu, origPkt)
        val ip = new IPv4()
        ip.setPayload(icmp)
        ip.setProtocol(ICMP.PROTOCOL_NUMBER)
        ip.setSourceAddress(origPkt.getDestinationAddress)
        ip.setDestinationAddress(origPkt.getSourceAddress)
        val eth = new Ethernet()
        eth.setEtherType(IPv4.ETHERTYPE)
        eth.setPayload(ip)
        eth.setSourceMACAddress(origEthernetPkt.getDestinationMACAddress)
        eth.setDestinationMACAddress(origEthernetPkt.getSourceMACAddress)
        DatapathController.getRef(actorSystem) !
            EmitGeneratedPacket(inPort, eth, Option(pktContext.getFlowCookie))
    }

    private def packetIngressesDevice(port: Port[_]) {
        var deviceFuture: Future[Any] = null
        port match {
            case _: BridgePort[_] =>
                deviceFuture = expiringAsk(
                    BridgeRequest(port.deviceID, false), expiry)
            case _: RouterPort[_] =>
                deviceFuture = expiringAsk(
                    RouterRequest(port.deviceID, false), expiry)
            case _ =>
                log.error("Port {} belongs to device {} which is neither a " +
                          "bridge or router!", port, port.deviceID)
                dropFlow(temporary = true)
                return
        }
        deviceFuture.onComplete {
            case Left(err) => dropFlow(temporary = true)
            case Right(deviceReply) =>
                if (!deviceReply.isInstanceOf[Device]) {
                    log.error("VirtualTopologyManager didn't return a device!")
                    dropFlow(temporary = true)
                } else {
                    numDevicesSimulated += 1
                    devicesSimulated.put(port.deviceID, numDevicesSimulated)
                    log.debug("Simulating packet with match {}, device {}",
                        pktContext.getMatch, port.deviceID)
                    handleActionFuture(deviceReply.asInstanceOf[Device].process(
                        pktContext))
                }
        }
    }

    private def handleActionFuture(actionF: Future[Action]) {
        actionF.onComplete {
            case Left(err) =>
                log.error("Error instead of Action - {}", err)
                dropFlow(temporary = true)
            case Right(action) =>
                log.info("Received action: {}", action)
                action match {
                    case ToPortSetAction(portSetID) =>
                        emit(portSetID, true, null)

                    case ToPortAction(outPortID) =>
                        packetEgressesPort(outPortID)

                    case _: ConsumedAction =>
                        pktContext.freeze()
                        pktContext.getFlowRemovedCallbacks foreach {
                            cb => cb.call()
                        }
                        cookie match {
                            case None => // Do nothing.
                            case Some(_) =>
                                flowController.tell(DiscardPacket(cookie))
                        }

                    case _: ErrorDropAction =>
                        pktContext.freeze()
                        cookie match {
                            case None => // Do nothing.
                                pktContext.getFlowRemovedCallbacks foreach {
                                    cb => cb.call()
                                }
                            case Some(_) =>
                                // Drop the flow temporarily
                                dropFlow(temporary = true)
                        }

                    case _: DropAction =>
                        pktContext.freeze()
                        log.debug("Device returned DropAction for {}",
                            origMatch)
                        cookie match {
                            case None => // Do nothing.
                                pktContext.getFlowRemovedCallbacks foreach {
                                    cb => cb.call()
                                }
                            case Some(_) =>
                                var temporary = false
                                // Flows which (if they were return flows in a
                                // conntracked connection) could be symmetrical
                                // to their forward flow (i.e. UDP), will be
                                // temporary.
                                // The reason is that changes in the conntrack
                                // table could render them invalid, and we
                                // don't get notifications for new conntrack
                                // entries
                                if (pktContext.isConnTracked() &&
                                    pktContext.forwardAndReturnAreSymmetric) {
                                    temporary = true
                                }
                                dropFlow(temporary, withTags = true)
                        }

                    case _: NotIPv4Action =>
                        log.debug("Device returned NotIPv4Action for {}",
                            origMatch)
                        pktContext.freeze()
                        cookie match {
                            case None => // Do nothing.
                                pktContext.getFlowRemovedCallbacks foreach {
                                    cb => cb.call()
                                }
                            case Some(_) =>
                                val notIPv4Match =
                                    (new WildcardMatch()
                                        .setInputPortUUID(origMatch.getInputPortUUID)
                                        .setEthernetSource(origMatch.getEthernetSource)
                                        .setEthernetDestination(
                                            origMatch.getEthernetDestination)
                                        .setEtherType(origMatch.getEtherType))
                                datapathController.tell(
                                    AddWildcardFlow(
                                        new WildcardFlow()
                                            .setMatch(notIPv4Match),
                                        cookie, origEthernetPkt.serialize(),
                                        pktContext.getFlowRemovedCallbacks,
                                        pktContext.getFlowTags))
                                // TODO(pino): Connection-tracking blob?
                        }


                    case _ =>
                        log.error("Device returned unexpected action!")
                        dropFlow(temporary = true)
                } // end action match
        } // end onComplete
    }

    private def packetIngressesPort(portID: UUID, getPortGroups: Boolean) {
        // Avoid loops - simulate at most X devices.
        if (numDevicesSimulated >= MAX_DEVICES_TRAVERSED) {
            dropFlow(temporary = true)
            return
        }

        // Get the RCU port object and start simulation.
        expiringAsk(PortRequest(portID, false), expiry) onComplete {
            case Left(err) => dropFlow(temporary = true)
            case Right(portReply) => portReply match {
                case port: Port[_] =>
                    if (getPortGroups &&
                            port.isInstanceOf[ExteriorPort[_]]) {
                        pktContext.setPortGroups(
                            port.asInstanceOf[ExteriorPort[_]].portGroups)
                    }

                    pktContext.setInputPort(port)
                    applyPortFilter(port, port.inFilterID,
                                    packetIngressesDevice _)
                    // add tag for flow invalidation
                    pktContext.addFlowTag(FlowTagger.invalidateFlowsByDevice(portID))
                case _ =>
                    log.error("VirtualTopologyManager didn't return a port!")
                    dropFlow(temporary = true)
            }
        }
    }

    def applyPortFilter(port: Port[_], filterID: UUID,
                        thunk: (Port[_]) => Unit) {
        if (filterID == null)
            thunk(port)
        else expiringAsk(ChainRequest(filterID, false), expiry) onComplete {
            case Left(err) => dropFlow(temporary = true)
            case Right(chainReply) => chainReply match {
                case chain: Chain =>
                    // add ChainID for flow invalidation
                    pktContext.addFlowTag(FlowTagger.invalidateFlowsByDevice(filterID))
                    pktContext.addFlowTag(
                        FlowTagger.invalidateFlowsByDeviceFilter(port.id, filterID))

                    val result = Chain.apply(chain, pktContext,
                                             pktContext.getMatch, port.id, true)
                    if (result.action == RuleAction.ACCEPT) {
                        thunk(port)
                    } else if (result.action == RuleAction.DROP ||
                               result.action == RuleAction.REJECT) {
                        dropFlow(temporary = false, withTags = true)
                    } else {
                        log.error("Port filter {} returned {}, not ACCEPT, " +
                                  "DROP, or REJECT.", filterID, result.action)
                        dropFlow(temporary = true)
                    }
                case _ =>
                    log.error("VirtualTopologyActor didn't return a chain!")
                    dropFlow(temporary = true)
            }
        }
    }

    /**
     * Simulate the packet egressing a virtual port. This is NOT intended
     * for output-ing to PortSets.
     * @param portID
     */
    private def packetEgressesPort(portID: UUID) {
        expiringAsk(PortRequest(portID, false), expiry) onComplete {
            case Left(err) => dropFlow(temporary = true)
            case Right(portReply) => portReply match {
                case port: Port[_] =>
                    // add tag for flow invalidation
                    pktContext.addFlowTag(FlowTagger.invalidateFlowsByDevice(portID))
                    pktContext.setOutputPort(port.id)
                    applyPortFilter(port, port.outFilterID, {
                        case _: ExteriorPort[_] =>
                            emit(portID, false, port)
                        case interiorPort: InteriorPort[_] =>
                            packetIngressesPort(interiorPort.peerID, false)
                        case _ =>
                            log.error(
                                "Port {} neither interior nor exterior port",
                                port)
                            dropFlow(temporary = true)
                    })
                case _ =>
                    log.error("VirtualTopologyManager didn't return a port!")
                    dropFlow(temporary = true)
            }
        }
    }

    /**
     * Complete the simulation by emitting the packet from the specified
     * virtual port or PortSet.  If the packet was internally generated
     * this will do a SendPacket, otherwise it will do an AddWildcardFlow.
     * @param outputID The ID of the virtual port or PortSet from which the
     *                 packet must be emitted.
     * @param isPortSet Whether the packet is output to a port set.
     * @param port The port output to; unused if outputting to a port set.
     */
    private def emit(outputID: UUID, isPortSet: Boolean, port: Port[_]) {
        val actions = actionsFromMatchDiff(origMatch, pktContext.getMatch)
        isPortSet match {
            case false =>
                log.debug("Emitting packet from vport {}", outputID)
                actions.append(new FlowActionOutputToVrnPort(outputID))
            case true =>
                log.debug("Emitting packet from port set {}", outputID)
                actions.append(new FlowActionOutputToVrnPortSet(outputID))
        }

        cookie match {
            case None =>
                log.debug("No cookie. SendPacket with actions {}", actions)
                datapathController.tell(
                    SendPacket(origEthernetPkt, actions.toList))
                pktContext.freeze()
                pktContext.getFlowRemovedCallbacks foreach { cb => cb.call() }
            case Some(_) =>
                log.debug("Cookie {}; Add a flow with actions {}",
                    cookie.get, actions)
                pktContext.freeze()
                // TODO(guillermo,pino) don't assume that portset id == bridge id
                if (pktContext.isConnTracked && pktContext.isForwardFlow) {
                    // Write the packet's data to the connectionCache.
                    installConnectionCacheEntry(outputID, pktContext.getMatch,
                            if (isPortSet) outputID else port.deviceID)
                }
                val wFlow = new WildcardFlow()
                    .setMatch(origMatch)
                    .setActions(actions.toList)
                if (pktContext.isConnTracked() && !pktContext.isForwardFlow())
                    wFlow.setHardExpirationMillis(RETURN_FLOW_EXPIRATION_MILLIS)
                else
                    wFlow.setIdleExpirationMillis(IDLE_EXPIRATION_MILLIS)
                datapathController.tell(
                    AddWildcardFlow(
                        wFlow, cookie, origEthernetPkt.serialize(),
                        pktContext.getFlowRemovedCallbacks,
                        pktContext.getFlowTags))
        }
    }

    private def installConnectionCacheEntry(outPortID: UUID,
                                            flowMatch: WildcardMatch,
                                            deviceID: UUID) {
        val key = PacketContext.connectionKey(
                        flowMatch.getNetworkDestinationIP(),
                        flowMatch.getTransportDestination(),
                        flowMatch.getNetworkSourceIP(),
                        flowMatch.getTransportSource(),
                        flowMatch.getNetworkProtocol(),
                        deviceID)
        log.debug("Installing conntrack entry: key:{}", key)
        connectionCache.set(key, "r")
    }

    /*
     * Compares two objects, which may be null, to determine if they should
     * cause flow actions.
     * The catch here is that if `modif` is null, the verdict is true regardless
     * because we don't create actions that set values to null.
     */
    private def matchObjectsSame(orig: Any, modif: Any): Boolean = {
        if (orig == null)
            modif == null
        else if (modif != null)
            orig.equals(modif)
        else
            true
    }

    private def actionsFromMatchDiff(orig: WildcardMatch, modif: WildcardMatch)
    : mutable.ListBuffer[FlowAction[_]] = {
        val actions = mutable.ListBuffer[FlowAction[_]]()
        if (!orig.getEthernetSource.equals(modif.getEthernetSource) ||
            !orig.getEthernetDestination.equals(modif.getEthernetDestination)) {
            actions.append(FlowActions.setKey(FlowKeys.ethernet(
                modif.getDataLayerSource, modif.getDataLayerDestination)))
        }
        if (!matchObjectsSame(orig.getNetworkSourceIP,
                              modif.getNetworkSourceIP) ||
            !matchObjectsSame(orig.getNetworkDestinationIP,
                              modif.getNetworkDestinationIP) ||
            !matchObjectsSame(orig.getNetworkTTL,
                              modif.getNetworkTTL)) {
            actions.append(FlowActions.setKey(
                modif.getNetworkSourceIP match {
                    case srcIP: IPv4Addr =>
                        FlowKeys.ipv4(srcIP,
                            modif.getNetworkDestinationIP.asInstanceOf[IPv4Addr],
                            modif.getNetworkProtocol)
                        //.setFrag(?)
                        .setTos(modif.getNetworkTypeOfService)
                        .setTtl(modif.getNetworkTTL)
                    case srcIP: IPv6Addr =>
                        FlowKeys.ipv6(srcIP,
                            modif.getNetworkDestinationIP.asInstanceOf[IPv6Addr],
                            modif.getNetworkProtocol)
                        //.setFrag(?)
                        .setHLimit(modif.getNetworkTTL)
                }
            ))
        }
        // ICMP errors
        if (!matchObjectsSame(orig.getIcmpData,
                              modif.getIcmpData)) {
            val icmpType = modif.getTransportSource()
            if (icmpType == ICMP.TYPE_PARAMETER_PROBLEM ||
                icmpType == ICMP.TYPE_UNREACH ||
                icmpType == ICMP.TYPE_TIME_EXCEEDED) {
                val actSetKey = FlowActions.setKey(null)
                actions.append(actSetKey)
                actSetKey.setFlowKey(FlowKeys.icmpError(
                    modif.getTransportSource,
                    modif.getTransportDestination,
                    modif.getIcmpData
                ))
            }
        }
        if (!matchObjectsSame(orig.getTransportSourceObject,
                              modif.getTransportSourceObject) ||
            !matchObjectsSame(orig.getTransportDestinationObject,
                              modif.getTransportDestinationObject)) {
            val actSetKey = FlowActions.setKey(null)
            actions.append(actSetKey)
            modif.getNetworkProtocol match {
                case TCP.PROTOCOL_NUMBER =>
                    actSetKey.setFlowKey(FlowKeys.tcp(
                        modif.getTransportSource,
                        modif.getTransportDestination))
                case UDP.PROTOCOL_NUMBER =>
                    actSetKey.setFlowKey(FlowKeys.udp(
                        modif.getTransportSource,
                        modif.getTransportDestination))
                case ICMP.PROTOCOL_NUMBER =>
                    // this case would only happen if icmp id in ECHO req/reply
                    // were translated, which is not the case, so leave alone
            }
        }
        return actions
    }
} // end Coordinator class
