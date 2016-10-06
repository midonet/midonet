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

package org.midonet.midolman

import java.util.UUID

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.storage.Storage
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.cluster.models.Topology.{Network => TopologyBridge,
                                            Router => TopologyRouter,
                                            Port => TopologyPort}
import org.midonet.midolman.rules.{Condition, RuleResult}
import org.midonet.midolman.simulation.Simulator.ToPortAction
import org.midonet.midolman.PacketWorkflow._
import org.midonet.midolman.layer3.Route._
import org.midonet.midolman.simulation.{Router => SimRouter, Bridge => SimBridge, BridgePort}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.odp.flows._
import org.midonet.packets._
import org.midonet.sdn.flows.FlowTagger
import org.midonet.util.Range
import org.midonet.util.concurrent.toFutureOps

@RunWith(classOf[JUnitRunner])
class MirroringTest extends MidolmanSpec with TopologyBuilder {

    var mirrorA: UUID = _
    var mirrorB: UUID = _

    var router: UUID = _
    var bridge: UUID = _

    var dstBridge: UUID = _
    var dstPortA: UUID = _
    var dstPortB: UUID = _

    var leftBridgePort: UUID = _
    var rightBridgePort: UUID = _

    var leftRouterPort: UUID = _
    var rightRouterPort: UUID = _

    val leftAddr = IPv4Addr.fromString("192.168.0.1")
    val rightAddr = IPv4Addr.fromString("192.168.0.5")
    val leftRouterAddr = IPv4Subnet.fromCidr("192.168.0.2/30")
    val rightRouterAddr = IPv4Subnet.fromCidr("192.168.0.6/30")

    val leftMac = MAC.random()
    val rightMac = MAC.random()
    val leftRouterMac = MAC.random()
    val rightRouterMac = MAC.random()

    val DIRECT_ROUTE = new IPv4Addr(NO_GATEWAY).toString

    var store: Storage = _

    val ToPort = classOf[ToPortAction]
    val SetKey = classOf[FlowActionSetKey]

    override def beforeTest(): Unit = {
        store = injector.getInstance(classOf[MidonetBackend]).store
        router = newRouter("router")
        bridge = newBridge("bridge")
        dstBridge = newBridge("mirrorBridge")

        leftRouterPort = newRouterPort(router, leftRouterMac,
                                       leftRouterAddr.toUnicastString,
                                       leftRouterAddr.toNetworkAddress.toString,
                                       leftRouterAddr.getPrefixLen)
        rightRouterPort = newRouterPort(router, rightRouterMac,
                                        rightRouterAddr.toUnicastString,
                                        rightRouterAddr.toNetworkAddress.toString,
                                        rightRouterAddr.getPrefixLen)
        materializePort(leftRouterPort, hostId, "leftRouterPort")
        materializePort(rightRouterPort, hostId, "rightRouterPort")

        newRoute(router, "0.0.0.0", 0, "192.168.0.0", 30, NextHop.PORT, leftRouterPort, DIRECT_ROUTE, 1)
        newRoute(router, "0.0.0.0", 0, "192.168.0.4", 30, NextHop.PORT, rightRouterPort, DIRECT_ROUTE, 1)

        leftBridgePort = newBridgePort(bridge, Some(hostId), Some("leftBridgePort"))
        rightBridgePort = newBridgePort(bridge, Some(hostId), Some("rightBridgePort"))
        materializePort(leftBridgePort, hostId, "leftBridgePort")
        materializePort(rightBridgePort, hostId, "rightBridgePort")

        dstPortA = newBridgePort(dstBridge, Some(hostId), Some("dstPortA"))
        store.get(classOf[TopologyPort], dstPortA).await()
        dstPortB = newBridgePort(dstBridge, Some(hostId), Some("dstPortB"))


        /* Mirror A: mirror traffic to TCP ports 22 and 23 */
        var mirrorBuilder = createMirrorBuilder(toPort = dstPortA)
        mirrorBuilder = addMirrorCondition(mirrorBuilder,
                                           tpDst = Option(new Range(22)))
        mirrorBuilder = addMirrorCondition(mirrorBuilder,
                                           tpDst = Option(new Range(23)))
        var mirror = mirrorBuilder.build
        store.create(mirror)
        mirrorA = mirror.getId


        /* Mirror B: mirror traffic to TCP ports 23 and 24 */
        mirrorBuilder = createMirrorBuilder(toPort = dstPortB)
        mirrorBuilder = addMirrorCondition(mirrorBuilder,
                                           tpDst = Option(new Range(23)))
        mirrorBuilder = addMirrorCondition(mirrorBuilder,
                                           tpDst = Option(new Range(24)))
        mirror = mirrorBuilder.build
        store.create(mirror)
        mirrorB = mirror.getId

        fetchPorts(leftBridgePort, rightBridgePort,
                   leftRouterPort, rightRouterPort,
                   dstPortA, dstPortB)

        fetchRouters(router)
        fetchDevice[SimBridge](bridge)

        feedArpTable(simRouter, rightAddr, rightMac)
        feedArpTable(simRouter, leftAddr, leftMac)
    }

