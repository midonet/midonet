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
package org.midonet.midolman.topology

import java.util.UUID

import scala.concurrent.duration._

import com.typesafe.config.{Config, ConfigValueFactory}

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.concurrent.Eventually._
import org.scalatest.time.{Seconds, Span}

import org.midonet.cluster.data.storage.Storage
import org.midonet.cluster.models.Commons.{Condition, Int32Range, Int32RangeOrBuilder}
import org.midonet.cluster.models.Topology.{Chain => TopoChain, Network => TopoBridge, Host, Rule}
import org.midonet.cluster.models.Topology.Rule.Action
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.NotYetException
import org.midonet.midolman.rules.RuleResult
import org.midonet.midolman.simulation.{Chain, Bridge}
import org.midonet.midolman.simulation.Port
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.packets.Ethernet
import org.midonet.sdn.flows.FlowTagger
import org.midonet.sdn.flows.FlowTagger.FlowTag
import org.midonet.util.concurrent._

@RunWith(classOf[JUnitRunner])
class RedirectRuleSimulationTest extends MidolmanSpec with TopologyBuilder {

    private var vt: VirtualTopology = _
    private var store: Storage = _
    val mac1 = "02:00:00:00:ee:00"
    val mac2 = "02:00:00:00:ee:11"

    registerActors(
        VirtualTopologyActor -> (() => new VirtualTopologyActor()))

    protected override def beforeTest(): Unit = {
        vt = injector.getInstance(classOf[VirtualTopology])
        store = injector.getInstance(classOf[MidonetBackend]).store
    }

    protected override def fillConfig(config: Config) = {
        super.fillConfig(config).withValue("zookeeper.use_new_stack",
                                           ConfigValueFactory.fromAnyRef(true))
    }

    def tryGet(thunk: () => Unit): Unit = {
        try {
            thunk()
        } catch {
            case e: NotYetException => e.waitFor.await(3 seconds)
        }
    }

    def makePort(host: Host, ifName: String, bridge: TopoBridge,
                 infilter: TopoChain = null, outfilter: TopoChain = null) = {
        createBridgePort(
            hostId = Some(host.getId),
            interfaceName = Some(ifName),
            adminStateUp = true,
            inboundFilterId = infilter match {
                case null => None
                case f => Some(f.getId)
            },
            outboundFilterId = outfilter match {
                case null => None
                case f => Some(f.getId)
            },
            bridgeId = Some(bridge.getId.asJava))
    }

    def packet(srcMac: String, dstMac: String, vlan: Option[Short] = None,
                  dstUdpPort: Short = 54) = {
        import org.midonet.packets.util.PacketBuilder._

        vlan match {
            case None =>
                {
                    eth addr srcMac -> dstMac
                } << {
                    ip4 addr "10.0.0.10" --> "10.0.0.11"
                } << {
                    udp ports 53 ---> dstUdpPort
                } <<
                payload(UUID.randomUUID().toString)
            case Some(v) =>
                {
                    eth addr srcMac -> dstMac vlan (v)
                } << {
                    ip4 addr "10.0.0.10" --> "10.0.0.11"
                } << {
                    udp ports 53 ---> dstUdpPort
                } <<
                payload(UUID.randomUUID().toString)
        }
    }

    def checkPacket(when: String, then: String, frame: Ethernet,
                    srcPortId: UUID, dstPortId: UUID,
                    expectedVlan: Option[Short] = None)
                   (expectedTags: FlowTag*) = {
        When(when)
        val packetContext = packetContextFor(frame, srcPortId)
        val result = simulate(packetContext)

        Then(then)
        result should be(toPort(dstPortId)(expectedTags : _*))
        expectedVlan match {
            case None =>
                result._2.wcmatch.getVlanIds.size() should be(0)
            case Some(vlan) =>
                //result should be(hasPushVlan())
                result._2.wcmatch.getVlanIds.get(0) should be(vlan)
        }
    }

