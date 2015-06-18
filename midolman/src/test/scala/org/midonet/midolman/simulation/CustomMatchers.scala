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

import org.slf4j.helpers.NOPLogger
import com.typesafe.scalalogging.Logger

import org.scalactic.Prettifier
import org.scalatest.matchers._

import org.midonet.midolman.PacketWorkflow.{AddVirtualWildcardFlow, SendPacket, _}
import org.midonet.midolman.flows.{FlowInvalidation, FlowInvalidator}
import org.midonet.odp.flows.{FlowAction, FlowActionSetKey, FlowKeyEthernet, FlowKeyIPv4}
import org.midonet.packets.{Ethernet, IPv4}
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

    def invalidate(tags: FlowTag*) = new Matcher[FlowInvalidator] {
        def apply(invalidator: FlowInvalidator): MatchResult = {
            val invalidatedTags = invalidator.get()
            MatchResult(
                tags forall invalidatedTags.contains,
                s"$invalidatedTags did not target all of $tags",
                "invalidates the expected tags",
                Vector(invalidator, tags))
        }

        override def toString(): String = "invalidates (" + Prettifier.default(tags) + ")"
    }

    def haveInvalidated (tags: FlowTag*) = new Matcher[FlowInvalidation{var tags: List[FlowTag]}] {
        def apply(invalidation: FlowInvalidation{var tags: List[FlowTag]}): MatchResult = {
            MatchResult(
                tags forall invalidation.tags.contains,
                s"${invalidation.tags} does not contain all of $tags",
                "invalidates the expected tags",
                Vector(invalidation, tags))
        }

        override def toString(): String = "have invalidated (" + Prettifier.default(tags) + ")"
    }

    implicit class FlowInvalidatorOps(val flowInvalidator: FlowInvalidator) {
        def clear(): List[FlowTag] = {
            val invalidatedTags = mutable.ListBuffer[FlowTag]()
            flowInvalidator.processAll(new FlowInvalidation {
                override val log: Logger = Logger(NOPLogger.NOP_LOGGER)
                override def invalidateFlowsFor(tag: FlowTag) = {
                    invalidatedTags += tag
                }
            })
            invalidatedTags.toList
        }

        def get(): List[FlowTag] = {
            val invalidatedTags = clear()
            invalidatedTags foreach flowInvalidator.scheduleInvalidationFor
            invalidatedTags
        }
    }
}
