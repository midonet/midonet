package org.midonet.midolman.simulation

import java.util
import java.util.UUID

import org.scalatest.matchers.{BePropertyMatchResult, BePropertyMatcher}

import org.midonet.midolman.PacketWorkflow.{SendPacket, AddVirtualWildcardFlow, SimulationResult}
import org.midonet.packets.{IPv4, Ethernet}
import org.midonet.sdn.flows.WildcardFlow
import org.midonet.sdn.flows.VirtualActions.FlowActionOutputToVrnPortSet
import org.midonet.odp.flows.{FlowAction, FlowKeyIPv4, FlowKeyEthernet, FlowActionSetKey}

trait CustomMatchers {

    val dropped = new BePropertyMatcher[SimulationResult] {
        def apply(simRes: SimulationResult) =
            BePropertyMatchResult(simRes match {
                case AddVirtualWildcardFlow(flow, _, _) =>
                    flow.actions.isEmpty
                case _ =>
                    false
            }, "a drop flow")
    }

    def toPortSet(portSetId: UUID) = new BePropertyMatcher[SimulationResult] {
        def apply(simRes: SimulationResult) =
            BePropertyMatchResult((simRes match {
                    case AddVirtualWildcardFlow(flow, _, _) => flow.actions
                    case SendPacket(actions) => actions
                    case _ => Nil
                }).exists({
                    case FlowActionOutputToVrnPortSet(id) => id == portSetId
                    case _ => false
                }), s"a port set action to $portSetId")
    }

    def flowMatching(pkt: Ethernet) = new BePropertyMatcher[SimulationResult] {
        def apply(simRes: SimulationResult) =
            BePropertyMatchResult(simRes match {
                case AddVirtualWildcardFlow(flow, _ , _) =>
                    flowMatchesPacket(flow, pkt)
                case _ =>
                    false
            } , s"a flow matching $pkt")

        def flowMatchesPacket(flow: WildcardFlow, pkt: Ethernet): Boolean = {
            val f: PartialFunction[({type A <: FlowAction[A]})#A, Boolean] = {
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
            }
            flow.actions
                .collect(f.asInstanceOf[PartialFunction[FlowAction[_], Boolean]])
                .size == 2
        }
    }
}