    def simRouter: SimRouter = fetchDevice[SimRouter](router)
    def simBridge: SimBridge = fetchDevice[SimBridge](bridge)

    def frameLeftToRightL2(dstPort: Short): Ethernet = {
        import org.midonet.packets.util.PacketBuilder._

        { eth ether_type IPv4.ETHERTYPE mac leftMac -> rightMac }  <<
            { ip4 addr leftAddr --> rightAddr } <<
                { tcp ports 12345 ---> dstPort }
    }

    def frameLeftToRightL2Vlan(dstPort: Short): Ethernet = {
        import org.midonet.packets.util.PacketBuilder._

        { eth ether_type IPv4.ETHERTYPE mac leftMac -> rightMac vlan 1 }  <<
            { ip4 addr leftAddr --> rightAddr } <<
                { tcp ports 12345 ---> dstPort }
    }


    def frameLeftToRightL3(dstPort: Short): Ethernet = {
        import org.midonet.packets.util.PacketBuilder._

        { eth ether_type IPv4.ETHERTYPE mac leftMac -> leftRouterMac }  <<
            { ip4 addr leftAddr --> rightAddr } <<
            { tcp ports 12345 ---> dstPort }
    }

    private def addMirrorToBridge(br: UUID, mirror: UUID, inbound: Boolean = true): Unit = {
        var dev = store.get(classOf[TopologyBridge], br).await()
        if (inbound)
            dev = dev.toBuilder.addInboundMirrorIds(mirror).build()
        else
            dev = dev.toBuilder.addOutboundMirrorIds(mirror).build()
        store.update(dev)
        fetchDevice[SimBridge](br)
    }

    private def addMirrorToRouter(rtr: UUID, mirror: UUID, inbound: Boolean = true): Unit = {
        var dev = store.get(classOf[TopologyRouter], rtr).await()
        if (inbound)
            dev = dev.toBuilder.addInboundMirrorIds(mirror).build()
        else
            dev = dev.toBuilder.addOutboundMirrorIds(mirror).build()
        store.update(dev)
        fetchDevice[SimRouter](rtr)
    }

    private def addMirrorToPort(port: UUID, mirror: UUID,
                                inbound: Boolean = true,
                                alt: Boolean = false): Unit = {
        var dev = store.get(classOf[TopologyPort], port).await()
        if (inbound)
            if (alt)
                dev = dev.toBuilder.addPostInFilterMirrorIds(mirror).build()
            else
                dev = dev.toBuilder.addInboundMirrorIds(mirror).build()
        else
            if (alt)
                dev = dev.toBuilder.addPreOutFilterMirrorIds(mirror).build()
            else
                dev = dev.toBuilder.addOutboundMirrorIds(mirror).build()
        store.update(dev)
        fetchDevice[BridgePort](port)
    }

    private def addDropAll(port: UUID, inbound: Boolean) {
        val chain = if (inbound)
            newInboundChainOnPort("test", port, UUID.randomUUID)
        else
            newOutboundChainOnPort("test", port, UUID.randomUUID)
        newLiteralRuleOnChain(chain, 1, new Condition(),
                              RuleResult.Action.DROP)
    }

    feature("Basic mirror on bridge") {
        scenario("Ingress mirror") {
            addMirrorToBridge(bridge, mirrorA, inbound = true)

            val (result, context) = simulate(packetContextFor(frameLeftToRightL2(22), leftBridgePort))
            result should be (AddVirtualWildcardFlow)
            context should be (taggedWith (FlowTagger.tagForMirror(mirrorA)))
            context should be (toPorts (rightBridgePort, dstPortA))
        }

        scenario("Egress mirror") {
            addMirrorToBridge(bridge, mirrorA, inbound = false)

            val (result, context) = simulate(packetContextFor(frameLeftToRightL2(22), leftBridgePort))
            result should be (AddVirtualWildcardFlow)
            context should be (taggedWith (FlowTagger.tagForMirror(mirrorA)))
            context should be (toPorts (rightBridgePort, dstPortA))
        }
    }

