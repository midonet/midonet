/*
 * Copyright 2012 Midokura Europe SARL
 */
package org.midonet.midolman.simulation

import akka.actor.ActorSystem
import akka.dispatch.{ExecutionContext, Future, Promise}
import scala.collection.{Map => ROMap}
import java.util.UUID

import org.midonet.cluster.client._
import org.midonet.midolman.logging.LoggerFactory
import org.midonet.midolman.rules.RuleResult.Action
import org.midonet.midolman.simulation.Coordinator._
import org.midonet.midolman.topology.{FlowTagger, MacFlowCount, RemoveFlowCallbackGenerator}
import org.midonet.midolman.topology.VirtualTopologyActor._
import org.midonet.packets._
import org.midonet.util.functors.Callback1
import org.midonet.midolman.DeduplicationActor
import java.lang.{Short => JShort}
import org.midonet.cluster.client.BridgePort
import org.midonet.midolman.topology.VirtualTopologyActor.PortRequest
import org.midonet.midolman.simulation.Coordinator.ConsumedAction
import org.midonet.midolman.simulation.Coordinator.ToPortSetAction
import scala.Some
import org.midonet.midolman.simulation.Coordinator.DropAction
import org.midonet.midolman.DeduplicationActor.EmitGeneratedPacket
import org.midonet.midolman.simulation.Coordinator.ForkAction
import org.midonet.midolman.simulation.Coordinator.ToPortAction
import org.midonet.midolman.simulation.Coordinator.ErrorDropAction
import org.midonet.odp.flows.FlowActionPopVLAN


/**
 * A bridge.
 *
 * Take into account that the bridge may now have ports that are
 * assigned to specific vlans for l2gateway. You can refer to the
 * l2gateway documentation but briefly, when a bridge has vlan-tagged
 * interior ports:
 * - all frames that come from that port must be PUSH'd the appropriate
 *   VLAN ID.
 * - only frames coming into the bridge with the port's vlan id may
 *   egress the device through it, and only after POP'ing the vlan-id
 *
 * To make this clearer, ToPortXActions should not be used directly, but
 * instead call the unicastAction and multicastAction methods to
 * generate the appropriate actions to send to one port (unicastAction)
 * or many ports (multicastAction).
 *
 * The Bridge can be configured for vlan-awareness by adding a number of
 * interior ports tagged with a vlan id. In this case, only frames from
 * the physical network that carry the corresponding vlan-id will be
 * sent through the interior port tagged with the same vlan-id, and only
 * after POP'ing it. For frames coming from that port, the vlan-id will
 * be PUSH'd into the frame.
 *
 * @param id
 * @param tunnelKey
 * @param macPortMap
 * @param ip4MacMap
 * @param flowCount
 * @param inFilter
 * @param outFilter
 * @param vlanPortId this field is the id of the interior port of a peer
 *                   VlanAwareBridge or Bridge connected to this device.
 *                   This means that this Bridge is considered to be on VLAN X,
 *                   Note that a vlan-unaware bridge can only be connected to a
 *                   single vlan-aware device (thus having only a single
 *                   optional value)
 * @param flowRemovedCallbackGen
 * @param macToLogicalPortId
 * @param ipToMac
 * @param actorSystem
 */
