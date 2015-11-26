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
import scala.collection.mutable
import scala.reflect.ClassTag

import org.scalactic.Prettifier
import org.scalatest.matchers._

import org.midonet.midolman.SimulationBackChannel
import org.midonet.midolman.PacketWorkflow.{Drop, SimulationResult, AddVirtualWildcardFlow}
import org.midonet.midolman.PacketWorkflow._
import org.midonet.midolman.flows.FlowTagIndexer
import org.midonet.odp.flows.{FlowActionPopVLAN, FlowActionPushVLAN}
import org.midonet.midolman.simulation.Simulator.ToPortAction
import org.midonet.midolman.topology.RouterMapper.InvalidateFlows
import org.midonet.odp.flows.{FlowAction, FlowActionSetKey, FlowKeyEthernet, FlowKeyIPv4}
import org.midonet.packets.{IPv4Subnet, Ethernet, IPv4}
import org.midonet.sdn.flows.FlowTagger.FlowTag

trait CustomMatchers {

    def taggedWith(expectedTags: FlowTag*) =
        BePropertyMatcher((pktCtx: PacketContext) =>
            BePropertyMatchResult(
                expectedTags forall pktCtx.flowTags .contains,
                s"a PacketContext containing tags {${expectedTags.toList}"))

    def processedWith(actionTypes: Class[_]*) =
        BePropertyMatcher((pktCtx: PacketContext) =>
            BePropertyMatchResult(
                pktCtx.virtualFlowActions map (_.getClass) sameElements actionTypes.toList ,
                s"a PacketContext containing action types {${actionTypes.toList}"))

    def toPorts(ports: UUID*) =
        BePropertyMatcher((pktCtx: PacketContext) =>
            BePropertyMatchResult(
                (ports map ToPortAction forall pktCtx.virtualFlowActions .contains) &&
                ((pktCtx.virtualFlowActions filter(_.isInstanceOf[ToPortAction])).size == ports.length),
                s"a PacketContext forwarding to ports {${ports.toList}"))

    def dropped(expectedTags: FlowTag*) =
        new BePropertyMatcher[(SimulationResult, PacketContext)] {
            def apply(simRes: (SimulationResult, PacketContext)) =
                BePropertyMatchResult(simRes._1 match {
                    case x if x == Drop || x == ErrorDrop || x == ShortDrop =>
                        expectedTags forall simRes._2.flowTags.contains
                    case _ =>
                        false
                }, s"a drop flow containing tags {${expectedTags.toList}")
        }

    def hasPopVlan() = new BePropertyMatcher[(SimulationResult, PacketContext)] {
        def apply(simRes: (SimulationResult, PacketContext)) =
            BePropertyMatchResult(
                simRes._2.virtualFlowActions.toList.exists({
                    case pop: FlowActionPopVLAN => true
                    case _ => false
                }), s"a pop vlan action ")
    }

    def hasPushVlan(vlan: Short) = new BePropertyMatcher[(SimulationResult, PacketContext)] {
        def apply(simRes: (SimulationResult, PacketContext)) =
            BePropertyMatchResult(
                simRes._2.virtualFlowActions.toList.exists({
                    case vl: FlowActionPushVLAN => vl.getTagControlIdentifier == vlan
                    case _ => false
                }), s"a push vlan action ")
    }

    def toPort(portId: UUID)(expectedTags: FlowTag*) =
        new BePropertyMatcher[(SimulationResult, PacketContext)] {
            def apply(simRes: (SimulationResult, PacketContext)) =
                BePropertyMatchResult((simRes._1 match {
                    case AddVirtualWildcardFlow =>
                        if (expectedTags forall simRes._2.flowTags.contains)
                            simRes._2.virtualFlowActions.toList
                        else Nil
                    case _ => Nil
                }).exists({
                    case ToPortAction(id) => id == portId
                    case _ => false
                }), s"a port action to $portId")
    }

