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
package org.midonet.midolman.simulation

import java.lang.{Short => JShort}
import java.util.UUID

import scala.collection.{Map => ROMap}

import akka.actor.ActorSystem

import org.midonet.cluster.client._
import org.midonet.midolman.NotYetException
import org.midonet.midolman.PacketWorkflow.{Drop, NoOp, SimulationResult, ErrorDrop}
import org.midonet.midolman.rules.RuleResult
import org.midonet.midolman.simulation.Bridge.UntaggedVlanId
import org.midonet.midolman.topology.VirtualTopology.VirtualDevice
import org.midonet.midolman.topology.VirtualTopologyActor._
import org.midonet.midolman.topology.devices.BridgePort
import org.midonet.midolman.topology.{MacFlowCount, RemoveFlowCallbackGenerator}
import org.midonet.odp.flows.FlowActions.popVLAN
import org.midonet.packets._
import org.midonet.sdn.flows.FlowTagger.{tagForArpRequests, tagForBridgePort, tagForBroadcast, tagForDevice, tagForFloodedFlowsByDstMac, tagForVlanPort}

object Bridge {
    final val UntaggedVlanId: Short = 0
}

/**
  * A bridge.
  *
  * Take into account that the bridge may now have ports that are
  * assigned to specific vlans for l2gateway. You can refer to the
  * l2gateway documentation but briefly, when a bridge has vlan-tagged
  * interior ports:
  * - the bridge must PUSH the vlan-id tagged in the ingress port into all
  *   frames coming from it.
  * - only frames coming into the bridge with the port's vlan id may
  *   egress the device through it, and only after POP'ing the vlan-id
  *
  * The Bridge can be configured for vlan-awareness by adding a number of
  * interior ports tagged with a vlan id. In this case, only frames from
  * the physical network that carry the corresponding vlan-id will be
  * sent through the interior port tagged with the same vlan-id, and only
  * after POP'ing it. For frames coming from that port, the vlan-id will
  * be PUSH'd into the frame.
  *
  * Note that Bridges will *NOT* apply pre- or post- chains on vlan tagged
  * traffic (see MN-590)
  *
  * @param id
  * @param tunnelKey
  * @param vlanMacTableMap
  * @param ip4MacMap
  * @param flowCount
  * @param inFilterId
  * @param outFilterId
  * @param vlanPortId this field is the id of the interior port of a peer Bridge
  *                   connected to this device. This means that this Bridge is
  *                   considered to be on VLAN X, Note that a vlan-unaware
  *                   bridge can only be connected to a single vlan-aware device
  *                   (thus having only a single optional value)
  * @param vxlanPortIds uuids of optional virtual exterior ports logically
                       connected to a virtual switch running on a vtep gateway.
                       If defined, the UUIDs will point in the virtual topology
                       to an exterior vport of subtype VxLanPort that
                       contains the information needed for tunnelling traffic to
                       the peer vtep.
                       FIXME: at the moment (v1.5), this field instance is only
                       needed for flooding traffic. With mac syncing, it will
                       become unnecessary.
  * @param flowRemovedCallbackGen
  * @param macToLogicalPortId
  * @param ipToMac
  * @param subnetIds only used for the new storage
  * @param actorSystem
  */
