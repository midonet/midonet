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

import org.midonet.midolman.topology.RouterManager
import org.midonet.midolman.{BackChannelHandler, BackChannelMessage, SimulationBackChannel}

import scala.collection.JavaConversions._
import scala.collection.mutable

import org.slf4j.helpers.NOPLogger
import com.typesafe.scalalogging.Logger

import org.scalactic.Prettifier
import org.scalatest.matchers._

import org.midonet.midolman.PacketWorkflow.{Drop, SimulationResult, AddVirtualWildcardFlow}
import org.midonet.midolman.PacketWorkflow._
import org.midonet.midolman.flows.FlowTagIndexer
import org.midonet.odp.flows.{FlowAction, FlowActionSetKey, FlowKeyEthernet, FlowKeyIPv4}
import org.midonet.packets.{IPv4Subnet, Ethernet, IPv4}
import org.midonet.sdn.flows.FlowTagger.FlowTag
import org.midonet.sdn.flows.VirtualActions.{FlowActionOutputToVrnBridge, FlowActionOutputToVrnPort}

trait CustomMatchers {

    def taggedWith(expectedTags: FlowTag*) =
        BePropertyMatcher((pktCtx: PacketContext) =>
            BePropertyMatchResult(
                expectedTags forall pktCtx.flowTags .contains,
                s"a PacketContext containing tags {${expectedTags.toList}"))

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
                    case msg: RouterManager.InvalidateFlows =>
                        msg.deletedRoutes forall {
                            case r =>
                                ips.contains(new IPv4Subnet(r.dstNetworkAddr, r.dstNetworkLength))
                        }
                        ips.contains(msg.asInstanceOf[RouterManager.InvalidateFlows].addedRoutes)
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
                    case msg: RouterManager.InvalidateFlows =>
                        msg.addedRoutes forall {
                            case r =>
                                ips.contains(new IPv4Subnet(r.dstNetworkAddr, r.dstNetworkLength))
                        }
                    ips.contains(msg.asInstanceOf[RouterManager.InvalidateFlows].addedRoutes)
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

    implicit class FlowInvalidatorOps(val flowInvalidator: SimulationBackChannel) {
        def clear(): List[FlowTag] = {
            val invalidatedTags = mutable.ListBuffer[FlowTag]()
            flowInvalidator.process(new FlowTagIndexer with BackChannelHandler {
                override val log: Logger = Logger(NOPLogger.NOP_LOGGER)
                override def handle(message: BackChannelMessage): Unit = {
                    message match {
                        case tag: FlowTag =>
                            invalidatedTags += tag
                        case _ =>
                    }
                }
            })
            invalidatedTags.toList
        }

        def get(): List[FlowTag] = {
            val invalidatedTags = clear()
            invalidatedTags foreach flowInvalidator.tell
            invalidatedTags
        }
    }
}