    feature("Basic mirroring on bridge for VLAN-tagged frames") {
        scenario("Ingress mirror") {
            addMirrorToBridge(bridge, mirrorA, inbound = true)

            val (result, context) = simulate(packetContextFor(frameLeftToRightL2Vlan(22), leftBridgePort))
            result should be (AddVirtualWildcardFlow)
            context should be (taggedWith (FlowTagger.tagForMirror(mirrorA)))
            context should be (toPorts (rightBridgePort, dstPortA))
        }

        scenario("Egress mirror") {
            addMirrorToBridge(bridge, mirrorA, inbound = false)

            val (result, context) = simulate(packetContextFor(frameLeftToRightL2Vlan(22), leftBridgePort))
            result should be (AddVirtualWildcardFlow)
            context should be (taggedWith (FlowTagger.tagForMirror(mirrorA)))
            context should be (toPorts (rightBridgePort, dstPortA))
        }
    }

    feature("Basic mirror on router") {
        scenario("Ingress mirror") {
            addMirrorToRouter(router, mirrorA, inbound = true)

            val (result, context) = simulate(packetContextFor(frameLeftToRightL3(22), leftRouterPort))
            result should be (AddVirtualWildcardFlow)
            context should be (taggedWith (FlowTagger.tagForMirror(mirrorA)))
            context should be (toPorts (rightRouterPort, dstPortA))
            context should be (processedWith (ToPort, SetKey, SetKey, ToPort))
        }

        scenario("Egress mirror") {
            addMirrorToRouter(router, mirrorA, inbound = false)

            val (result, context) = simulate(packetContextFor(frameLeftToRightL3(22), leftRouterPort))
            result should be (AddVirtualWildcardFlow)
            context should be (taggedWith (FlowTagger.tagForMirror(mirrorA)))
            context should be (toPorts (rightRouterPort, dstPortA))
            context should be (processedWith (SetKey, SetKey, ToPort, ToPort))
        }
    }

    feature("Basic mirror on port") {
        scenario("Ingress mirror") {
            addMirrorToPort(leftBridgePort, mirrorA, inbound = true)

            val (result, context) = simulate(packetContextFor(frameLeftToRightL2(22), leftBridgePort))
            result should be (AddVirtualWildcardFlow)
            context should be (taggedWith (FlowTagger.tagForMirror(mirrorA)))
            context should be (toPorts (rightBridgePort, dstPortA))
        }

        scenario("Egress mirror") {
            addMirrorToPort(rightBridgePort, mirrorA, inbound = false)

            val (result, context) = simulate(packetContextFor(frameLeftToRightL2(22), leftBridgePort))
            result should be (AddVirtualWildcardFlow)
            context should be (taggedWith (FlowTagger.tagForMirror(mirrorA)))
            context should be (toPorts (rightBridgePort, dstPortA))
        }
    }

    feature("Basic alt mirror on port") {
        scenario("Ingress mirror") {
            addMirrorToPort(leftBridgePort, mirrorA, inbound = true, alt = true)

            val (result, context) = simulate(packetContextFor(frameLeftToRightL2(22), leftBridgePort))
            result should be (AddVirtualWildcardFlow)
            context should be (taggedWith (FlowTagger.tagForMirror(mirrorA)))
            context should be (toPorts (rightBridgePort, dstPortA))
        }

        scenario("Egress mirror") {
            addMirrorToPort(rightBridgePort, mirrorA, inbound = false,
                            alt = true)

            val (result, context) = simulate(packetContextFor(frameLeftToRightL2(22), leftBridgePort))
            result should be (AddVirtualWildcardFlow)
            context should be (taggedWith (FlowTagger.tagForMirror(mirrorA)))
            context should be (toPorts (rightBridgePort, dstPortA))
        }
    }

