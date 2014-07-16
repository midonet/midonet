package org.midonet.midolman.simulation

import java.util
import java.util.UUID

import org.scalatest.matchers.{BePropertyMatchResult, BePropertyMatcher}

import org.midonet.midolman.PacketWorkflow._
import org.midonet.packets.{IPv4, Ethernet}
import org.midonet.sdn.flows.WildcardFlow
import org.midonet.odp.flows.{FlowKeyIPv4, FlowKeyEthernet, FlowActionSetKey}
import org.midonet.midolman.PacketWorkflow.{AddVirtualWildcardFlow, SendPacket}
import org.midonet.sdn.flows.VirtualActions.FlowActionOutputToVrnPortSet
import org.midonet.sdn.flows.VirtualActions.FlowActionOutputToVrnPort
import org.midonet.sdn.flows.FlowTagger.FlowTag

trait CustomMatchers {

    def dropped(expectedTags: FlowTag*) =
        new BePropertyMatcher[(SimulationResult, PacketContext)] {
            def apply(simRes: (SimulationResult, PacketContext)) =
                BePropertyMatchResult(simRes._1 match {
                    case x if x == Drop || x == TemporaryDrop =>
                        expectedTags forall simRes._2.flowTags.contains
                    case _ =>
                        false
                }, s"a drop flow containing tags {${expectedTags.toList}")
    }

    def toPort(portId: UUID)(expectedTags: FlowTag*) =
        new BePropertyMatcher[(SimulationResult, PacketContext)] {
            def apply(simRes: (SimulationResult, PacketContext)) =
                BePropertyMatchResult((simRes._1 match {
                    case AddVirtualWildcardFlow(flow) =>
                        if (expectedTags forall simRes._2.flowTags.contains)
                            flow.actions
                        else Nil
                    case SendPacket(actions) => actions
                    case _ => Nil
                }).exists({
                    case FlowActionOutputToVrnPort(id) => id == portId
                    case _ => false
                }), s"a port action to $portId")
    }

    def toPortSet(portSetId: UUID, expectedTags: FlowTag*) =
        new BePropertyMatcher[(SimulationResult, PacketContext)] {
            def apply(simRes: (SimulationResult, PacketContext)) =
                BePropertyMatchResult((simRes._1 match {
                        case AddVirtualWildcardFlow(flow) =>
                            if (expectedTags forall simRes._2.flowTags.contains)
                                flow.actions
                            else Nil
                        case SendPacket(actions) => actions
                        case _ => Nil
                    }).exists({
                        case FlowActionOutputToVrnPortSet(id) => id == portSetId
                        case _ => false
                    }), s"a port set action to $portSetId containing tags " +
                        s"{${expectedTags.toList}}")
    }

    def flowMatching(pkt: Ethernet, expectedTags: FlowTag*) =
        new BePropertyMatcher[(SimulationResult, PacketContext)] {
            def apply(simRes: (SimulationResult, PacketContext)) =
                BePropertyMatchResult(simRes._1 match {
                    case AddVirtualWildcardFlow(flow) =>
                        flowMatchesPacket(flow, pkt) &&
                        (expectedTags forall simRes._2.flowTags.contains)
                    case _ =>
                        false
                } , s"a flow matching $pkt containing tags " +
                    s"{${expectedTags.toList}}")

        def flowMatchesPacket(flow: WildcardFlow, pkt: Ethernet): Boolean =
            flow.actions.count {
                case f: FlowActionSetKey => f.getFlowKey match {
                    case k: FlowKeyEthernet =>
                        util.Arrays.equals(
                            k.getDst,
                            pkt.getDestinationMACAddress.getAddress) &&
                        util.Arrays.equals(
                            k.getSrc,
                            pkt.getSourceMACAddress.getAddress)
                    case k: FlowKeyIPv4 if pkt.getPayload.isInstanceOf[IPv4] =>
                        val ipPkt = pkt.getPayload.asInstanceOf[IPv4]
                        k.getDst == ipPkt.getDestinationAddress &&
                        k.getSrc == ipPkt.getSourceAddress
                    case _ => false
                }
                case _ => false
            } == 2
    }
}
