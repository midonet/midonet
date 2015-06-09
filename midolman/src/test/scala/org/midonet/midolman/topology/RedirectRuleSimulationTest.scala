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
import org.midonet.cluster.models.Commons.{Int32Range, Int32RangeOrBuilder}
import org.midonet.cluster.models.Topology.{Chain => TopoChain, Network => TopoBridge, Host}
import org.midonet.cluster.models.Topology.Rule.Action
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.NotYetException
import org.midonet.midolman.rules.RuleResult
import org.midonet.midolman.simulation.{Chain, Bridge}
import org.midonet.midolman.topology.devices.Port
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.sdn.flows.FlowTagger
import org.midonet.util.concurrent._

@RunWith(classOf[JUnitRunner])
class RedirectRuleSimulationTest extends MidolmanSpec with TopologyBuilder {

    private var vt: VirtualTopology = _
    private var store: Storage = _

    private val bridgeId = UUID.randomUUID

    registerActors(
        VirtualTopologyActor -> (() => new VirtualTopologyActor()))

    protected override def beforeTest(): Unit = {
        vt = injector.getInstance(classOf[VirtualTopology])
        store = injector.getInstance(classOf[MidonetBackend]).store
        store.create(createBridge(id = bridgeId))
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

    def makePort(host: Host, ifName: String, infilter: TopoChain,
                 outfilter: TopoChain, bridge: TopoBridge) = {
        createBridgePort(
            hostId = Some(host.getId),
            interfaceName = Some(ifName),
            adminStateUp = true,
            inboundFilterId = Some(infilter.getId),
            outboundFilterId = Some(outfilter.getId),
            bridgeId = Some(bridge.getId))
    }

    def packet = makePacket()

    def makePacket(dstPort: Short = 54) = {
        import org.midonet.packets.util.PacketBuilder._

        {
            eth addr "02:00:00:00:ee:00" -> "02:00:00:00:ee:11"
        } << {
            ip4 addr "10.0.0.10" --> "10.0.0.11"
        } << {
            udp ports 53 ---> dstPort
        } <<
        payload(UUID.randomUUID().toString)
    }

    def packetWithVlan(vlan: Short) = {
        import org.midonet.packets.util.PacketBuilder._

        {
            eth addr "02:00:00:00:ee:00" -> "02:00:00:00:ee:11" vlan (vlan)
        } << {
            ip4 addr "10.0.0.10" --> "10.0.0.11"
        } << {
            udp ports 53 ---> 54
        } <<
        payload(UUID.randomUUID().toString)
    }

    def returnPacket = {
        import org.midonet.packets.util.PacketBuilder._

        {
            eth addr "02:00:00:00:ee:11" -> "02:00:00:00:ee:00"
        } << {
            ip4 addr "10.0.0.11" --> "10.0.0.10"
        } << {
            udp ports 54 ---> 53
        } <<
        payload(UUID.randomUUID().toString)
    }

    def returnPacketWithVlan(vlan: Short) = {
        import org.midonet.packets.util.PacketBuilder._

        {
            eth addr "02:00:00:00:ee:11" -> "02:00:00:00:ee:00" vlan (vlan)
        } << {
            ip4 addr "10.0.0.11" --> "10.0.0.10"
        } << {
            udp ports 54 ---> 53
        } <<
        payload(UUID.randomUUID().toString)
    }

    feature("Test redirect without vlans") {
        scenario("Test") {
            val host = createHost()

            val leftBridge = createBridge(name = Some("LeftBridge"))
            val rightBridge = createBridge(name = Some("RightBridge"))

            var leftPortInFilter = createChain(name = Some("LeftInFilter"))

            val leftPort = createBridgePort(
                hostId = Some(host.getId),
                interfaceName = Some("leftInterface"),
                adminStateUp = true,
                inboundFilterId = Some(
                    leftPortInFilter.getId),
                bridgeId = Some(leftBridge.getId))

            val rightPort = createBridgePort(
                hostId = Some(host.getId),
                interfaceName = Some("rightInterface"),
                adminStateUp = true,
                bridgeId = Some(
                    rightBridge.getId.asJava))

            val leftRule = createRedirectRuleBuilder(
                chainId = Some(leftPortInFilter.getId),
                targetPortId = rightPort.getId).build()

            leftPortInFilter = leftPortInFilter.toBuilder.
                addRuleIds(leftRule.getId).build()

            store.create(host)
            store.create(leftBridge)
            store.create(rightBridge)
            store.create(leftPortInFilter)
            store.create(leftPort)
            store.create(rightPort)

            // Create rules
            store.create(leftRule)

            tryGet(() => VirtualTopology.tryGet[Port](leftPort.getId))
            tryGet(() => VirtualTopology.tryGet[Port](rightPort.getId))
            tryGet(() => VirtualTopology.tryGet[Bridge](leftBridge.getId))
            tryGet(() => VirtualTopology.tryGet[Bridge](rightBridge.getId))

            When("A packet ingresses the left port")
            val packetContext = packetContextFor(packet, leftPort.getId)
            val result = simulate(packetContext)

            Then("It's redirected out the right port with the expected tags")
            result should be(toPort(rightPort.getId)
                                 (FlowTagger.tagForDevice(leftPort.getId),
                                  FlowTagger.tagForDevice(rightPort.getId)))
        }
    }

    feature("Test redirect with vlans") {
        scenario("Test") {
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

            val vm1Port = makePort(host, "if_vm1", vm1In, vm1Out, vmBridge)
            val vm2Port = makePort(host, "if_vm2", vm2In, vm2Out, vmBridge)
            var svc1Port = makePort(host, "if_svc1", svc1In, svc1Out, svcBridge)
            val svc2Port = makePort(host, "if_svc2", svc2In, svc2Out, svcBridge)

            // vm1Port's ingress rules
            // Packets without vlan should go to service 1 with vlan 10
            val vm1InR1 = createRedirectRuleBuilder(
                chainId = Some(vm1In.getId),
                targetPortId = svc1Port.getId)
                .setNoVlan(true).setPushVlan(10).build()
            // Packets with vlan 20 should have their vlan popped and accepted
            val vm1InR2 = createLiteralRuleBuilder(
                chainId = Some(vm1In.getId),
                action = Some(Action.ACCEPT))
                .setVlan(20).setPopVlan(true).build()
            vm1In = vm1In.toBuilder
                .addRuleIds(vm1InR1.getId)
                .addRuleIds(vm1InR2.getId).build()

            // vm1Port's egress rules
            // Packets without vlan should go to service 1 with vlan 11
            val vm1OutR1 = createRedirectRuleBuilder(
                chainId = Some(vm1Out.getId),
                targetPortId = svc1Port.getId)
                .setNoVlan(true).setPushVlan(11).build()
            // No rule matching packets with vlan 21: outgoing don't re-traverse filters
            vm1Out = vm1Out.toBuilder
                .addRuleIds(vm1OutR1.getId).build()

            // svc1Port's ingress rules
            // Pop vlan 10, redirect to service 2 with vlan 20
            val svc1InR1 = createRedirectRuleBuilder(
                chainId = Some(svc1In.getId),
                targetPortId = svc2Port.getId)
                .setVlan(10).setPopVlan(true).setPushVlan(20).build()
            // Pop vlan 11, redirect to service 2 with vlan 21
            val svc1InR2 = createRedirectRuleBuilder(
                chainId = Some(svc1In.getId),
                targetPortId = svc2Port.getId)
                .setVlan(11).setPopVlan(true).setPushVlan(21).build()
            svc1In = svc1In.toBuilder
                .addRuleIds(svc1InR1.getId).addRuleIds(svc1InR2.getId).build()
            // svc1Port's egress rule: drop all
            val svc1OutR1 = createLiteralRuleBuilder(
                chainId = Some(svc1Out.getId),
                action = Some(Action.DROP)).build()
            svc1Out = svc1Out.toBuilder
                .addRuleIds(svc1OutR1.getId).build()

            // svc2Port's ingress rules
            // Match vlan 20, redirect to vm1Port - ingress
            val svc2InR1 = createRedirectRuleBuilder(
                chainId = Some(svc2In.getId),
                targetPortId = vm1Port.getId,
                ingress = true)
                .setVlan(20).build()
            // Match vlan 21, redirect to vm1Port - egress
            val svc2InR2 = createRedirectRuleBuilder(
                chainId = Some(svc2In.getId),
                targetPortId = vm1Port.getId,
                ingress = false)
                .setVlan(21).setPopVlan(true).build()
            svc2In = svc2In.toBuilder
                .addRuleIds(svc2InR1.getId).addRuleIds(svc2InR2.getId).build()
            // svc2Port's egress rule: drop all
            val svc2OutR1 = createLiteralRuleBuilder(
                chainId = Some(svc2Out.getId),
                action = Some(Action.DROP)).build()
            svc2Out = svc2Out.toBuilder
                .addRuleIds(svc2OutR1.getId).build()

            List(host, vmBridge, svcBridge,
                 vm1In, vm1Out, vm2In, vm2Out,
                 svc1In, svc1Out, svc2In, svc2Out,
                 vm1Port, vm2Port, svc1Port, svc2Port,
                 vm1InR1, vm1InR2, vm1OutR1,
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
                VirtualTopology.tryGet[Port](port.getId)._active = true
            }

            // Incoming/Outgoing are from vm1Port's perspective
            When("An incoming packet ingresses vm1Port")
            var packetContext = packetContextFor(packet, vm1Port.getId)
            var result = simulate(packetContext)

            Then("It's redirected out svc1Port with vlan 10")
            //result should be(hasPushVlan(10))
            result should be(toPort(svc1Port.getId)
                                 (FlowTagger.tagForDevice(vm1Port.getId),
                                  FlowTagger.tagForDevice(svc1Port.getId)))
            result._2.wcmatch.getVlanIds.get(0) should be(10)

            When("The incoming packet ingresses svc1Port")
            packetContext = packetContextFor(packetWithVlan(10), svc1Port.getId)
            result = simulate(packetContext)

            Then("It's redirected out svc2Port with vlan 20")
            //result should be(hasPopVlan())
            //result should be(hasPushVlan(20))
            result should be(toPort(svc2Port.getId)
                                 (FlowTagger.tagForDevice(svc1Port.getId),
                                  FlowTagger.tagForDevice(svc2Port.getId)))
            result._2.wcmatch.getVlanIds.get(0) should be(20)

            When("The incoming packet ingresses svc2Port")
            packetContext = packetContextFor(packetWithVlan(20), svc2Port.getId)
            result = simulate(packetContext)

            Then(
                "It's redirected into vm1Port, traverses the bridge and gets to vm2Port")
            //result should be(hasPopVlan())
            result should be(toBridge(vmBridge.getId,
                                      List(vm2Port.getId),
                                      FlowTagger.tagForDevice(svc2Port.getId),
                                      FlowTagger.tagForDevice(vm1Port.getId),
                                      FlowTagger.tagForDevice(vmBridge.getId),
                                      FlowTagger.tagForDevice(vm2Port.getId)))
            result._2.wcmatch.getVlanIds.size() should be(0)

            When("An outgoing packet from vm2Port arrives at vm1Port")
            packetContext = packetContextFor(returnPacket, vm2Port.getId)
            result = simulate(packetContext)

            Then("It's redirected out svc1Port with vlan 11")
            //result should be(hasPushVlan(11))
            result should be(toPort(svc1Port.getId)
                                 (FlowTagger.tagForDevice(vm2Port.getId),
                                  FlowTagger.tagForDevice(vmBridge.getId),
                                  FlowTagger.tagForDevice(vm1Port.getId),
                                  FlowTagger.tagForDevice(svc1Port.getId)))
            result._2.wcmatch.getVlanIds.get(0) should be(11)

            When("The outgoing packet ingresses svc1Port")
            packetContext = packetContextFor(returnPacketWithVlan(11),
                                             svc1Port.getId)
            result = simulate(packetContext)

            Then("It's redirected out svc2Port with vlan 21")
            //result should be(hasPopVlan())
            //result should be(hasPushVlan(21))
            result should be(toPort(svc2Port.getId)
                                 (FlowTagger.tagForDevice(svc1Port.getId),
                                  FlowTagger.tagForDevice(svc2Port.getId)))
            result._2.wcmatch.getVlanIds.get(0) should be(21)

            When("The outgoing packet ingresses svc2Port")
            packetContext = packetContextFor(returnPacketWithVlan(21),
                                             svc2Port.getId)
            result = simulate(packetContext)

            Then("It's redirected out of vm1Port")
            //result should be(hasPopVlan())
            result should be(toPort(vm1Port.getId)
                                 (FlowTagger.tagForDevice(svc2Port.getId),
                                  FlowTagger.tagForDevice(vm1Port.getId)))
            result._2.wcmatch.getVlanIds.size() should be(0)

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
            packetContext = packetContextFor(packet, vm1Port.getId)
            result = simulate(packetContext)

            Then("It's dropped because svc1 is down")
            //result should be(hasPushVlan(10))
            result should be(dropped(FlowTagger.tagForDevice(vm1Port.getId),
                                  FlowTagger.tagForDevice(svc1Port.getId)))

            // Now set FAIL_OPEN to true on the Redirect to svc1. Traffic from
            // VM1 should skip svc1 and go to svc2.
            val vm1InR3 = createRedirectRuleBuilder(
                chainId = Some(vm1In.getId),
                targetPortId = svc1Port.getId,
                failOpen = true)
                .setNoVlan(true).setPushVlan(10).build()
            vm1In = vm1In.toBuilder
                .removeRuleIds(0)
                .addRuleIds(vm1InR3.getId).build()
            var c = VirtualTopology.tryGet[Chain](vm1In.getId)
            c.getRules.get(0).action should be(RuleResult.Action.REDIRECT)
            store.create(vm1InR3)
            store.update(vm1In)
            eventually (timeout(Span(2, Seconds))) {
                c = VirtualTopology.tryGet[Chain](vm1In.getId)
                c.getRules.get(0).action should be(RuleResult.Action.ACCEPT)
            }

            When("An incoming packet ingresses vm1Port")
            packetContext = packetContextFor(packet, vm1Port.getId)
            result = simulate(packetContext)

            Then("It's redirected out svc2Port with vlan 20, skipping svc1")
            //result should be(hasPushVlan(20))
            result should be(toPort(svc2Port.getId)
                                 (FlowTagger.tagForDevice(vm1Port.getId),
                                  FlowTagger.tagForDevice(svc1Port.getId),
                                  FlowTagger.tagForDevice(svc2Port.getId)))
            result._2.wcmatch.getVlanIds.get(0) should be(20)
        }
    }

    feature("Test port with several inbound and outbound chains") {
        scenario("Test") {
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

            // Inbound filters:
            // - drop dst udp 40 in inFilter
            // - redirect to port2 when dst udp 41 in InFilter
            val inFilterR1 = createLiteralRuleBuilder(
                action = Some(Action.DROP),
                chainId = Some(inFilter.getId))
                .setTpDst(
                    Int32Range.newBuilder().setStart(40).setEnd(40).build())
                .build()
            val inFilterR2 = createRedirectRuleBuilder(
                chainId = Some(inFilter.getId),
                targetPortId = vm2Port.getId)
                .setTpDst(
                    Int32Range.newBuilder().setStart(41).setEnd(41).build())
                .build()
            inFilter = inFilter.toBuilder
                .addRuleIds(inFilterR1.getId)
                .addRuleIds(inFilterR2.getId).build()

            // - drop dst udp 41 in inChain1 (ignored)
            // - drop dst udp 42 in inChain1
            // - redirect to port2 when dst udp 43 in Chain1
            val inChain1R1 = createLiteralRuleBuilder(
                action = Some(Action.DROP),
                chainId = Some(inChain1.getId))
                .setTpDst(
                    Int32Range.newBuilder().setStart(41).setEnd(41).build())
                .build()
            val inChain1R2 = createLiteralRuleBuilder(
                action = Some(Action.DROP),
                chainId = Some(inChain1.getId))
                .setTpDst(
                    Int32Range.newBuilder().setStart(42).setEnd(42).build())
                .build()
            val inChain1R3 = createRedirectRuleBuilder(
                chainId = Some(inChain1.getId),
                targetPortId = vm2Port.getId)
                .setTpDst(
                    Int32Range.newBuilder().setStart(43).setEnd(43).build())
                .build()
            inChain1 = inChain1.toBuilder
                .addRuleIds(inChain1R1.getId)
                .addRuleIds(inChain1R2.getId)
                .addRuleIds(inChain1R3.getId).build()

            // - drop dst udp 43 in inChain2 (ignored)
            // - drop dst udp 44 in inChain2
            val inChain2R1 = createLiteralRuleBuilder(
                action = Some(Action.DROP),
                chainId = Some(inChain1.getId))
                .setTpDst(
                    Int32Range.newBuilder().setStart(43).setEnd(43).build())
                .build()
            val inChain2R2 = createLiteralRuleBuilder(
                action = Some(Action.DROP),
                chainId = Some(inChain1.getId))
                .setTpDst(
                    Int32Range.newBuilder().setStart(44).setEnd(44).build())
                .build()
            inChain2 = inChain2.toBuilder
                .addRuleIds(inChain2R1.getId)
                .addRuleIds(inChain2R2.getId).build()

            List(host, vmBridge,
                 inFilter, outFilter,
                 inChain1, outChain1,
                 inChain2, outChain2,
                 inFilterR1, inFilterR2,
                 inChain1R1, inChain1R2, inChain1R3,
                 inChain2R1, inChain2R2,
                 vm1Port, vm2Port
            ).foreach {
                store.create(_)
            }

            // Load the cache to avoid NotYetException at simulation time
            for (port <- List(vm1Port, vm2Port))
                tryGet(() => VirtualTopology.tryGet[Port](port.getId))
            tryGet(() => VirtualTopology.tryGet[Bridge](vmBridge.getId))

            // Set all the ports to "Active"
            for (port <- List(vm1Port, vm2Port)) {
                VirtualTopology.tryGet[Port](port.getId)._active = true
            }

            // Incoming/Outgoing are from vm1Port's perspective
            When("A udp packet to port 40 ingresses vm1Port")
            var packetContext = packetContextFor(makePacket(40), vm1Port.getId)
            var result = simulate(packetContext)
            Then("It's dropped")
            result should be(dropped(FlowTagger.tagForDevice(vm1Port.getId)))

            When("A udp packet to port 41 ingresses vm1Port")
            packetContext = packetContextFor(makePacket(41), vm1Port.getId)
            result = simulate(packetContext)
            Then("It's redirected out vm2Port")
            result should be(toPort(vm2Port.getId)
                                 (FlowTagger.tagForDevice(vm1Port.getId),
                                  FlowTagger.tagForDevice(vm2Port.getId)))

            When("A udp packet to port 42 ingresses vm1Port")
            packetContext = packetContextFor(makePacket(42), vm1Port.getId)
            result = simulate(packetContext)
            Then("It's dropped")
            result should be(dropped(FlowTagger.tagForDevice(vm1Port.getId)))

            When("A udp packet to port 41 ingresses vm1Port")
            packetContext = packetContextFor(makePacket(43), vm1Port.getId)
            result = simulate(packetContext)
            Then("It's redirected out vm2Port")
            result should be(toPort(vm2Port.getId)
                                 (FlowTagger.tagForDevice(vm1Port.getId),
                                  FlowTagger.tagForDevice(vm2Port.getId)))

            When("A udp packet to port 44 ingresses vm1Port")
            packetContext = packetContextFor(makePacket(44), vm1Port.getId)
            result = simulate(packetContext)
            Then("It's dropped")
            result should be(dropped(FlowTagger.tagForDevice(vm1Port.getId)))

            When("A udp packet to port 45 ingresses vm1Port")
            packetContext = packetContextFor(makePacket(45), vm1Port.getId)
            result = simulate(packetContext)
            Then("It's accepted")
            result should be(toBridge(vmBridge.getId, List(vm2Port.getId),
                                      FlowTagger.tagForDevice(vm1Port.getId),
                                      FlowTagger.tagForDevice(vmBridge.getId),
                                      FlowTagger.tagForDevice(vm2Port.getId)))
        }
    }
}