    feature("Filtering vs mirror on port") {
        scenario("Ingress mirror") {
            addDropAll(leftBridgePort, inbound = true)
            addMirrorToPort(leftBridgePort, mirrorA, inbound = true)

            val (result, context) = simulate(packetContextFor(frameLeftToRightL2(22), leftBridgePort))
            result should be (AddVirtualWildcardFlow)
            context should be (taggedWith (FlowTagger.tagForMirror(mirrorA)))
            context should be (toPorts (dstPortA))
        }

        scenario("Egress mirror") {
            addDropAll(rightBridgePort, inbound = false)
            addMirrorToPort(rightBridgePort, mirrorA, inbound = false)

            val (result, context) = simulate(packetContextFor(frameLeftToRightL2(22), leftBridgePort))
            result should be (Drop)
            context should not be (taggedWith (FlowTagger.tagForMirror(mirrorA)))
            context should be (toPorts ())
        }
    }

    feature("Filtering vs alt mirror on port") {
        scenario("Ingress mirror") {
            addDropAll(leftBridgePort, inbound = true)
            addMirrorToPort(leftBridgePort, mirrorA, inbound = true, alt = true)

            val (result, context) = simulate(packetContextFor(frameLeftToRightL2(22), leftBridgePort))
            result should be (Drop)
            context should not be (taggedWith (FlowTagger.tagForMirror(mirrorA)))
            context should be (toPorts ())
        }

        scenario("Egress mirror") {
            addDropAll(rightBridgePort, inbound = false)
            addMirrorToPort(rightBridgePort, mirrorA, inbound = false,
                            alt = true)

            val (result, context) = simulate(packetContextFor(frameLeftToRightL2(22), leftBridgePort))
            result should be (AddVirtualWildcardFlow)
            context should be (taggedWith (FlowTagger.tagForMirror(mirrorA)))
            context should be (toPorts (dstPortA))
        }
    }

    feature("Double mirroring") {
        scenario("On a bridge inbound") {
            addMirrorToBridge(bridge, mirrorA, inbound = true)
            addMirrorToBridge(bridge, mirrorB, inbound = true)

            val (result, context) = simulate(packetContextFor(frameLeftToRightL2(23), leftBridgePort))
            result should be (AddVirtualWildcardFlow)
            context should be (taggedWith (FlowTagger.tagForMirror(mirrorA),
                                           FlowTagger.tagForMirror(mirrorB)))
            context should be (toPorts (rightBridgePort, dstPortA, dstPortB))
        }

        scenario("On a bridge inbound and outbound") {
            addMirrorToBridge(bridge, mirrorA, inbound = true)
            addMirrorToBridge(bridge, mirrorB, inbound = false)

            val (result, context) = simulate(packetContextFor(frameLeftToRightL2(23), leftBridgePort))
            result should be (AddVirtualWildcardFlow)
            context should be (taggedWith (FlowTagger.tagForMirror(mirrorA),
                                           FlowTagger.tagForMirror(mirrorB)))
            context should be (toPorts (rightBridgePort, dstPortA, dstPortB))
        }

        scenario("On a router inbound and outbound") {
            addMirrorToRouter(router, mirrorA, inbound = true)
            addMirrorToRouter(router, mirrorB, inbound = false)

            val (result, context) = simulate(packetContextFor(frameLeftToRightL3(23), leftRouterPort))
            result should be (AddVirtualWildcardFlow)
            context should be (taggedWith (FlowTagger.tagForMirror(mirrorA),
                                           FlowTagger.tagForMirror(mirrorB)))
            context should be (toPorts (rightRouterPort, dstPortA, dstPortB))
            context should be (processedWith (ToPort, SetKey, SetKey, ToPort, ToPort))
        }

        scenario("On a router outbound its inbound port") {
            addMirrorToRouter(router, mirrorA, inbound = false)
            addMirrorToPort(leftRouterPort, mirrorB, inbound = true)

            val (result, context) = simulate(packetContextFor(frameLeftToRightL3(23), leftRouterPort))
            result should be (AddVirtualWildcardFlow)
            context should be (taggedWith (FlowTagger.tagForMirror(mirrorA),
                FlowTagger.tagForMirror(mirrorB)))
            context should be (toPorts (rightRouterPort, dstPortA, dstPortB))
            context should be (processedWith (ToPort, SetKey, SetKey, ToPort, ToPort))
        }
    }

    feature("Loop detection") {
        scenario("to-port is mirrored back") {
            addMirrorToPort(dstPortA, mirrorA, inbound = false)

            val (result, context) = simulate(packetContextFor(frameLeftToRightL2(23), dstPortB))
            result should be (ErrorDrop)
        }
    }
}
