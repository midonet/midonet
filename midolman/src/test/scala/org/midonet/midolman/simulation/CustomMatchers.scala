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

import java.util
import java.util.UUID

import scala.collection.JavaConversions._

import org.midonet.midolman.PacketWorkflow.{AddVirtualWildcardFlow, SendPacket, _}
import org.midonet.odp.flows.{FlowAction, FlowActionSetKey, FlowKeyEthernet, FlowKeyIPv4}
import org.midonet.packets.{Ethernet, IPv4}
import org.midonet.sdn.flows.FlowTagger.FlowTag
import org.midonet.sdn.flows.VirtualActions.{FlowActionOutputToVrnBridge, FlowActionOutputToVrnPort}
import org.scalatest.matchers.{BePropertyMatchResult, BePropertyMatcher}

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
                    case AddVirtualWildcardFlow =>
                        if (expectedTags forall simRes._2.flowTags.contains)
                            simRes._2.virtualFlowActions.toList
                        else Nil
                    case SendPacket => simRes._2.virtualFlowActions.toList
                    case _ => Nil
                }).exists({
                    case FlowActionOutputToVrnPort(id) => id == portId
                    case _ => false
                }), s"a port action to $portId")
    }

    def toBridge(bridgeId: UUID, brPorts: List[UUID], expectedTags: FlowTag*) =
        new BePropertyMatcher[(SimulationResult, PacketContext)] {
            def apply(simRes: (SimulationResult, PacketContext)) =
                BePropertyMatchResult((simRes._1 match {
                        case AddVirtualWildcardFlow =>
                            if (expectedTags forall simRes._2.flowTags.contains)
                                simRes._2.virtualFlowActions.toList
                            else Nil
                        case SendPacket => simRes._2.virtualFlowActions.toList
                        case _ => Nil
                    }).exists({
                        case FlowActionOutputToVrnBridge(id, ports) =>
                            id == bridgeId && ports == brPorts
                        case _ => false
                    }), s"a flood bridge action on $bridgeId containing tags " +
                        s"{${expectedTags.toList}}")
    }

    def flowMatching(pkt: Ethernet, expectedTags: FlowTag*) =
        new BePropertyMatcher[(SimulationResult, PacketContext)] {
            def apply(simRes: (SimulationResult, PacketContext)) =
                BePropertyMatchResult(simRes._1 match {
                    case AddVirtualWildcardFlow =>
                        actionsMatchesPacket(simRes._2.virtualFlowActions.toList, pkt) &&
                        (expectedTags forall simRes._2.flowTags.contains)
                    case _ =>
                        false
                } , s"a flow matching $pkt containing tags " +
                    s"{${expectedTags.toList}}")

        def actionsMatchesPacket(actions: List[FlowAction], pkt: Ethernet): Boolean =
            actions.count {
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