class Bridge(val id: UUID,
             val adminStateUp: Boolean,
             val tunnelKey: Long,
             val vlanMacTableMap: ROMap[Short, MacLearningTable],
             val ip4MacMap: IpMacMap[IPv4Addr],
             val flowCount: MacFlowCount,
             val inFilterId: Option[UUID],
             val outFilterId: Option[UUID],
             val vlanPortId: Option[UUID],
             val vxlanPortIds: Seq[UUID],
             val flowRemovedCallbackGen: RemoveFlowCallbackGenerator,
             val macToLogicalPortId: ROMap[MAC, UUID],
             val ipToMac: ROMap[IPAddr, MAC],
             val vlanToPort: VlanPortMap,
             val exteriorPorts: List[UUID],
             val subnetIds: List[UUID])
            (implicit val actorSystem: ActorSystem) extends Coordinator.Device
                                                    with VirtualDevice {

    import org.midonet.midolman.simulation.Coordinator._

    val floodAction = FloodBridgeAction(id, exteriorPorts)

    override val deviceTag = tagForDevice(id)

    override def toString =
        s"Bridge [id=$id adminStateUp=$adminStateUp tunnelKey=$tunnelKey " +
        s"vlans=${vlanMacTableMap.keys} inFilterId=$inFilterId " +
        s"outFilterId=$outFilterId vlanPortId=$vlanPortId " +
        s"vxlanPortIds=$vxlanPortIds vlanToPorts=$vlanToPort " +
        s"exteriorPorts=$exteriorPorts]"

    /*
     * Avoid generating ToPortXActions directly in the processing methods
     * that spawn from this entry point since the frame may also require having
     * vlan ids POP'd or PUSH'd if this is a Vlan-Aware bridge. Instead, call
     * the unicastAction and multicastAction methods to generate unicast and
     * multicast actions, these will take care of the vlan-id details for you.
     */
    override def process(context: PacketContext): SimulationResult = {
        implicit val ctx = context

        context.log.debug("Current vlanPortId {}.", vlanPortId)
        context.log.debug("Current vlan-port map {}", vlanToPort)

        // Some basic sanity checks
        if (context.wcmatch.getEthSrc.mcast) {
            context.log.info("Packet has multi/broadcast source, DROP")
            Drop
        } else {
            normalProcess()
        }
    }

    @throws[NotYetException]
    def normalProcess()(implicit context: PacketContext,
                                 actorSystem: ActorSystem)
    : SimulationResult = {
        context.addFlowTag(deviceTag)

        if (!adminStateUp) {
            context.log.debug("Bridge {} is down, DROP", id)
            return Drop
        }

        if (areChainsApplicable()) {

            // Call ingress (pre-bridging) chain. InputPort is already set.
            context.outPortId = null
            val preBridgeResult = Chain.apply(
                inFilterId match {
                    case Some(filterId) => tryAsk[Chain](filterId)
                    case None => null
                },
                context, id, false)
            context.log.debug("Ingress chain returned {}", preBridgeResult)

            preBridgeResult.action match {
                case RuleResult.Action.ACCEPT => // pass through
                case RuleResult.Action.DROP | RuleResult.Action.REJECT =>
                    val srcDlAddress = context.wcmatch.getEthSrc
                    updateFlowCount(srcDlAddress, context)
                    // No point in tagging by dst-MAC+Port because the outPort was
                    // not used in deciding to drop the flow.
                    return Drop
                case other =>
                    context.log.warn(
                        s"PreBridging for $id returned $other which was not " +
                        "ACCEPT, DROP or REJECT.")
                    return ErrorDrop
            }
        } else {
            context.log.debug("Ignoring pre/post chains on vlan tagged traffic")
        }

        // Learn the entry
        val srcDlAddress = context.wcmatch.getEthSrc
        updateFlowCount(srcDlAddress, context)

        val dstDlAddress = context.wcmatch.getEthDst
        val action =
             if (isArpBroadcast())
                 handleARPRequest()
             else if (dstDlAddress.mcast)
                 handleL2Multicast()
             else
                 handleL2Unicast() // including ARP replies

        if (areChainsApplicable()) {
            doPostBridging(context, action)
        } else {
            action
        }
    }

    private def isArpBroadcast()(implicit context: PacketContext) = {
        Ethernet.isBroadcast(context.wcmatch.getEthDst) &&
                             context.wcmatch.getEtherType == ARP.ETHERTYPE
    }

    /**
     * Tells if chains should be executed for the current frames. So far, all
     * will be, except those with a VLAN tag.
     */
    private def areChainsApplicable()(implicit context: PacketContext) =
        !context.wcmatch.isVlanTagged

    /**
      * Used by normalProcess to deal with L2 Unicast frames, this just decides
      * on a port based on MAC
      */
    private def handleL2Unicast()(implicit context: PacketContext)
    : SimulationResult = {
        val ethDst = context.wcmatch.getEthDst
        val ethSrc = context.wcmatch.getEthSrc
        context.log.debug("Handling L2 unicast to {}", ethDst)
        macToLogicalPortId.get(ethDst) match {
            case Some(logicalPort: UUID) => // some device (router|vab-bridge)
                context.log.debug("Packet intended for interior port {}", logicalPort)
                context.addFlowTag(tagForBridgePort(id, logicalPort))
                unicastAction(logicalPort)
            case None => // not a logical port, is the dstMac learned?
                val vlanId = srcVlanTag(context)
                val portId =
                    if (ethDst == ethSrc) {
                        context.inPortId
                    } else {
                        vlanMacTableMap.get(vlanId) match {
                            case Some(map: MacLearningTable) => map.get(ethDst)
                            case _ => null
                        }
                    }
                // Tag the flow with the (src-port, src-mac) pair so we can
                // invalidate the flow if the MAC migrates.
                context.addFlowTag(tagForVlanPort(id, ethSrc, vlanId,
                                                  context.inPortId))
                if (portId == null) {
                    context.log.debug(s"Dst MAC $ethDst, VLAN $vlanId is not learned: Flood")
                    context.addFlowTag(
                        tagForFloodedFlowsByDstMac(id, vlanId, ethDst))
                    multicastAction()
                } else if (portId == context.inPortId) {
                    context.log.warn(
                        s"MAC $ethDst VLAN $vlanId resolves to InPort $portId: DROP")
                    // No tags because temp flows aren't affected by
                    // invalidations. would get byPort (ethDst, vlan, port)
                    //
                    // TODO: we may have to send it to InPort, instead of
                    // dropping it. Some hardware vendors use L2 ping-pong
                    // packets for their specific purposes (e.g. keepalive message)
                    //
                    ErrorDrop
                } else {
                    context.log.debug(s"Dst MAC $ethDst, VLAN $vlanId on port $portId: Forward")
                    context.addFlowTag(tagForVlanPort(id, ethDst, vlanId, portId))
                    unicastAction(portId)
                }
        }
    }

    /**
      * Used by normalProcess to deal with frames addressed to an L2 multicast
      * addr except for ARPs.
      */
    private def handleL2Multicast()(implicit context: PacketContext)
    : SimulationResult = {
        context.log.debug("Handling L2 multicast {}", id)
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
      * Note that there is no flow tagging here. You're responsible to set the
      * right tags.
      */
    private def unicastAction(toPort: UUID)(implicit context: PacketContext)
    : SimulationResult = {

        val inPortVlan = vlanToPort.getVlan(context.inPortId)
        if (inPortVlan != null) {
            context.log.debug(
                "InPort is interior, vlan tagged {}: PUSH & fwd to trunk {}",
                inPortVlan, toPort)
            context.wcmatch.addVlanId(inPortVlan)
            return ToPortAction(toPort)
        }

        val vlanInFrame: Option[JShort] = context.ethernet.getVlanIDs match {
            case l: java.util.List[_] if !l.isEmpty => Some(l.get(0))
            case _ => None
        }

        vlanToPort.getVlan(toPort) match {
            case null => // the outbound port has no vlan assigned
                context.log.debug("OutPort has no vlan assigned: forward")
                ToPortAction(toPort)
            case vlanId if vlanInFrame == None =>
                context.log.debug("OutPort has vlan {}, frame had none: DROP", vlanId)
                Drop
            case vlanId if vlanInFrame.get == vlanId =>
                context.log.debug("OutPort tagged with vlan {}, POP & forward", vlanId)
                context.wcmatch.removeVlanId(vlanId)
                ToPortAction(toPort)
            case vlanId =>
                context.log.debug("OutPort vlan {} doesn't match frame vlan {}: DROP",
                          vlanId, vlanInFrame.get)
                Drop
        }
    }

    /**
      * Possible cases of an L2 multicast happenning on any bridge (vlan-aware
      * or not). This is generally a FloodBridgeAction, but:
      *
      * - If this is a VUB connected to a VAB, it'll fork and do also a
      *   ToPortAction that sends the frame to the VAB.
      * - If this is VAB (that is: has ports with vlan-ids assigned to them)
      *   it'll jump to multicastVlanAware. This will:
      *   - If the frame comes from an exterior port and has a vlan id, restrict
      *     the FloodBridgeAction to a single bridge, the one with that vlan-id,
      *     after popping the vlan tag.
      *   - If the frame comes from an exterior port, but has no vlan id, do
      *     an ordinary flood (for example: BPDU, or an ARP request)
      *   - If the frame comes from an interior port, POP the vlan id if the
      *     port has one assigned, and flood the bridge.
      *
      *  Note that there is no flow tagging here, you're responsible to set the
      *  right tag depending on the reason for doing the multicast. Generally,
      *  if you broadcast bc. you ignore the mac's port, you must set a
      *  tagForFloodedFlowsByDstMac. For broadcast flows, you'll want to set
      *  a tagForBroadcast.
      */
    private def multicastAction()(implicit context: PacketContext) = {
        context.addFlowTag(tagForBroadcast(id))

        vlanPortId match {
            case Some(vPId) if !context.inPortId.equals(vPId) =>
                // This VUB is connected to a VAB: send there too
                context.log.debug("Add vlan-aware bridge flood")
                ForkAction(Vector(
                    floodAction,
                    ToPortAction(vPId)
                ))
            case None if !vlanToPort.isEmpty => // A vlan-aware bridge
                context.log.debug("Vlan-aware flood")
                multicastVlanAware(tryAsk[BridgePort](context.inPortId))
            case _ => // A normal bridge
                context.log.debug("Flooding")
                floodAction
        }
    }

    /**
      * Possible cases of an L2 multicast happening on a vlan-aware bridge.
      * Refer to multicastAction for details.
      */
    private def multicastVlanAware(inPort: BridgePort)
                                  (implicit context: PacketContext)
    : SimulationResult = inPort match {

        case p: BridgePort if p.isExterior =>
            // multicast from trunk, goes only to designated log. port
            val vlanIds = context.ethernet.getVlanIDs
            val vlanId = if (vlanIds.isEmpty) null else vlanIds.get(0)
            // get interior port tagged with frame's vlan id
            vlanToPort.getPort(vlanId) match {
                case null => // none, ordinary flood
                    context.log.debug("Flooding")
                    floodAction
                case vlanPort => // vlan is on an interior port
                    context.log.debug(
                        "Frame from trunk on vlan {}, send to trunks, POP, to port {}",
                        vlanId, vlanPort)
                    ForkAction(Vector(
                        floodAction,
                        DoFlowAction(popVLAN()),
                        ToPortAction(vlanPort))
                    )
            }
        case p: BridgePort if p.isInterior =>
            vlanToPort.getVlan(context.inPortId) match {
                case vlanId: JShort =>
                    context.log.debug("Frame from interior bridge port: PUSH {}", vlanId)
                    context.wcmatch.addVlanId(vlanId)
                case _ =>
                    context.log.debug("Flood")
            }
            floodAction
        case _ =>
            context.log.warn("Unexpected input port type!")
            ErrorDrop
    }

    /**
      * Used by normalProcess to handle specifically ARP multicast.
      */
    private def handleARPRequest()(implicit context: PacketContext)
    : SimulationResult = {
        context.log.debug("Handling ARP multicast")
        val pMatch = context.wcmatch
        val nwDst = pMatch.getNetworkDstIP
        if (ipToMac.contains(nwDst)) {
            // Forward broadcast ARPs to their devices if we know how.
            context.log.debug("The packet is intended for an interior port.")
            val portID = macToLogicalPortId.get(ipToMac.get(nwDst).get).get
            unicastAction(portID)
        } else {
            // If it's an ARP request, can we answer from the Bridge's IpMacMap?
            val mac = pMatch.getNetworkProto.shortValue() match {
                case ARP.OP_REQUEST if ip4MacMap != null =>
                    ip4MacMap get
                        pMatch.getNetworkDstIP.asInstanceOf[IPv4Addr]
                case _ =>
                    null
            }

            // TODO(pino): tag the flow with the destination
            // TODO: mac, so we can deal with changes in the mac.
            if (mac == null) {
                // Unknown MAC for this IP, or it's an ARP reply, broadcast
                context.log.debug("Flooding ARP at bridge {}, source MAC {}",
                                  id, pMatch.getEthSrc)
                context.addFlowTag(tagForArpRequests(id))
                multicastAction()
            } else {
                context.log.debug("Known MAC, {} reply to the ARP req.", mac)
                processArpRequest(context.ethernet.getPayload.asInstanceOf[ARP],
                                  mac, context.inPortId)
                NoOp
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
    @throws[NotYetException]
    private def doPostBridging(context: PacketContext,
                               act: SimulationResult): SimulationResult = {

        implicit val ctx = context

        context.log.debug("post bridging phase")
        //XXX: Add to traversed elements list if flooding.

        // If the packet's not being forwarded, we're done.
        if (!act.isInstanceOf[Coordinator.ForwardAction]) {
            context.log.debug("Dropping the packet after mac-learning.")
            return act
        }

        // Otherwise, apply egress (post-bridging) chain
        act match {
            case ToPortAction(port) =>
                context.log.debug("To port: {}", port)
                context.outPortId = port
            case FloodBridgeAction(brid, ports) =>
                context.log.debug("Flood bridge: {}", brid)
            case ForkAction(acts) =>
                context.log.debug("Fork, to port and flood")
                // TODO (galo) check that we only want to apply to the first
                // action
                acts.head match {
                    case ToPortAction(port) =>
                        context.outPortId = port
                    case FloodBridgeAction(brid, ports) =>
                    case a =>
                        context.log.warn("Unexpected forked action {}", a)
                }
            case a => context.log.warn("Unhandled Coordinator.Action {}", a)
        }

        val postBridgeResult = Chain.apply(
            outFilterId match {
                case Some(filterId) => tryAsk[Chain](filterId)
                case None => null
            },
            context, id, false
        )
        postBridgeResult.action match {
            case RuleResult.Action.ACCEPT => // pass through
                context.log.debug("Forwarding the packet with action {}", act)
                // Note that the filter cannot change the output port.
                act
            case RuleResult.Action.DROP | RuleResult.Action.REJECT =>
                context.log.debug("Dropping the packet due to egress filter.")
                Drop
            case other =>
                context.log.warn(
                    "PostBridging returned {} which was not ACCEPT, DROP, or REJECT.",
                    other)
                // TODO(pino): decrement the mac-port reference count?
                // TODO(pino): remove the flow tag?
                ErrorDrop
        }
    }

    /**
      * Decide what source VLAN this packet is from.
      *
      * - Vlan 0 ("untagged") will be used when:
      *   - The frame is actually untagged
      *   - The frame is vlan-tagged, but the bridge is a VUB (i.e.: the bridge
      *     has no interior ports tagged with a vlan id).
      * - If the in-port is tagged with a vlan, that's the source VLAN
      * - Else if the traffic is tagged with a vlan, the outermost tag
      *   is the source VLAN. This will be expected to exist as a tag in one
      *   of the bridge's interior ports.
      */
    private def srcVlanTag(context: PacketContext): Short =
        if (vlanMacTableMap.size == 1) {
            UntaggedVlanId
        } else {
            val inPortVlan = vlanToPort.getVlan(context.inPortId)
            if (inPortVlan ne null) {
                inPortVlan
            } else {
                val matchVlans = context.wcmatch.getVlanIds
                if (!matchVlans.isEmpty) {
                    matchVlans.get(0)
                } else {
                    UntaggedVlanId
                }
            }
        }

    /**
      * Learns the given source MAC unless it's a logical port's, also
      * increasing the reference count for the tuple mac-vlan-port. What vlan is
      * chosen depends on the rules in Bridge::srcVlanTag.
      *
      * This will also install a flow removed callback in the context so that
      * we can decrement the mac-vlan-port flow count accordingly.
      *
      * NOTE: Flow invalidations caused by MACs migrating between ports are
      * done by the BridgeManager's MacTableNotifyCallBack.
      */
    private def updateFlowCount(srcDlAddress: MAC,
                                context: PacketContext) {
        implicit val ctx = context
        if (!macToLogicalPortId.contains(srcDlAddress)) {
            val vlanId = short2Short(srcVlanTag(context))
            val inPortId = context.inPortId
            context.log.debug("Increasing ref. count for MAC {}, VLAN {} on port {}",
                              srcDlAddress, vlanId, inPortId)
            flowCount.increment(srcDlAddress, vlanId, inPortId)
            val callback = flowRemovedCallbackGen
                           .getCallback(srcDlAddress, vlanId, inPortId)
            context.addFlowRemovedCallback(callback)
        }
    }

    private def processArpRequest(arpReq: ARP, mac: MAC, inPortId: UUID)
                                 (implicit originalPktContex: PacketContext) {
        // Construct the reply, reversing src/dst fields from the request.
        val eth = ARP.makeArpReply(mac, arpReq.getSenderHardwareAddress,
                                   arpReq.getTargetProtocolAddress,
                                   arpReq.getSenderProtocolAddress)
        originalPktContex.addGeneratedPacket(inPortId, eth)
    }
}