    def redirectRuleBuilder(chainId: Option[UUID], targetPortId: UUID,
                            ingress: Boolean = false,
                            failOpen: Boolean = false,
                            cond: (Condition.Builder) => Unit = null): Rule.Builder = {
        val ruleBuilder = createRedirectRuleBuilder(
            chainId = chainId,
            targetPortId = targetPortId,
            ingress = ingress,
            failOpen = failOpen)
        val condBuilder = ruleBuilder.getConditionBuilder
        if (cond != null)
            cond(condBuilder)
        ruleBuilder
    }

    def literalRuleBuilder(chainId: Option[UUID],
                           action: Option[Rule.Action],
                           cond: (Condition.Builder) => Unit = null): Rule.Builder = {
        val ruleBuilder = createLiteralRuleBuilder(chainId = chainId,
                                                   action = action)
        val condBuilder = ruleBuilder.getConditionBuilder
        if (cond != null)
            cond(condBuilder)
        ruleBuilder
    }

    feature("Test redirect rules") {
        scenario("Test redirect without vlans") {
            val host = createHost()

            val leftBridge = createBridge(name = Some("LeftBridge"))
            val rightBridge = createBridge(name = Some("RightBridge"))

            var leftPortInFilter = createChain(name = Some("LeftInFilter"))

            val leftPort = makePort(host, "left_if", leftBridge, leftPortInFilter)
            val rightPort = makePort(host, "right_if", rightBridge)

            store.create(host)
            store.create(leftBridge)
            store.create(rightBridge)
            store.create(leftPortInFilter)
            store.create(leftPort)
            store.create(rightPort)

            val leftRule = redirectRuleBuilder(
                chainId = Some(leftPortInFilter.getId),
                targetPortId = rightPort.getId).build()
            store.create(leftRule)

            tryGet(() => VirtualTopology.tryGet[Port](leftPort.getId))
            tryGet(() => VirtualTopology.tryGet[Port](rightPort.getId))
            tryGet(() => VirtualTopology.tryGet[Bridge](leftBridge.getId))
            tryGet(() => VirtualTopology.tryGet[Bridge](rightBridge.getId))

            checkPacket("A packet ingresses the left port",
                        "It's redirected out the right port",
                        packet(mac1, mac2, None), leftPort.getId,
                        rightPort.getId)(
                    FlowTagger.tagForPort(leftPort.getId),
                    FlowTagger.tagForPort(rightPort.getId))
        }

        scenario("Test redirect with vlans") {
            val host = createHost()

            val vmBridge = createBridge(name = Some("vmBridge"),
                                        adminStateUp = true)
            val svcBridge = createBridge(name = Some("svcBridge"),
                                         adminStateUp = true)

            var vm1In = createChain(name = Some("vm1In"))
            var vm1Out = createChain(name = Some("vm1Out"))

            var vm2In = createChain(name = Some("vm2In"))
            var vm2Out = createChain(name = Some("vm2Out"))

            var svc1In = createChain(name = Some("svc1In"))
            var svc1Out = createChain(name = Some("svc1Out"))

            var svc2In = createChain(name = Some("svc2In"))
            var svc2Out = createChain(name = Some("svc2Out"))

            val vm1Port = makePort(host, "if_vm1", vmBridge, vm1In, vm1Out)
            val vm2Port = makePort(host, "if_vm2", vmBridge, vm2In, vm2Out)
            var svc1Port = makePort(host, "if_svc1", svcBridge, svc1In, svc1Out)
            val svc2Port = makePort(host, "if_svc2", svcBridge, svc2In, svc2Out)

            List(host, vmBridge, svcBridge,
                 vm1In, vm1Out, vm2In, vm2Out,
                 svc1In, svc1Out, svc2In, svc2Out,
                 vm1Port, vm2Port, svc1Port, svc2Port
            ).foreach {
                store.create(_)
            }

            // vm1Port's ingress rules
            // Packets without vlan should go to service 1 with vlan 10
            val vm1InR1 = redirectRuleBuilder(
                chainId = Some(vm1In.getId),
                targetPortId = svc1Port.getId,
                cond = {c => c.setNoVlan(true)})
                .setPushVlan(10).build()
            // Packets with vlan 20 should have their vlan popped and accepted
            val vm1InR2 = literalRuleBuilder(
                chainId = Some(vm1In.getId),
                action = Some(Action.ACCEPT),
                cond = {c => c.setVlan(20)})
                .setPopVlan(true).build()

            // vm1Port's egress rules
            // Packets without vlan should go to service 1 with vlan 11
            val vm1OutR1 = redirectRuleBuilder(
                chainId = Some(vm1Out.getId),
                targetPortId = svc1Port.getId,
                cond = {c => c.setNoVlan(true)})
                .setPushVlan(11).build()
            // No rule matching packets with vlan 21: outgoing don't re-traverse filters

            // svc1Port's ingress rules
            // Pop vlan 10, redirect to service 2 with vlan 20
            val svc1InR1 = redirectRuleBuilder(
                chainId = Some(svc1In.getId),
                targetPortId = svc2Port.getId,
                cond = {c => c.setVlan(10)})
                .setPopVlan(true).setPushVlan(20).build()
            // Pop vlan 11, redirect to service 2 with vlan 21
            val svc1InR2 = redirectRuleBuilder(
                chainId = Some(svc1In.getId),
                targetPortId = svc2Port.getId,
                cond = {c => c.setVlan(11)})
                .setPopVlan(true).setPushVlan(21).build()

            // svc1Port's egress rule: drop all
            val svc1OutR1 = literalRuleBuilder(
                chainId = Some(svc1Out.getId),
                action = Some(Action.DROP)).build()

            // svc2Port's ingress rules
            // Match vlan 20, redirect to vm1Port - ingress
            val svc2InR1 = redirectRuleBuilder(
                chainId = Some(svc2In.getId),
                targetPortId = vm1Port.getId,
                ingress = true,
                cond = {c => c.setVlan(20)})
                .build()
            // Match vlan 21, redirect to vm1Port - egress
            val svc2InR2 = redirectRuleBuilder(
                chainId = Some(svc2In.getId),
                targetPortId = vm1Port.getId,
                ingress = false,
                cond = {c => c.setVlan(21)})
                .setPopVlan(true).build()

            // svc2Port's egress rule: drop all
            val svc2OutR1 = literalRuleBuilder(
                chainId = Some(svc2Out.getId),
                action = Some(Action.DROP)).build()

            List(vm1InR1, vm1InR2, vm1OutR1,
                 svc1InR1, svc1InR2, svc1OutR1,
                 svc2InR1, svc2InR2, svc2OutR1
            ).foreach {
                store.create(_)
            }

            // Load the cache to avoid NotYetException at simulation time
            for (port <- List(vm1Port, vm2Port, svc1Port, svc2Port))
                tryGet(() => VirtualTopology.tryGet[Port](port.getId))
            tryGet(() => VirtualTopology.tryGet[Bridge](vmBridge.getId))
            tryGet(() => VirtualTopology.tryGet[Bridge](svcBridge.getId))

            // Set all the ports to "Active"
            for (port <- List(vm1Port, vm2Port, svc1Port, svc2Port)) {
                VirtualTopology.tryGet[Port](port.getId).toggleActive(true)
            }

            // Before adding insertions, send forward and return packets to
            // seed the MAC tables. This avoids bridge flood actions and
            // allows 'checkPacket' to always check for a single output port.
            simulate(packetContextFor(packet(mac1, mac2), vm1Port.getId))
            simulate(packetContextFor(packet(mac2, mac1), vm2Port.getId))

            checkPacket("A packet ingresses vm1Port",
                        "It's redirected out svc1Port with vlan 20",
                        packet(mac1, mac2, None),
                        vm1Port.getId, svc1Port.getId, Some(10))(
                    FlowTagger.tagForPort(vm1Port.getId),
                    FlowTagger.tagForPort(svc1Port.getId))

            checkPacket("The incoming packet ingresses svc1Port",
                        "It's redirected out svc2Port with vlan 20",
                        packet(mac1, mac2, Some(10)),
                        svc1Port.getId, svc2Port.getId, Some(20))(
                    FlowTagger.tagForPort(svc1Port.getId),
                    FlowTagger.tagForPort(svc2Port.getId))

            checkPacket("The incoming packet ingresses svc2Port",
                        "It traverses the bridge and egresses vm2Port without vlan",
                        packet(mac1, mac2, Some(20)),
                        svc2Port.getId, vm2Port.getId)(
                    FlowTagger.tagForPort(svc2Port.getId),
                    FlowTagger.tagForPort(vm1Port.getId),
                    FlowTagger.tagForBridge(vmBridge.getId),
                    FlowTagger.tagForPort(vm2Port.getId))

            checkPacket("A return packet from vm2Port arrives at vm1Port",
                        "It's redirected out svc1Port with vlan 11",
                        packet(mac2, mac1),
                        vm2Port.getId, svc1Port.getId, Some(11))(
                    FlowTagger.tagForPort(vm2Port.getId),
                    FlowTagger.tagForBridge(vmBridge.getId),
                    FlowTagger.tagForPort(vm1Port.getId),
                    FlowTagger.tagForPort(svc1Port.getId))

            checkPacket("The return packet ingresses svc1Port",
                        "It's redirected out svc2Port with vlan 21",
                        packet(mac2, mac1, Some(11)),
                        svc1Port.getId, svc2Port.getId, Some(21))(
                    FlowTagger.tagForPort(svc1Port.getId),
                    FlowTagger.tagForPort(svc2Port.getId))

            checkPacket("The return packet ingresses svc2Port",
                        "It's redirected out vm1Port without vlan",
                        packet(mac2, mac1, Some(21)),
                        svc2Port.getId, vm1Port.getId)(
                    FlowTagger.tagForPort(svc2Port.getId),
                    FlowTagger.tagForPort(vm1Port.getId))

            var p = VirtualTopology.tryGet[Port](svc1Port.getId)
            p.adminStateUp should be(true)

            // Now set svc1Port down. Traffic from VM1 should be dropped because FAIL_OPEN is false.
            svc1Port = svc1Port.toBuilder().setAdminStateUp(false).build()
            store.update(svc1Port)
            eventually (timeout(Span(2, Seconds))) {
                p = VirtualTopology.tryGet[Port](svc1Port.getId)
                p.adminStateUp should be(false)
            }

            When("An incoming packet ingresses vm1Port")
            var packetContext = packetContextFor(packet(mac1, mac2),
                                                 vm1Port.getId)
            var result = simulate(packetContext)

            Then("It's dropped because svc1 is down")
            result should be(dropped(FlowTagger.tagForPort(vm1Port.getId),
                                  FlowTagger.tagForPort(svc1Port.getId)))

            // Now set FAIL_OPEN to true on the Redirect to svc1. Traffic from
            // VM1 should skip svc1 and go to svc2.
            val vm1InR3 = redirectRuleBuilder(
                chainId = Some(vm1In.getId),
                targetPortId = svc1Port.getId,
                failOpen = true,
                cond = {c => c.setNoVlan(true)})
                .setPushVlan(10).build()
            store.delete(classOf[Rule], vm1InR1.getId)
            store.create(vm1InR3)
            eventually (timeout(Span(2, Seconds))) {
                var c = VirtualTopology.tryGet[Chain](vm1In.getId)
                c.rules.get(0).action should be(RuleResult.Action.ACCEPT)
            }

            checkPacket("A forward packet ingresses vm1Port",
                        "It skips svc1Port and egresses svc2Port with vlan 20",
                        packet(mac1, mac2),
                        vm1Port.getId, svc2Port.getId, Some(20))(
                    FlowTagger.tagForPort(vm1Port.getId),
                    FlowTagger.tagForPort(svc1Port.getId), // still traversed
                    FlowTagger.tagForPort(svc2Port.getId))
        }

        scenario("Test port with several inbound and outbound chains") {
            val host = createHost()

            val vmBridge = createBridge(name = Some("vmBridge"),
                                        adminStateUp = true)

            var inFilter = createChain(name = Some("inFilter"))
            var outFilter = createChain(name = Some("outFilter"))

            var inChain1 = createChain(name = Some("inChain1"))
            var outChain1 = createChain(name = Some("outChain2"))

            var inChain2 = createChain(name = Some("inChain2"))
            var outChain2 = createChain(name = Some("outChain2"))

            var vm1Port = createBridgePort(
                hostId = Some(host.getId),
                interfaceName = Some("if_vm1"),
                adminStateUp = true,
                inboundFilterId = Some(inFilter.getId),
                outboundFilterId = Some(outFilter.getId),
                bridgeId = Some(vmBridge.getId)
            )
            vm1Port = vm1Port.toBuilder().addInboundChain(inChain1.getId)
                .addInboundChain(inChain2.getId)
                .addOutboundChain(outChain1.getId)
                .addOutboundChain(outChain2.getId).build()

            val vm2Port = createBridgePort(hostId = Some(host.getId),
                                           interfaceName = Some("if_vm2"),
                                           adminStateUp = true,
                                           bridgeId = Some(vmBridge.getId))

            List(host, vmBridge,
                 inFilter, outFilter,
                 inChain1, outChain1,
                 inChain2, outChain2,
                 vm1Port, vm2Port
            ).foreach {
                store.create(_)
            }

            // - drop dst udp 40 in inChain1
            // - redirect to port2 when dst udp 41 in Chain1
            val inChain1R1 = literalRuleBuilder(
                action = Some(Action.DROP),
                chainId = Some(inChain1.getId),
                cond = {c => c.setTpDst(
                    Int32Range.newBuilder().setStart(40).setEnd(40).build())})
                .build()
            val inChain1R2 = redirectRuleBuilder(
                chainId = Some(inChain1.getId),
                targetPortId = vm2Port.getId,
                cond = {c => c.setTpDst(
                    Int32Range.newBuilder().setStart(41).setEnd(41).build())})
                .build()

            // - drop dst udp 41 in inChain2 (ignored)
            // - drop dst udp 42 in inChain2
            // - redirect to port2 when dst udp 43 in InFilter
            val inChain2R1 = literalRuleBuilder(
                action = Some(Action.DROP),
                chainId = Some(inChain2.getId),
                cond = {c => c.setTpDst(
                    Int32Range.newBuilder().setStart(41).setEnd(41).build())})
                .build()
            val inChain2R2 = literalRuleBuilder(
                action = Some(Action.DROP),
                chainId = Some(inChain2.getId),
                cond = {c => c.setTpDst(
                    Int32Range.newBuilder().setStart(42).setEnd(42).build())})
                .build()
            val inChain2R3 = redirectRuleBuilder(
                chainId = Some(inChain2.getId),
                targetPortId = vm2Port.getId,
                cond = {c => c.setTpDst(
                    Int32Range.newBuilder().setStart(43).setEnd(43).build())})
                .build()

            // Inbound filters:
            // - drop dst udp 43 in inFilter (ignored)
            // - drop dst udp 44 in inFilter (ignored)
            val inFilterR1 = literalRuleBuilder(
                action = Some(Action.DROP),
                chainId = Some(inFilter.getId),
                cond = {c => c.setTpDst(
                    Int32Range.newBuilder().setStart(43).setEnd(43).build())})
                .build()
            val inFilterR2 = literalRuleBuilder(
                action = Some(Action.DROP),
                chainId = Some(inFilter.getId),
                cond = {c => c.setTpDst(
                    Int32Range.newBuilder().setStart(44).setEnd(44).build())})
                .build()

            List(inFilterR1, inFilterR2,
                 inChain1R1, inChain1R2,
                 inChain2R1, inChain2R2, inChain2R3
            ).foreach {
                store.create(_)
            }

            /*inChain1 = inChain1.toBuilder
                .addRuleIds(inChain1R1.getId)
                .addRuleIds(inChain1R2.getId).build()
            inChain2 = inChain2.toBuilder
                .addRuleIds(inChain2R1.getId)
                .addRuleIds(inChain2R2.getId)
                .addRuleIds(inChain2R3.getId).build()
            inFilter = inFilter.toBuilder
                .addRuleIds(inFilterR1.getId)
                .addRuleIds(inFilterR2.getId).build()

            List(inChain1, inChain2, inFilter).foreach {
                store.update(_)
            }*/

            // Load the cache to avoid NotYetException at simulation time
            for (port <- List(vm1Port, vm2Port))
                tryGet(() => VirtualTopology.tryGet[Port](port.getId))
            tryGet(() => VirtualTopology.tryGet[Bridge](vmBridge.getId))

            // Set all the ports to "Active"
            for (port <- List(vm1Port, vm2Port)) {
                VirtualTopology.tryGet[Port](port.getId).toggleActive(true)
            }

            // Incoming/Outgoing are from vm1Port's perspective
            When("A udp packet to port 40 ingresses vm1Port")
            var packetContext = packetContextFor(
                packet(mac1, mac2, dstUdpPort = 40), vm1Port.getId)
            var result = simulate(packetContext)
            Then("It's dropped")
            result should be(dropped(FlowTagger.tagForPort(vm1Port.getId)))

            When("A udp packet to port 41 ingresses vm1Port")
            packetContext = packetContextFor(
                packet(mac1, mac2, dstUdpPort = 41), vm1Port.getId)
            result = simulate(packetContext)
            Then("It's redirected out vm2Port")
            result should be(toPort(vm2Port.getId)
                                 (FlowTagger.tagForPort(vm1Port.getId),
                                  FlowTagger.tagForPort(vm2Port.getId)))

            When("A udp packet to port 42 ingresses vm1Port")
            packetContext = packetContextFor(
                packet(mac1, mac2, dstUdpPort = 42), vm1Port.getId)
            result = simulate(packetContext)
            Then("It's dropped")
            result should be(dropped(FlowTagger.tagForPort(vm1Port.getId)))

            When("A udp packet to port 43 ingresses vm1Port")
            packetContext = packetContextFor(
                packet(mac1, mac2, dstUdpPort = 43), vm1Port.getId)
            result = simulate(packetContext)
            Then("It's redirected out vm2Port")
            result should be(toPort(vm2Port.getId)
                                 (FlowTagger.tagForPort(vm1Port.getId),
                                  FlowTagger.tagForPort(vm2Port.getId)))

            When("A udp packet to port 44 ingresses vm1Port")
            packetContext = packetContextFor(
                packet(mac1, mac2, dstUdpPort = 44), vm1Port.getId)
            result = simulate(packetContext)
            Then("It's dropped")
            result should be(dropped(FlowTagger.tagForPort(vm1Port.getId)))

            When("A udp packet to port 45 ingresses vm1Port")
            packetContext = packetContextFor(
                packet(mac1, mac2, dstUdpPort = 45), vm1Port.getId)
            result = simulate(packetContext)
            Then("It's accepted and flooded, so it egresses port2")
            result should be(toPort(vm2Port.getId)(
                                      FlowTagger.tagForPort(vm1Port.getId),
                                      FlowTagger.tagForBridge(vmBridge.getId),
                                      FlowTagger.tagForPort(vm2Port.getId)))
        }
    }
}
