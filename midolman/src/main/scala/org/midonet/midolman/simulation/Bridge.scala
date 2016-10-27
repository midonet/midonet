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
import java.util
import java.util.{List => JList, UUID}

import scala.collection.{Map => ROMap}

import org.midonet.cluster.client._
import org.midonet.midolman.NotYetException
import org.midonet.midolman.PacketWorkflow.{Drop, ErrorDrop, NoOp, SimStep,
                                            SimulationResult => Result}
import org.midonet.midolman.simulation.Bridge.{RemoveFlowCallbackGenerator, MacFlowCount, UntaggedVlanId}
import org.midonet.midolman.simulation.SimulationStashes._
import org.midonet.midolman.topology.VirtualTopology.{VirtualDevice, tryGet}
import org.midonet.packets._
import org.midonet.sdn.flows.FlowTagger.{tagForArpRequests, tagForBridgePort,
                                         tagForBroadcast, tagForBridge,
                                         tagForFloodedFlowsByDstMac, tagForVlanPort}
import org.midonet.util.functors.Callback0

object Bridge {
    final val UntaggedVlanId: Short = 0

    /* The MacFlowCount is called from the Coordinators' actors and dispatches
     * to the BridgeManager's actor to get/modify the flow counts.  */
    trait MacFlowCount {
        def increment(mac: MAC, vlanId: Short, port: UUID): Unit
        def decrement(mac: MAC, vlanId: Short, port: UUID): Unit
    }