    def toBridge(bridge: Bridge, brPorts: List[UUID], expectedTags: FlowTag*) =
        new BePropertyMatcher[(SimulationResult, PacketContext)] {
            def apply(simRes: (SimulationResult, PacketContext)) =
                BePropertyMatchResult(simRes._1 match {
                    case AddVirtualWildcardFlow =>
                        (expectedTags forall simRes._2.flowTags.contains) &&
                        (brPorts map ToPortAction).forall(simRes._2.virtualFlowActions.contains)
                }, s"a flood bridge action ${bridge.floodAction} on " +
                   s"${bridge.id} containing tags {${expectedTags.toList}}")
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
                            k.eth_dst,
                            pkt.getDestinationMACAddress.getAddress) &&
                        util.Arrays.equals(
                            k.eth_src,
                            pkt.getSourceMACAddress.getAddress)
                    case k: FlowKeyIPv4 if pkt.getPayload.isInstanceOf[IPv4] =>
                        val ipPkt = pkt.getPayload.asInstanceOf[IPv4]
                        k.ipv4_dst == ipPkt.getDestinationAddress &&
                        k.ipv4_src == ipPkt.getSourceAddress
                    case _ => false
                }
                case _ => false
            } == 2
    }

    def invalidate(tags: FlowTag*) = new Matcher[SimulationBackChannel] {
        def apply(invalidator: SimulationBackChannel): MatchResult = {
            val invalidatedTags = invalidator.get()
            MatchResult(
                tags forall invalidatedTags.contains,
                s"$invalidatedTags did not target all of $tags",
                "invalidates the expected tags",
                Vector(invalidator, tags))
        }

        override def toString(): String = "invalidates (" + Prettifier.default(tags) + ")"
    }

    def invalidateForDeletedRoutes(ips: IPv4Subnet*) = new Matcher[SimulationBackChannel] {
        def apply(backChannel: SimulationBackChannel): MatchResult = {
            val msgs = backChannel.get()
            MatchResult(
                msgs forall {
                    case msg: InvalidateFlows =>
                        msg.deletedRoutes forall {
                            case r =>
                                ips.contains(new IPv4Subnet(r.dstNetworkAddr, r.dstNetworkLength))
                        }
                        ips.contains(msg.asInstanceOf[InvalidateFlows].addedRoutes)
                    case _ => true
                },
                s"$msgs did not target all of $ips",
                "invalidates the expected tags",
                Vector(backChannel, ips))
        }

        override def toString(): String = "invalidates (" + Prettifier.default(ips) + ")"
    }

    def invalidateForNewRoutes(ips: IPv4Subnet*) = new Matcher[SimulationBackChannel] {
        def apply(backChannel: SimulationBackChannel): MatchResult = {
            val msgs = backChannel.get()
            MatchResult(
                msgs forall {
                    case msg: InvalidateFlows =>
                        msg.addedRoutes forall {
                            case r =>
                                ips.contains(new IPv4Subnet(r.dstNetworkAddr, r.dstNetworkLength))
                        }
                    ips.contains(msg.asInstanceOf[InvalidateFlows].addedRoutes)
                    case _ => true
                },
                s"$msgs did not target all of $ips",
                "invalidates the expected tags",
                Vector(backChannel, ips))
        }

        override def toString(): String = "invalidates (" + Prettifier.default(ips) + ")"
    }

    def haveInvalidated (tags: FlowTag*) = new Matcher[FlowTagIndexer{var tags: List[FlowTag]}] {
        def apply(invalidation: FlowTagIndexer{var tags: List[FlowTag]}): MatchResult = {
            MatchResult(
                tags forall invalidation.tags.contains,
                s"${invalidation.tags} does not contain all of $tags",
                "invalidates the expected tags",
                Vector(invalidation, tags))
        }

        override def toString(): String = "have invalidated (" + Prettifier.default(tags) + ")"
    }

    implicit class BackChannelOps(val backChannel: SimulationBackChannel) {
        def clear(): List[FlowTag] = {
            val invalidatedTags = mutable.ListBuffer[FlowTag]()
            while (backChannel.hasMessages) {
                backChannel.poll() match {
                    case tag: FlowTag =>
                        invalidatedTags += tag
                    case _ =>
                }
            }
            invalidatedTags.toList
        }

        def get(): List[FlowTag] = {
            val invalidatedTags = clear()
            invalidatedTags foreach backChannel.tell
            invalidatedTags
        }

        def find[T >: Null : ClassTag](): T = {
            while (backChannel.hasMessages) {
                backChannel.poll() match {
                    case msg: T => return msg
                    case _ =>
                }
            }
            null
        }
    }
}
