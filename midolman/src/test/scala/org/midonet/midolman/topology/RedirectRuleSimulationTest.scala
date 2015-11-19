/*
 * Copyright 2015 Midokura SARL
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

import scala.collection.JavaConverters._
import scala.concurrent.Await
import scala.concurrent.duration._

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.storage.Storage
import org.midonet.cluster.models.Commons.{Condition, UUID => PUUID}
import org.midonet.cluster.models.Topology.{Network => TopoBridge}
import org.midonet.cluster.models.Topology.{Port => TopoPort, L2Insertion, Rule}
import org.midonet.cluster.models.Topology.Rule.Action
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.rules.RuleResult
import org.midonet.midolman.simulation.{Chain, Bridge}
import org.midonet.midolman.simulation.{Port,ServicePort}
import org.midonet.midolman.simulation.Simulator.ToPortAction
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.packets.Ethernet
import org.midonet.sdn.flows.FlowTagger
import org.midonet.sdn.flows.FlowTagger.FlowTag

@RunWith(classOf[JUnitRunner])
class RedirectRuleSimulationTest extends MidolmanSpec with TopologyBuilder {

    private var vt: VirtualTopology = _
    private var store: Storage = _
    val mac1 = "02:00:00:00:ee:00"
    val mac2 = "02:00:00:00:ee:11"

    protected override def beforeTest(): Unit = {
        vt = injector.getInstance(classOf[VirtualTopology])
        store = injector.getInstance(classOf[MidonetBackend]).store
    }

    def makePort(id: UUID, ifName: String, bridge: TopoBridge,
                 infilter: Option[UUID] = None,
                 outfilter: Option[UUID] = None) = {
        createBridgePort(
            id = id,
            hostId = Some(hostId),
            interfaceName = Some(ifName),
            adminStateUp = true,
            inboundFilterId = infilter,
            outboundFilterId = outfilter,
            bridgeId = Some(bridge.getId.asJava))
    }

    def addServiceToPort(portId: PUUID, srvPortId: PUUID = null): L2Insertion = {
        val srvPortId2 = if (srvPortId == null) { portId } else { srvPortId }
        // isn't actually translated to anything, just here
        // to convert port to service port
        L2Insertion.newBuilder
            .setId(UUID.randomUUID().asProto)
            .setMac(mac1)
            .setPortId(portId)
            .setSrvPortId(srvPortId2)
            .setPosition(1)
            .setVlan(10)
            .build
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
                    eth addr srcMac -> dstMac vlan v
                } << {
                    ip4 addr "10.0.0.10" --> "10.0.0.11"
                } << {
                    udp ports 53 ---> dstUdpPort
                } <<
                payload(UUID.randomUUID().toString)
        }
    }

    def checkPacket(when: String, `then`: String, frame: Ethernet,
                    srcPortId: UUID, dstPortId: UUID,
                    expectedVlan: Option[Short] = None,
                    expectedTags: List[FlowTag] = List()) = {
        When(when)
        val packetContext = packetContextFor(frame, srcPortId)
        val result = simulate(packetContext)

        Then(`then`)
        result should be(toPort(dstPortId)(expectedTags : _*))
        expectedVlan match {
            case None =>
                result._2.wcmatch.getVlanIds.size() should be(0)
            case Some(vlan) =>
                //result should be(hasPushVlan())
                result._2.wcmatch.getVlanIds.get(0) should be(vlan)
        }
    }

    def l2transformRuleBuilder(chainId: Option[UUID],
                               action: Option[Action],
                               pushVLan: Option[Short] = None,
                               popVLan: Option[Boolean] = None,
                               cond: Option[(Condition.Builder) => Unit] = None)
            : Rule.Builder = {
        val ruleBuilder = createL2TransformRuleBuilder(
            chainId = chainId,
            action = action,
            pushVLan = pushVLan,
            popVLan = popVLan)
        val condBuilder = ruleBuilder.getConditionBuilder
        cond foreach { _(condBuilder) }
        ruleBuilder
    }

    def redirectRuleBuilder(chainId: Option[UUID],
                            targetPortId: Option[UUID],
                            ingress: Option[Boolean] = None,
                            failOpen: Option[Boolean] = None,
                            pushVLan: Option[Short] = None,
                            popVLan: Option[Boolean] = None,
                            cond: Option[(Condition.Builder) => Unit] = None)
            : Rule.Builder = {
        val ruleBuilder = createL2TransformRuleBuilder(
            chainId = chainId,
            targetPortId = targetPortId,
            ingress = ingress,
            pushVLan = pushVLan,
            popVLan = popVLan,
            failOpen = failOpen)
        val condBuilder = ruleBuilder.getConditionBuilder
        cond foreach { _(condBuilder) }
        ruleBuilder
    }

    def literalRuleBuilder(chainId: Option[UUID],
                           action: Option[Action],
                           cond: Option[(Condition.Builder) => Unit] = None)
            : Rule.Builder = {
        val ruleBuilder = createLiteralRuleBuilder(UUID.randomUUID(),
                                                   chainId = chainId,
                                                   action = action)
        val condBuilder = ruleBuilder.getConditionBuilder
        cond foreach { _(condBuilder) }
        ruleBuilder
    }

    feature("Test redirect rules") {
        scenario("Test redirect without vlans") {
            val leftBridge = createBridge(name = Some("LeftBridge"))
            val rightBridge = createBridge(name = Some("RightBridge"))

            var leftPortInFilter = createChain(name = Some("LeftInFilter"))

            val leftPort = makePort(new UUID(0,1), "left_if",
                                    leftBridge, Some(leftPortInFilter.getId))
            val rightPort = makePort(new UUID(0,2), "right_if", rightBridge)

            store.create(createHost(id = hostId))
            store.create(leftBridge)
            store.create(rightBridge)
            store.create(leftPortInFilter)
            store.create(leftPort)
            store.create(rightPort)

            val leftRule = redirectRuleBuilder(
                chainId = Some(leftPortInFilter.getId),
                targetPortId = Some(rightPort.getId)).build()
            store.create(leftRule)

            fetchPorts(leftPort.getId, rightPort.getId)
            fetchDevice[Bridge](leftBridge.getId)
            fetchDevice[Bridge](rightBridge.getId)

            checkPacket("A packet ingresses the left port",
                        "It's redirected out the right port",
                        packet(mac1, mac2, None),
                        leftPort.getId,
                        rightPort.getId,
                        expectedTags=List(FlowTagger.tagForPort(leftPort.getId),
                                          FlowTagger.tagForPort(rightPort.getId)))
        }

        scenario("Test redirect with vlans") {
            val host = createHost(id = hostId)

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

            val vm1Port = makePort(new UUID(0,1), "if_vm1", vmBridge,
                                   Some(vm1In.getId), Some(vm1Out.getId))
            val vm2Port = makePort(new UUID(0,2), "if_vm2", vmBridge,
                                   Some(vm2In.getId), Some(vm2Out.getId))
            var svc1Port = makePort(new UUID(1,1),
                                    "if_svc1", svcBridge,
                                    Some(svc1In.getId),
                                    Some(svc1Out.getId))
            val ins1 = addServiceToPort(svc1Port.getId)
            val svc2Port = makePort(new UUID(1,2),
                                    "if_svc2", svcBridge,
                                    Some(svc2In.getId),
                                    Some(svc2Out.getId))
            val ins2 = addServiceToPort(svc2Port.getId)

            List(host, vmBridge, svcBridge,
                 vm1In, vm1Out, vm2In, vm2Out,
                 svc1In, svc1Out, svc2In, svc2Out,
                 vm1Port, vm2Port, svc1Port, svc2Port,
                 ins1, ins2
            ).foreach {
                store.create(_)
            }
            materializePort(vm1Port.getId, hostId, "if_vm1")
            materializePort(vm2Port.getId, hostId, "if_vm2")
            materializePort(svc1Port.getId, hostId, "if_svc1")
            materializePort(svc2Port.getId, hostId, "if_svc2")

            // vm1Port's ingress rules
            // Packets without vlan should go to service 1 with vlan 10
            val vm1InR1 = redirectRuleBuilder(
                chainId = Some(vm1In.getId),
                targetPortId = Some(svc1Port.getId),
                pushVLan = Some(10),
                cond = Some({c => c.setNoVlan(true)})).build()
            // Packets with vlan 20 should have their vlan popped and accepted
            val vm1InR2 = l2transformRuleBuilder(
                chainId = Some(vm1In.getId),
                action = Some(Action.ACCEPT),
                popVLan = Some(true),
                cond = Some({c => c.setVlan(20)})).build()

            // vm1Port's egress rules
            // Packets without vlan should go to service 1 with vlan 11
            val vm1OutR1 = redirectRuleBuilder(
                chainId = Some(vm1Out.getId),
                targetPortId = Some(svc1Port.getId),
                pushVLan = Some(11),
                cond = Some({c => c.setNoVlan(true)})).build()
            // No rule matching packets with vlan 21: outgoing don't re-traverse filters

            // svc1Port's ingress rules
            // Pop vlan 10, redirect to service 2 with vlan 20
            val svc1InR1 = redirectRuleBuilder(
                chainId = Some(svc1In.getId),
                targetPortId = Some(svc2Port.getId),
                popVLan = Some(true),
                pushVLan = Some(20),
                cond = Some({c => c.setVlan(10)})).build()

            // Pop vlan 11, redirect to service 2 with vlan 21
            val svc1InR2 = redirectRuleBuilder(
                chainId = Some(svc1In.getId),
                targetPortId = Some(svc2Port.getId),
                popVLan = Some(true),
                pushVLan = Some(21),
                cond = Some({c => c.setVlan(11)})).build()

            // svc1Port's egress rule: drop all
            val svc1OutR1 = literalRuleBuilder(
                chainId = Some(svc1Out.getId),
                action = Some(Action.DROP)).build()

            // svc2Port's ingress rules
            // Match vlan 20, redirect to vm1Port - ingress
            val svc2InR1 = redirectRuleBuilder(
                chainId = Some(svc2In.getId),
                targetPortId = Some(vm1Port.getId),
                ingress = Some(true),
                cond = Some({c => c.setVlan(20)})).build()

            // Match vlan 21, redirect to vm1Port - egress
            val svc2InR2 = redirectRuleBuilder(
                chainId = Some(svc2In.getId),
                targetPortId = Some(vm1Port.getId),
                ingress = Some(false),
                popVLan = Some(true),
                cond = Some({c => c.setVlan(21)})).build()

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
            fetchPorts(vm1Port.getId, vm2Port.getId,
                       svc1Port.getId, svc2Port.getId)
            fetchDevice[Bridge](vmBridge.getId)
            fetchDevice[Bridge](svcBridge.getId)

            // Before adding insertions, send forward and return packets to
            // seed the MAC tables. This avoids bridge flood actions and
            // allows 'checkPacket' to always check for a single output port.
            simulate(packetContextFor(packet(mac1, mac2), vm1Port.getId))
            simulate(packetContextFor(packet(mac2, mac1), vm2Port.getId))

            checkPacket("A packet ingresses vm1Port",
                        "It's redirected out svc1Port with vlan 10",
                        packet(mac1, mac2, None),
                        vm1Port.getId, svc1Port.getId,
                        expectedVlan = Some(10),
                        expectedTags = List(
                            FlowTagger.tagForPort(vm1Port.getId),
                            FlowTagger.tagForPort(svc1Port.getId)))

            checkPacket("The incoming packet ingresses svc1Port",
                        "It's redirected out svc2Port with vlan 20",
                        packet(mac1, mac2, Some(10)),
                        svc1Port.getId, svc2Port.getId,
                        expectedVlan = Some(20),
                        expectedTags = List(
                            FlowTagger.tagForPort(svc1Port.getId),
                            FlowTagger.tagForPort(svc2Port.getId)))

            checkPacket("The incoming packet ingresses svc2Port",
                        "It traverses the bridge and egresses vm2Port without vlan",
                        packet(mac1, mac2, Some(20)),
                        svc2Port.getId, vm2Port.getId,
                        expectedTags = List(
                            FlowTagger.tagForPort(svc2Port.getId),
                            FlowTagger.tagForPort(vm1Port.getId),
                            FlowTagger.tagForBridge(vmBridge.getId),
                            FlowTagger.tagForPort(vm2Port.getId)))

            checkPacket("A return packet from vm2Port arrives at vm1Port",
                        "It's redirected out svc1Port with vlan 11",
                        packet(mac2, mac1),
                        vm2Port.getId, svc1Port.getId,
                        expectedVlan = Some(11),
                        expectedTags = List(
                            FlowTagger.tagForPort(vm2Port.getId),
                            FlowTagger.tagForBridge(vmBridge.getId),
                            FlowTagger.tagForPort(vm1Port.getId),
                            FlowTagger.tagForPort(svc1Port.getId)))

            checkPacket("The return packet ingresses svc1Port",
                        "It's redirected out svc2Port with vlan 21",
                        packet(mac2, mac1, Some(11)),
                        svc1Port.getId, svc2Port.getId,
                        expectedVlan = Some(21),
                        expectedTags = List(
                            FlowTagger.tagForPort(svc1Port.getId),
                            FlowTagger.tagForPort(svc2Port.getId)))

            checkPacket("The return packet ingresses svc2Port",
                        "It's redirected out vm1Port without vlan",
                        packet(mac2, mac1, Some(21)),
                        svc2Port.getId, vm1Port.getId,
                        expectedTags = List(
                            FlowTagger.tagForPort(svc2Port.getId),
                            FlowTagger.tagForPort(vm1Port.getId)))

            var p = fetchDevice[Port](svc1Port.getId)
            p.adminStateUp shouldBe true

            // Now set svc1Port down. Traffic from VM1 should be dropped because FAIL_OPEN is false.
            svc1Port = Await.result(store.get(classOf[TopoPort], svc1Port.getId), 5 seconds)
            svc1Port = svc1Port.toBuilder.setAdminStateUp(false).build()
            store.update(svc1Port)
            fetchDevice[Port](svc1Port.getId).
                asInstanceOf[ServicePort].realAdminStateUp shouldBe false

            When("An incoming packet ingresses vm1Port")
            var packetContext = packetContextFor(packet(mac1, mac2),
                                                 vm1Port.getId)
            var result = simulate(packetContext)

            Then("It's dropped because svc1 is down")
            result should be(dropped(FlowTagger.tagForPort(vm1Port.getId)))

            // Now set FAIL_OPEN to true on the Redirect to svc1. Traffic from
            // VM1 should skip svc1 and go to svc2.
            val vm1InR3 = redirectRuleBuilder(
                chainId = Some(vm1In.getId),
                targetPortId = Some(svc1Port.getId),
                failOpen = Some(true),
                pushVLan = Some(10),
                cond = Some({c => c.setNoVlan(true)})).build()
            store.delete(classOf[Rule], vm1InR1.getId)
            store.create(vm1InR3)

            val c = fetchDevice[Chain](vm1In.getId)
            c.rules.get(0).action shouldBe RuleResult.Action.ACCEPT

            checkPacket("A forward packet ingresses vm1Port",
                        "It skips svc1Port and egresses svc2Port with vlan 20",
                        packet(mac1, mac2),
                        vm1Port.getId, svc2Port.getId,
                        expectedVlan = Some(20),
                        expectedTags = List(
                            FlowTagger.tagForPort(vm1Port.getId),
                            FlowTagger.tagForPort(svc1Port.getId), // still traversed
                            FlowTagger.tagForPort(svc2Port.getId)))
        }

        scenario("Test redirect interaction with flooding") {
            Given("A bridge with a mix of normal and service ports")
            val host = createHost(id = hostId)
            store.create(host)
            val bridge0: TopoBridge = createBridge(name = Some("bridge"),
                                                   adminStateUp = true)
            store.create(bridge0)

            val id = new UUID(2,0xdeadbeef)
            val srcPort = makePort(id,
                                   "src_port", bridge0)
            store.create(srcPort)
            materializePort(srcPort.getId, hostId, "src_port_$i")

            for (i <- 1.until(100)) {
                var filter = createChain(name = Some(s"filter_$i"))
                val port = makePort(new UUID(0,i), s"prot_if_$i",
                                    bridge0, outfilter=Some(filter.getId))
                val srvPort = makePort(new UUID(1,i), s"srv_if_$i", bridge0)
                val rule = redirectRuleBuilder(
                    chainId = Some(filter.getId),
                    targetPortId = Some(srvPort.getId)).build()
                val ins = addServiceToPort(srvPort.getId)
                store.create(filter)
                store.create(port)
                store.create(srvPort)
                store.create(ins)
                store.create(rule)

                materializePort(port.getId, hostId, "vm_if_$i")
                materializePort(srvPort.getId, hostId, "vm_if_srv_$i")

                fetchPorts(port.getId, srvPort.getId)
            }

            fetchPorts(srcPort.getId)
            fetchDevice[Bridge](bridge0.getId)

            When("A packet floods the bridge")
            val frame = packet(mac1, mac2, None)
            val packetContext = packetContextFor(frame, srcPort.getId)
            val result = simulate(packetContext)

            val portIds = packetContext.virtualFlowActions.asScala
                .filter({ _ match {
                             case ToPortAction(_) => true
                             case _ => false
                         }
                        })
                .map({ case ToPortAction(id) => id })

            Then("No port should receive the packet more than once")
            val portCounts = portIds.groupBy(identity).mapValues(_.size)
            portCounts.count({ case (key,count) => count > 1 }) shouldBe 0

            Then("Should only have hit service ports (and the srcPort) ")
            // the protected ports all have a uuid with MSB as 0
            portIds.count({ _.getMostSignificantBits == 0 }) shouldBe 0
        }

        scenario("Redirecting sends flow state to all ports") {
            Given("A port with 4 services")
            val host = createHost(id = hostId)
            store.create(host)

            val bridge0: TopoBridge = createBridge(name = Some("bridge"),
                                                   adminStateUp = true)
            store.create(bridge0)

            val srcPort = makePort(new UUID(0, 1), "src_port", bridge0)
            store.create(srcPort)
            materializePort(srcPort.getId, hostId, "src_port_$i")

            val filter = createChain(name = Some(s"outfilter"))
            store.create(filter)

            val id = new UUID(0, 2)
            val dstPort = makePort(id, "dst_port", bridge0,
                                   outfilter=Some(filter.getId))
            store.create(dstPort)
            materializePort(dstPort.getId, hostId, "dst_port_$i")

            val srvPort0 = new UUID(1,0)
            val srvPort1 = new UUID(1,1)
            val srvPort2 = new UUID(1,2)
            val srvPort3 = new UUID(1,3)
            val srvPorts = Seq(srvPort0, srvPort1, srvPort2, srvPort3)
            for (i <- srvPorts) {
                val p = makePort(i, s"srv_port_$i", bridge0)
                store.create(p)
                materializePort(i, hostId, s"srv_port_$i")
                store.create(addServiceToPort(dstPort.getId, i))
            }

            val rule = redirectRuleBuilder(
                    chainId = Some(filter.getId),
                    targetPortId = Some(srvPort0)).build()
            store.create(rule)

            fetchPorts(srcPort.getId, dstPort.getId,
                       srvPort0, srvPort1,
                       srvPort2, srvPort3)
            fetchDevice[Chain](filter.getId)
            fetchDevice[Bridge](bridge0.getId)

            // seed the macs
            simulate(packetContextFor(packet(mac1, mac2), srcPort.getId))
            simulate(packetContextFor(packet(mac2, mac1), dstPort.getId))

            When("A packet goes through")
            val packetContext = packetContextFor(packet(mac1,mac2),
                                                 srcPort.getId)
            val result = simulate(packetContext)

            Then("The service ports should be set on the context")
            val expectedPorts = srvPorts :+ dstPort.getId.asJava
            packetContext.servicePorts should contain theSameElementsAs expectedPorts
        }
    }

}