    trait RemoveFlowCallbackGenerator {
        def getCallback(mac: MAC,vlanId: Short, port: UUID): Callback0
    }
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
  * @param vlanPortId this field is the id of the interior port of a peer Bridge
  *                   connected to this device. This means that this Bridge is
  *                   considered to be on VLAN X, Note that a vlan-unaware
  *                   bridge can only be connected to a single vlan-aware device
  *                   (thus having only a single optional value)
  * @param subnetIds only used for the new storage
  */
class Bridge(val id: UUID,
             val adminStateUp: Boolean,
             val tunnelKey: Long,
             val vlanMacTableMap: ROMap[Short, MacLearningTable],
             val ip4MacMap: IpMacMap[IPv4Addr],
             val flowCount: MacFlowCount,
             val inboundFilters: JList[UUID],
             val outboundFilters: JList[UUID],
             val vlanPortId: Option[UUID],
             val flowRemovedCallbackGen: RemoveFlowCallbackGenerator,
             val macToLogicalPortId: ROMap[MAC, UUID],
             val ipToMac: ROMap[IPAddr, MAC],
             val vlanToPort: VlanPortMap,
             val exteriorPorts: List[UUID],
             val subnetIds: List[UUID],
             override val preInFilterMirrors: JList[UUID] =
                new util.ArrayList[UUID](),
             override val postOutFilterMirrors: JList[UUID] =
                new util.ArrayList[UUID]())
        extends SimDevice
        with ForwardingDevice
        with InAndOutFilters
        with MirroringDevice
        with VirtualDevice {

    import org.midonet.midolman.simulation.Simulator._

    override def postInFilterMirrors = throw new NotImplementedError()
    override def preOutFilterMirrors = throw new NotImplementedError()

    val floodAction: Result =
        (exteriorPorts map ToPortAction).foldLeft(NoOp: Result) (ForkAction)

    override val deviceTag = tagForBridge(id)

    override def toString =
        s"Bridge [id=$id adminStateUp=$adminStateUp tunnelKey=$tunnelKey " +
        s"vlans=${vlanMacTableMap.keys} inboundFilters=$inboundFilters " +
        s"outboundFilters=$outboundFilters vlanPortId=$vlanPortId " +
        s"vlanToPorts=$vlanToPort exteriorPorts=$exteriorPorts]"

    /*
     * Avoid generating ToPortXActions directly in the processing methods
     * that spawn from this entry point since the frame may also require having
     * vlan ids POP'd or PUSH'd if this is a Vlan-Aware bridge. Instead, call
     * the unicastAction and multicastAction methods to generate unicast and
     * multicast actions, these will take care of the vlan-id details for you.
     */
    override def process(context: PacketContext): Result = {
        implicit val ctx = context

        context.addFlowTag(deviceTag)

        context.log.debug(s"Packet ingressing $this")

        // Some basic sanity checks
        if (context.wcmatch.getEthSrc.mcast) {
            context.log.info("Packet has multicast/broadcast source: dropping")
            mirroringPreInFilter(context, Drop)
        } else if (adminStateUp) {
            mirroringPreInFilter(context, normalProcess)
        } else {
            context.log.debug(s"Bridge $id is administratively down: dropping")
            Drop
        }
    }

    private val continueIn: SimStep = context => {
        // Learn the entry
        implicit val c: PacketContext = context
        val srcDlAddress = context.wcmatch.getEthSrc
        updateFlowCount(srcDlAddress, context)

        val dstDlAddress = context.wcmatch.getEthDst
        val action =
            if (isArpBroadcast())
                handleArp()
            else if (dstDlAddress.mcast)
                handleL2Multicast()
            else
                handleL2Unicast() // including ARP replies

        doPostBridging(context, action)
    }

    val normalProcess = ContinueWith(context => {
        if (areChainsApplicable()(context)) {
            // Call ingress (pre-bridging) chain. InputPort is already set.
            context.outPortId = null
            filterIn(context, continueIn)
        } else {
            context.log.debug("Ignoring pre/post chains on VLAN tagged traffic")
            continueIn(context)
        }
    })

    override val dropIn: DropHook = (context, result) => {
        updateFlowCount(context.wcmatch.getEthSrc, context)
        Drop
    }

    private def isArpBroadcast()(implicit context: PacketContext) = {
        Ethernet.isBroadcast(context.wcmatch.getEthDst) &&
                             context.wcmatch.getEtherType == ARP.ETHERTYPE
    }

    /**
     * Tells if chains should be executed for the current frames. So far, all
     * will be, except those with a VLAN tag.
     */
    private def areChainsApplicable()(implicit context: PacketContext) = {
        !context.wcmatch.isVlanTagged
    }

    /**
      * Used by normalProcess to deal with L2 Unicast frames, this just decides
      * on a port based on MAC
      */
    private def handleL2Unicast()(implicit context: PacketContext) : Result = {
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
                    context.log.debug(s"Destination MAC $ethDst, VLAN $vlanId " +
                                      s"is not learned: flooding")
                    context.addFlowTag(
                        tagForFloodedFlowsByDstMac(id, vlanId, ethDst))
                    multicastAction()
                } else if (portId == context.inPortId) {
                    context.log.debug(s"Destination MAC $ethDst VLAN $vlanId " +
                                      s"resolves to ingress port $portId: dropping")
                    // No tags because temp flows aren't affected by
                    // invalidations. would get byPort (ethDst, vlan, port)
                    //
                    // TODO: we may have to send it to InPort, instead of
                    // dropping it. Some hardware vendors use L2 ping-pong
                    // packets for their specific purposes (e.g. keepalive message)
                    //
                    ErrorDrop
                } else {
                    context.log.debug(s"Destination MAC $ethDst, VLAN $vlanId " +
                                      s"found on port $portId: forwarding")
                    context.addFlowTag(tagForVlanPort(id, ethDst, vlanId, portId))
                    unicastAction(portId)
                }
        }
    }

    /**
      * Used by normalProcess to deal with frames addressed to an L2 multicast
      * addr except for ARPs.
      */
    private def handleL2Multicast()(implicit context: PacketContext) : Result = {
        context.log.debug("Handling L2 multicast")
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
    private def unicastAction(toPort: UUID)(implicit context: PacketContext) : Result = {

        val inPortVlan = vlanToPort.getVlan(context.inPortId)
        if (inPortVlan != null) {
            context.log.debug("Ingress port is interior with VLAN tag {}: " +
                              "push and forward to trunk port {}", inPortVlan,
                              toPort)
            context.wcmatch.addVlanId(inPortVlan)
            return tryGet(classOf[Port], toPort).action
        }

        val vlanInFrame: Option[JShort] = context.wcmatch.getVlanIds match {
            case l: java.util.List[_] if !l.isEmpty => Some(l.get(0))
            case _ => None
        }

        vlanToPort.getVlan(toPort) match {
            case null => // the outbound port has no vlan assigned
                context.log.debug("Egress port has no VLAN assigned: forwarding")
                tryGet(classOf[Port], toPort).action
            case vlanId if vlanInFrame.isEmpty =>
                context.log.debug("Egress port has VLAN {}, frame had none: " +
                                  "dropping", vlanId)
                Drop
            case vlanId if vlanInFrame.get == vlanId =>
                context.log.debug("Egress port tagged with VLAN {}: pop and " +
                                  "forward", vlanId)
                context.wcmatch.removeVlanId(vlanId)
                tryGet(classOf[Port], toPort).action
            case vlanId =>
                context.log.debug("Egress port VLAN {} does not match frame " +
                                  "VLAN {}: dropping", vlanId, vlanInFrame.get)
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
                context.log.debug("Add VLAN-aware bridge flooding")
                Fork(floodAction, tryGet(classOf[Port], vPId).action)
            case None if !vlanToPort.isEmpty => // A vlan-aware bridge
                context.log.debug("VLAN-aware flooding")
                multicastVlanAware(tryGet(classOf[BridgePort], context.inPortId))
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
                                  (implicit context: PacketContext): Result = inPort match {

        case p: BridgePort if p.isExterior =>
            // multicast from trunk, goes only to designated log. port
            val vlanIds = context.wcmatch.getVlanIds
            val vlanId = if (vlanIds.isEmpty) null else vlanIds.get(0)
            // get interior port tagged with frame's vlan id
            vlanToPort.getPort(vlanId) match {
                case null => // none, ordinary flood
                    context.log.debug("Flooding")
                    floodAction
                case vlanPort => // vlan is on an interior port
                    context.log.debug("Frame from trunk on VLAN {}: send to " +
                                      "trunks, pop to port {}", vlanId, vlanPort)
                    Fork(floodAction, tryGet(classOf[Port], vlanPort).action)
            }
        case p: BridgePort if p.isInterior =>
            vlanToPort.getVlan(context.inPortId) match {
                case vlanId: JShort =>
                    context.log.debug("Frame from interior bridge port: push " +
                                      "VLAN {}", vlanId)
                    context.wcmatch.addVlanId(vlanId)
                case _ =>
                    context.log.debug("Flooding")
            }
            floodAction
        case _ =>
            context.log.warn("Unexpected input port type")
            ErrorDrop
    }

    /**
      * Used by normalProcess to handle specifically ARP multicast.
      */
    private def handleArp()(implicit context: PacketContext): Result = {
        context.log.debug("Handling ARP multicast")
        context.wcmatch.getNetworkProto.shortValue() match {
            case ARP.OP_REQUEST => handleArpRequest()
            case _ => handleArpGeneric()
        }
    }

    /**
      * Handle an ARP request: if the destination IP is found in the pre-seeding
      * table, then generate a response and complete the similation with
      * [[NoOp]]. Otherwise, use generic ARP handling.
      */
    private def handleArpRequest()(implicit context: PacketContext)
    : Result = {
        val pMatch = context.wcmatch
        val nwSrc = pMatch.getNetworkSrcIP.asInstanceOf[IPv4Addr]
        val nwDst = pMatch.getNetworkDstIP.asInstanceOf[IPv4Addr]

        context.log.debug(s"Handling ARP request from $nwSrc for $nwDst")

        val mac = ipToMac.getOrElse(nwDst,
                                    if (ip4MacMap ne null) ip4MacMap get nwDst
                                    else null)
        if (mac ne null) {
            context.log.debug("Known MAC {} reply to the ARP request", mac)
            processArpRequest(context.ethernet.getPayload.asInstanceOf[ARP],
                              mac, context.inPortId)
            NoOp
        } else {
            handleArpGeneric()
        }
    }

    /**
      * Generic ARP handling for requests and responses:
      * - If ARP intended for a peer router port, and port is known, unicast
      *   to that port.
      * - Else, if packet is a GARP, learn entry and flood.
      * - Else, flood.
      */
    private def handleArpGeneric()(implicit context: PacketContext): Result = {
        val pMatch = context.wcmatch
        val nwDst = pMatch.getNetworkDstIP

        ipToMac get nwDst match {
            case Some(mac) if macToLogicalPortId.contains(mac) =>
                val portId = macToLogicalPortId.get(mac).get
                context.log.debug(s"ARP unicast to peer router port $portId " +
                                  s"(IP: $nwDst MAC: $mac)")
                unicastAction(portId)
            case _ if pMatch.getNetworkSrcIP == pMatch.getNetworkDstIP &&
                      (ip4MacMap ne null) =>
                context.log.debug(
                    "Gratuitous ARP: adding to IP->MAC mapping "
                    + s"${pMatch.getNetworkSrcIP} -> ${pMatch.getEthSrc}")
                ip4MacMap.put(pMatch.getNetworkSrcIP.asInstanceOf[IPv4Addr],
                              pMatch.getEthSrc)

                context.log.debug("Flooding GARP at bridge {}, source MAC {}",
                                  id, pMatch.getEthSrc)
                context.addFlowTag(tagForArpRequests(id))
                context.markUserspaceOnly()
                multicastAction()
            case _ =>
                context.log.debug("Flooding ARP at bridge {} source MAC {}",
                                  id, pMatch.getEthSrc)
                context.addFlowTag(tagForArpRequests(id))
                multicastAction()
        }
    }

    /**
      * Perform post-bridging actions.
      *
      * - If the simulation resulted in single ToPort actions, set the output
      *   port and apply post-chains.
      */
    @throws[NotYetException]
    private def doPostBridging(context: PacketContext, act: Result): Result = {
        implicit val ctx = context

        context.log.debug("Post bridging phase")
        //XXX: Add to traversed elements list if flooding.

        if (!act.isInstanceOf[Simulator.ForwardAction]) {
            context.log.debug("Dropping the packet after MAC-learning")
            return act
        }

        /*
         * Out-port matching in a bridge's out-filter will only work for unicast
         * forwarding. The semantics of any other case don't make sense, unless
         * the match turns into "set of output ports contain".
         */
        act match {
            case ToPortAction(port) => context.outPortId = port
            case _ =>
        }

        val result =
            if (areChainsApplicable()) filterOut(context, act.simStep)
            else act

        if (result eq act)
            mirroringPostOutFilter(context, result)
        else
            result
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
            context.log.debug("Increasing reference count for MAC {} VLAN {} " +
                              "on port {}", srcDlAddress, vlanId, inPortId)
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