class Bridge(val id: UUID, val tunnelKey: Long,
             val macPortMap: MacLearningTable,
             val ip4MacMap: IpMacMap[IPv4Addr],
             val flowCount: MacFlowCount, val inFilter: Chain,
             val outFilter: Chain,
             val vlanPortId: Option[UUID],
             val flowRemovedCallbackGen: RemoveFlowCallbackGenerator,
             val macToLogicalPortId: ROMap[MAC, UUID],
             val ipToMac: ROMap[IPAddr, MAC],
             val vlanToPort: VlanPortMap)
            (implicit val actorSystem: ActorSystem) extends Device {

    var log = LoggerFactory.getSimulationAwareLog(this.getClass)(actorSystem.eventStream)

    override def process(packetContext: PacketContext)
                        (implicit ec: ExecutionContext,
                         actorSystem: ActorSystem)
            : Future[Coordinator.Action] = {
        implicit val pktContext = packetContext

        log.debug("Bridge {} process method called.", id)
        log.debug("Current vlanPortId {}.", vlanPortId)
        log.debug("Current vlan-port map {}", vlanToPort)

        // Some basic sanity checks
        if (Ethernet.isMcast(packetContext.getMatch.getEthernetSource)) {
            log.info("Packet has multi/broadcast source, DROP")
            Promise.successful(DropAction())
        } else
            normalProcess(packetContext)
    }

    def normalProcess(packetContext: PacketContext)
                     (implicit ec: ExecutionContext, actorSystem: ActorSystem)
    : Future[Coordinator.Action] = {

        implicit val pktContext = packetContext

        log.debug("Processing frame: {}", pktContext.getFrame)

        // Tag the flow with this Bridge ID
        packetContext.addFlowTag(FlowTagger.invalidateFlowsByDevice(id))

        // Call ingress (pre-bridging) chain
        // InputPort is already set.
        packetContext.setOutputPort(null)
        val preBridgeResult = Chain.apply(inFilter, packetContext,
                                          packetContext.getMatch, id, false)
        log.debug("The ingress chain returned {}", preBridgeResult)

        if (preBridgeResult.action == Action.DROP ||
                preBridgeResult.action == Action.REJECT) {
            val srcDlAddress = packetContext.getMatch.getEthernetSource
            updateFlowCount(srcDlAddress, packetContext)
            // No point in tagging by dst-MAC+Port because the outPort was
            // not used in deciding to drop the flow.
            return Promise.successful(DropAction())
        } else if (preBridgeResult.action != Action.ACCEPT) {
            log.error("Pre-bridging for {} returned an action which was {}, " +
                      "not ACCEPT, DROP, or REJECT.", id,
                      preBridgeResult.action)
            return Promise.successful(ErrorDropAction())
        }
        if (preBridgeResult.pmatch ne packetContext.getMatch) {
            log.error("Pre-bridging for {} returned a different match object",
                      id)
            return Promise.successful(ErrorDropAction())
        }

        val dstDlAddress = packetContext.getMatch.getEthernetDestination
        val action: Future[Coordinator.Action] =
             if (isArpBroadcast()) handleARPRequest()
             else if (Ethernet.isMcast(dstDlAddress)) handleL2Multicast()
             else handleL2Unicast() // including ARP replies

        action map doPostBridging(packetContext)
    }

    private def isArpBroadcast()(implicit pktCtx: PacketContext) = {
        Ethernet.isBroadcast(pktCtx.getMatch.getEthernetDestination) &&
                             pktCtx.getMatch.getEtherType == ARP.ETHERTYPE
    }

    /**
     * Used by normalProcess to deal with L2 Unicast frames, this just decides
     * on a port based on MAC
     */
    private def handleL2Unicast()(implicit packetContext: PacketContext,
                                       ec: ExecutionContext): Future[Coordinator.Action] = {
        // L2 unicast
        log.debug("Handling L2 unicast")
        val dlDst = packetContext.getMatch.getEthernetDestination
        val dlSrc = packetContext.getMatch.getEthernetSource
        macToLogicalPortId.get(dlDst) match {
            case Some(logicalPort: UUID) => // some device (router|vab-bridge)
                log.debug("Packet intended for interior port.")
                packetContext.addFlowTag(
                    FlowTagger.invalidateFlowsByLogicalPort(id, logicalPort))
                unicastAction(logicalPort)
            case None => // not a logical port, is the dstMac learned?
                val port = getPortOfMac(dlDst, packetContext.expiry, ec)
                // Tag the flow with the (dst-port, dst-mac) pair so we can
                // invalidate the flow if the MAC migrates.
                port flatMap {
                    case null =>
                        log.debug("Dst MAC {} is not learned: Flood", dlDst)
                        packetContext.addFlowTag(
                            FlowTagger.invalidateFloodedFlowsByDstMac(id, dlDst))
                        multicastAction()
                    case portId: UUID =>
                        log.debug("Dst MAC {} on port {}: Forward",
                                  dlDst, portId)
                        packetContext.addFlowTag(
                            FlowTagger.invalidateFlowsByPort(id, dlDst, portId))
                        packetContext.addFlowTag(
                            FlowTagger.invalidateFlowsByPort(id, dlSrc,
                                                packetContext.getInPortId))
                        unicastAction(portId)
                }
        }
    }

    /**
     * Used by normalProcess to deal with frames having an L2 multicast addr.
     * that are not ARPs.
     */
    private def handleL2Multicast()(implicit packetContext: PacketContext,
                           ec: ExecutionContext): Future[Coordinator.Action] = {
        log.debug("Handling L2 multicast {}", id)
        packetContext.addFlowTag(FlowTagger.invalidateBroadcastFlows(id, id))
        // TODO (galo) -> if coming from interior port, ok, but if coming
        // from exterior port depends on the vlan id on the frame
        multicastAction()
    }

    /**
     * Does a unicastAction, validating and doing PUSH/POP of vlan ids as
     * appropriate. All other methods in this device are expected NOT to
     * build ToPortActions by themselves, and instead delegate on this method,
     * which allows them to remain agnostic of vlan related details.
     *
     * Should be used whenever an unicastAction needs to happen. For example,
     * after doing MAC learning and deciding that a frame needs to go to toPort
     * just delegate on this method to create the right action.
     *
     * @param toPort
     * @param pktCtx
     * @return
     */
    private def unicastAction(toPort: UUID)(implicit pktCtx: PacketContext):
    Future[Coordinator.Action] = {

        val inPortVlan = vlanToPort.getVlan(pktCtx.getInPortId)
        if (inPortVlan != null) {
            log.debug("InPort has vlan {}, PUSH & fwd to trunks", inPortVlan)
            pktCtx.getMatch.addVlanId(inPortVlan)
            return Promise.successful(ToPortAction(toPort))
        }

        val vlanInFrame: Option[JShort]= pktCtx.getFrame.getVlanIDs match {
            case l: java.util.List[JShort] if !l.isEmpty => Some(l.get(0))
            case _ => None
        }

        vlanToPort.getVlan(toPort) match {
            case null => // the outbound port has no vlan assigned
                Promise.successful(ToPortAction(toPort))
            case vlanId if vlanInFrame == None =>
                log.warning("Out port has vlan {}, but frame did not" +
                    "have any, DROP", vlanId)
                Promise.successful(new DropAction())
            case vlanId if vlanInFrame.get == vlanId =>
                log.debug("OutPort has vlan {}, POP & forward", vlanId)
                pktCtx.getMatch.removeVlanId(vlanId)
                Promise.successful(ToPortAction(toPort))
            case vlanId =>
                log.warning("OutPort has vlan {} but frame has {}, " +
                            "DROP", vlanInFrame.get)
                Promise.successful(new DropAction())
        }
    }

    /**
     * Possible cases of an L2 multicast happenning on any bridge (vlan-aware or
     * not). This is generally a ToPortSetAction, but:
     *
     * - If the bridge is connected to another vlan-aware bridge, it'll fork
     *   and do also a ToPortAction that sends the frame to the vab-bridge
     * - If the bridge is vlan-aware itself (that is: has ports with vlan-ids
     *   assigned to them) it'll jump to multicastVlanAware. This will:
     *   - If the frame comes from an exterior port and has a vlan id, restrict
     *     the ToPortSetAction to a single ToPortSet, the one with that vlan-id,
     *     after popping the vlan tag.
     *   - If the frame comes from an exterior port, but has no vlan id, send
     *     to the ordinary port set (for example: BPDU, or an ARP request)
     *   - If the frame comes from an interior port, POP the vlan id if the
     *     port has one assigned, and send to the PortSet.
     */
    private def multicastAction()(implicit pktCtx: PacketContext):
    Future[Coordinator.Action] = {
        vlanPortId match {
            case Some(vPId) if (!pktCtx.getInPortId.equals(vPId)) =>
                // This bridge is connected to a vlan-aware bridge: send there too
                log.debug("Add vlan-aware bridge to port set")
                Promise.successful(ForkAction(List(
                        Promise.successful(ToPortSetAction(id)),
                        Promise.successful(ToPortAction(vPId)))))
            case None if (!vlanToPort.isEmpty) =>
                log.debug("Vlan-aware ToPortSet")
                multicastVlanAware()
            case _ =>
                // normal bridge
                log.debug("Normal ToPortSet")
                Promise.successful(ToPortSetAction(id))
        }
    }

    /**
     * Possible cases of an L2 multicast happening on a vlan-aware bridge.
     *
     * Refer to multicastAction for details.
     */
    private def multicastVlanAware()(implicit pktCtx: PacketContext):
    Future[Coordinator.Action] = {
        getPort(pktCtx.getInPortId, pktCtx.getExpiry) flatMap {
            case p: ExteriorBridgePort =>
                // multicast from trunk, goes only to designated log. port
                val vlanIds = pktCtx.getFrame.getVlanIDs
                val vlanId = if (vlanIds.isEmpty) null else vlanIds.get(0)
                vlanToPort.getPort(vlanId) match {
                    case null =>
                        log.debug("Frame to port set")
                        Promise.successful(new ToPortSetAction(id))
                    case vlanPort =>
                        log.info("Frame from trunk on vlan {}, send to other " +
                            "trunks, POP, send to port {}", vlanId, vlanPort)
                        Promise.successful(ForkAction(List(
                                Promise.successful(new ToPortSetAction(id)),
                                Promise.successful(new DoFlowAction(new FlowActionPopVLAN)),
                                Promise.successful(new ToPortAction(vlanPort)))
                        ))
                }
            case p: InteriorBridgePort =>
                vlanToPort.getVlan(pktCtx.getInPortId) match {
                    case vlanId: JShort =>
                        log.debug("Frame from log. br. port: PUSH {}", vlanId)
                        pktCtx.getMatch.addVlanId(vlanId)
                        Promise.successful(ToPortSetAction(id))
                    case _ =>
                        log.debug("Send to port set")
                        Promise.successful(ToPortSetAction(id))
                }
            case _ =>
                log.warning("Unexpected InPort type!")
                Promise.successful(ErrorDropAction())
        }
    }

    /**
     * Retrieves a BridgePort
     */
    private def getPort(portId: UUID, expiry: Long)
                         (implicit actorSystem: ActorSystem,
                          pktContext: PacketContext): Future[BridgePort[_]] = {
        expiringAsk(PortRequest(portId, update = false), expiry)
            .mapTo[BridgePort[_]] map {
            case null => log.warning("Can't find port: {}", portId)
                         null
            case p => p
        }
    }

    /**
     * Used by normalProcess to handle specifically ARP multicast
     */
    private def handleARPRequest()(implicit pktContext: PacketContext,
                          ec: ExecutionContext): Future[Coordinator.Action] = {
        log.debug("Handling ARP multicast")
        val pMatch = pktContext.getMatch
        val nwDst = pMatch.getNetworkDestinationIP
        if (ipToMac.contains(nwDst)) {
            // Forward broadcast ARPs to their devices if we know how.
            log.debug("The packet is intended for an interior port.")
            val portID = macToLogicalPortId.get(ipToMac.get(nwDst).get).get
            unicastAction(portID) // TODO (galo) read comments inside
        } else {
            // If it's an ARP request, can we answer from the Bridge's IpMacMap?
            var mac: Future[MAC]= null
            pMatch.getNetworkProtocol match {
                case ARP.OP_REQUEST =>
                    mac = getMacOfIp(pMatch.getNetworkDestinationIP
                                     .asInstanceOf[IPv4Addr],
                        pktContext.expiry, ec)
                case _ => Promise.successful[MAC](null)
            }
            // TODO(pino): tag the flow with the destination
            // TODO: mac, so we can deal with changes in the mac.
            mac flatMap {
                case null =>
                    // Unknown MAC for this IP, or it's an ARP reply, broadcast
                    log.debug("Flooding ARP to port set {}, source MAC {}",
                              id, pMatch.getEthernetSource)
                    pktContext.addFlowTag(FlowTagger.invalidateArpRequests(id))
                    multicastAction()
                case m: MAC =>
                    log.debug("Known MAC, {} reply to the ARP req.", mac)
                    processArpRequest(
                        pktContext.getFrame.getPayload.asInstanceOf[ARP], m,
                        pktContext.getInPortId)
                    Promise.successful(new ConsumedAction)
            }
        }
    }

    /**
     * Perform post-bridging actions.
     *
     * It will learn the mac-port entry, and:
     * - If the simulation resulted in single ToPort actions, set the output
     *   port and apply post-chains.
     * - If the simulation resulted in a Fork action, set the output port to
     *   the first action in the fork.
     */
    private def doPostBridging(packetContext: PacketContext)
                              (act: Coordinator.Action): Coordinator.Action = {
        implicit val pktContext = packetContext

        log.debug("Post-Bridging..")
        // First, learn the mac-port entry.
        // TODO(pino): what if the filters can modify the L2 addresses?
        updateFlowCount(packetContext.getMatch.getEthernetSource, packetContext)
        //XXX: Add to traversed elements list if flooding.

        // If the packet's not being forwarded, we're done.
        if (!act.isInstanceOf[Coordinator.ForwardAction]) {
            log.debug("Dropping the packet after mac-learning.")
            return act
        }

        // Otherwise, apply egress (post-bridging) chain
        act match {
            case a: Coordinator.ToPortAction =>
                log.debug("To port: {}", a.outPort)
                packetContext.setOutputPort(a.outPort)
            case a: Coordinator.ToPortSetAction =>
                log.debug("To port set: {}", a.portSetID)
                packetContext.setOutputPort(a.portSetID)
            case a: ForkAction =>
                log.debug("Fork, to port and port set")
                // TODO (galo) check that we only want to apply to the first
                // action
                a.actions.head map { case c => c match {
                        case b: Coordinator.ToPortAction =>
                            packetContext.setOutputPort(b.outPort)
                        case b: Coordinator.ToPortSetAction =>
                            packetContext.setOutputPort(b.portSetID)
                    }}
            case a =>
                log.error("Unhandled Coordinator.ForwardAction {}", a)
        }
        val postBridgeResult = Chain.apply(outFilter, packetContext,
                                           packetContext.getMatch, id, false)

        if (postBridgeResult.action == Action.DROP ||
                postBridgeResult.action == Action.REJECT) {
            log.debug("Dropping the packet due to egress filter.")
            DropAction()
        } else if (postBridgeResult.action != Action.ACCEPT) {
            log.error("Post-bridging for {} returned an action which was {}, " +
                      "not ACCEPT, DROP, or REJECT.", id,
                      postBridgeResult.action)
            // TODO(pino): decrement the mac-port reference count?
            // TODO(pino): remove the flow tag?
            ErrorDropAction()
        } else {
            log.debug("Forwarding the packet with action {}", act)
            // Note that the filter is not permitted to change the output port.
            act
        }
    }

    private def updateFlowCount(srcDlAddress: MAC,
                                packetContext: PacketContext) {
        implicit val pktContext = packetContext
        // Learn the src MAC unless it's a logical port's.
        if (!macToLogicalPortId.contains(srcDlAddress)) {
            log.debug("Increasing the reference count for MAC {} on port {}",
                srcDlAddress, packetContext.getInPortId())
            flowCount.increment(srcDlAddress, packetContext.getInPortId)
            // Add a flow-removal callback that decrements the reference count.
            packetContext.addFlowRemovedCallback(
                flowRemovedCallbackGen.getCallback(srcDlAddress,
                                                   packetContext.getInPortId))
            // Flow invalidations caused by MACs migrating between ports
            // are done by the BridgeManager's MacTableNotifyCallBack.
        }
    }

    private def getPortOfMac(mac: MAC, expiry: Long, ec: ExecutionContext) = {
        val rv = Promise[UUID]()(ec)
        macPortMap.get(mac, new Callback1[UUID] {
            def call(port: UUID) {
                rv.success(port)
            }
        }, expiry)
        rv
    }

    private def getMacOfIp(ip: IPv4Addr, expiry: Long, ec: ExecutionContext) = {
        ip4MacMap match {
            case null => Promise.successful[MAC](null)
            case map =>
                val rv = Promise[MAC]()(ec)
                map.get(ip, new Callback1[MAC] {
                    def call(mac: MAC) {
                        rv.success(mac)
                    }
                }, expiry)
                rv
        }
    }

    private def processArpRequest(arpReq: ARP, mac: MAC, inPortId: UUID)
                                 (implicit ec: ExecutionContext,
                                  actorSystem: ActorSystem,
                                  originalPktContex: PacketContext) {
        // Construct the reply, reversing src/dst fields from the request.
        val eth = ARP.makeArpReply(mac, arpReq.getSenderHardwareAddress,
                                   arpReq.getTargetProtocolAddress,
                                   arpReq.getSenderProtocolAddress)
        DeduplicationActor.getRef(actorSystem) ! EmitGeneratedPacket(
            inPortId, eth,
            if (originalPktContex != null)
                Option(originalPktContex.getFlowCookie)
            else None)
    }
}
