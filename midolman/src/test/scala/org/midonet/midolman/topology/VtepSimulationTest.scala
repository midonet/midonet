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

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Random

import com.typesafe.config.{Config, ConfigValueFactory}

import org.junit.runner.RunWith
import org.scalatest.Ignore
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.storage._
import org.midonet.cluster.models.Commons.{IPAddress => PIPAddr, IPVersion => PIPVersion}
import org.midonet.cluster.models.Topology.Route.NextHop
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.NotYetException
import org.midonet.midolman.simulation.{Port, Router}
import org.midonet.midolman.state.ArpCacheEntry
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.packets.util.PacketBuilder._
import org.midonet.packets._
import org.midonet.sdn.flows.FlowTagger
import org.midonet.util.concurrent.FutureOps

@RunWith(classOf[JUnitRunner])
class VtepSimulationTest extends MidolmanSpec with TopologyBuilder {

    private var vt: VirtualTopology = _
    private var store: Storage = _

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

    implicit def toFutureOps[U](future: Future[U]): FutureOps[U] = {
        new FutureOps(future)
    }

    def tryGet(thunk: () => Unit): Unit = {
        try {
            thunk()
        } catch {
            case e: NotYetException => e.waitFor.await(3 seconds)
        }
    }

    def packet(srcMac: String, dstMac: String): Ethernet = {
        {
            eth addr srcMac -> dstMac
        } << {
            ip4 addr "192.168.0.10" --> "192.168.0.20"
        } << {
            udp ports 9999 ---> 10999
        } <<
        payload(UUID.randomUUID().toString)
    }

    def vxlanPacket(srcMac: String, dstMac: String,
                    srcIp: String, dstIp: String,
                    vniValue: Int, innerPkt: Ethernet): Ethernet = {
        val udpSrc: Int = Random.nextInt() >>> 17
        return (eth addr srcMac -> dstMac) << (ip4 addr srcIp --> dstIp) <<
               (udp ports udpSrc ---> UDP.VXLAN.toShort) <<
               (vxlan vni vniValue) <<
               payload(innerPkt.serialize())
    }

    def checkPacket(when: String, then: String, frame: Ethernet,
                    srcPortId: UUID, dstPortId: UUID,
                    expectedVlan: Option[Short] = None) = {
        When(when)
        val packetContext = packetContextFor(frame, srcPortId)
        val result = simulate(packetContext)

        Then(then)
        result should be(toPort(dstPortId)())
        expectedVlan match {
            case None =>
                result._2.wcmatch.getVlanIds.size() should be(0)
            case Some(vlan) =>
                //result should be(hasPushVlan())
                result._2.wcmatch.getVlanIds.get(0) should be(vlan)
        }
    }

    /*
    def waitForPortChain(portId: UUID, size: Int, inbound: Boolean) = {
        eventually (timeout(Span(2, Seconds))) {
            val port = VirtualTopology.tryGet[SimPort](portId)
            val chain = inbound match {
                case true =>
                    VirtualTopology.tryGet[SimChain](port.inboundChains(0))
                case false =>
                    VirtualTopology.tryGet[SimChain](port.outboundChains(0))
            }
            chain.getRules.size should be(size)
        }
    }*/

    /**
     * Convert a java.util.UUID to a Protocol Buffers message.
     */
    implicit def toProto(ip: IPv4Addr): PIPAddr = {
        if (ip == null) {
            null
        }
        else {
            PIPAddr.newBuilder
                .setVersion(PIPVersion.V4)
                .setAddress(ip.toString)
                .build()
        }
    }

    feature("Test overlay vtep") {
        scenario("Test router with two vtep switches") {
            // In this test we want to check that:
            // 1) when a VXLAN packet ingresses the router's L3 port, it gets
            // decap'ed and egresses the correct vtep switch port.
            // 2) when a packet ingresses a vtep switch port, it gets encap'ed
            // and is routed via the L3 port.
            val host = createHost()
            store.create(host)

            val router = createRouter(name = Some("rtr1"),
                                      adminStateUp = true)
            store.create(router)

            val defaultVtepIp = IPv4Addr("10.10.0.123")
            val l3PortIp = IPv4Addr("10.0.0.1")
            val defaultGW = IPv4Addr("10.0.0.2")
            var l3port = createRouterPort(routerId = Some(router.getId),
                                          hostId = Some(host.getId),
                                          interfaceName = Some("l3port"),
                                          adminStateUp = true,
                                          portAddress = l3PortIp,
                                          portSubnet = new IPv4Subnet(l3PortIp, 30))
            store.create(l3port)

            // Create a route to the default gateway via the lone L3 port
            val route = createRoute(routerId = Some(router.getId),
                                    srcNetwork = new IPv4Subnet("0.0.0.0", 0),
                                    dstNetwork = new IPv4Subnet("0.0.0.0", 0),
                                    nextHop = NextHop.PORT,
                                    nextHopPortId = Some(l3port.getId),
                                    nextHopGateway = Some("10.0.0.2"))
            store.create(route)
            l3port = l3port.toBuilder.addRouteIds(route.getId).build()
            store.update(l3port)

            var l2port1 = createRouterPort(routerId = Some(router.getId),
                                           hostId = Some(host.getId),
                                           interfaceName = Some("l2port1"),
                                           adminStateUp = true)
            l2port1 = l2port1.toBuilder.setVni(10)
                .setDefaultRemoteVtep(defaultVtepIp).build()
            store.create(l2port1)

            var l2port2 = l2port1.toBuilder
                .setId(UUID.randomUUID.asProto)
                .setInterfaceName("l2port2")
                .setVni(20)
                .build()
            store.create(l2port2)


            // Load the cache to avoid NotYetException at simulation time
            for (port <- List(l3port, l2port1, l2port2)) {
                tryGet(() => VirtualTopology.tryGet[Port](port.getId))
            }
            tryGet(() => VirtualTopology.tryGet[Router](router.getId))

            // Set all the ports to "Active"
            for (port <- List(l3port, l2port1, l2port2)) {
                VirtualTopology.tryGet[Port](port.getId).toggleActive(true)
            }

            // Seed the router's ARP cache with the default gateway
            VirtualTopology.tryGet[Router](router.getId).arpCache.add(
                defaultGW,
                new ArpCacheEntry(
                    MAC.fromString("02:11:22:33:44:55"),
                    System.currentTimeMillis()+60000,
                    System.currentTimeMillis()+30000,
                    0 ))

            // Now build a VXLAN packet and inject it into the L3 port
            var innerPkt = packet("02:aa:bb:cc:dd:11", "02:aa:bb:cc:dd:22")
            var vxlanPkt = vxlanPacket("02:dd:dd:dd:dd:01", l3port.getPortMac,
                                       "10.11.12.13",
                                       l3PortIp.toString,
                                       10, innerPkt)
            When("The vxlan packet with vni=10 ingresses the L3 port")
            var packetContext = packetContextFor(vxlanPkt, l3port.getId)
            var result = simulate(packetContext)

            Then("The inner packet should be emitted from l2port1")
            result should be(toPort(l2port1.getId)())

            vxlanPkt = vxlanPacket("02:dd:dd:dd:dd:01", l3port.getPortMac,
                                       "10.11.12.13",
                                       l3PortIp.toString,
                                       20, innerPkt)
            When("The vxlan packet with vni=10 ingresses the L3 port")
            packetContext = packetContextFor(vxlanPkt, l3port.getId)
            result = simulate(packetContext)

            Then("The inner packet should be emitted from l2port1")
            result should be(toPort(l2port2.getId)())

            // Now inject the inner packet into one an the L2 port and verify
            // that it's encapsulated and emitted from the L3 port.
            When("The inner packet ingresses an L2 port")
            packetContext = packetContextFor(innerPkt, l2port1.getId)
            result = simulate(packetContext)

            Then("It should be encap'ed and emitted from the L3 port")
            result should be(toPort(l3port.getId)())

            /*
            waitForPortChain(vm1Port.getId, 3, true)
            waitForPortChain(vm1Port.getId, 2, false)
            waitForPortChain(srv1Port.getId, 2, true)
            */
        }

        scenario("Test two vtep routers connected via their L3 ports") {
            // In this test we want to check that:
            // 1) when an Ethernet packet ingresses R1's L2 port, it
            // gets encap'ed, emitted via the L3 port, ingresses R2's L3 port,
            // get decap'ed, and finally emitted via R2's L2 port.
            // The original packet egresses the L2 ports without modification.
            val host = createHost()
            store.create(host)

            val router1 = createRouter(name = Some("rtr1"),
                                       adminStateUp = true)
            val router2 = createRouter(name = Some("rtr2"),
                                       adminStateUp = true)
            store.create(router1)
            store.create(router2)

            // The L3 ports are interior - don't need host or interface name
            val l3Port1Ip = IPv4Addr("10.0.0.1")
            val l3Port2Ip = IPv4Addr("10.0.0.2")
            var l3port1 = createRouterPort(routerId = Some(router1.getId),
                                           adminStateUp = true,
                                           portAddress = l3Port1Ip,
                                           portSubnet = new IPv4Subnet(l3Port1Ip, 30))
            var l3port2 = createRouterPort(routerId = Some(router2.getId),
                                           adminStateUp = true,
                                           portAddress = l3Port2Ip,
                                           portSubnet = new IPv4Subnet(l3Port1Ip, 30))
            store.create(l3port1)
            store.create(l3port2)

            // Create a route to the default gateway via the lone L3 port
            val route1 = createRoute(routerId = Some(router1.getId),
                                     srcNetwork = new IPv4Subnet("0.0.0.0", 0),
                                     dstNetwork = new IPv4Subnet("0.0.0.0", 0),
                                     nextHop = NextHop.PORT,
                                     nextHopPortId = Some(l3port1.getId),
                                     nextHopGateway = Some("10.0.0.2"))
            val route2 = createRoute(routerId = Some(router2.getId),
                                     srcNetwork = new IPv4Subnet("0.0.0.0", 0),
                                     dstNetwork = new IPv4Subnet("0.0.0.0", 0),
                                     nextHop = NextHop.PORT,
                                     nextHopPortId = Some(l3port2.getId),
                                     nextHopGateway = Some("10.0.0.1"))
            store.create(route1)
            store.create(route2)

            l3port1 = l3port1.toBuilder.addRouteIds(route1.getId)
                .setPeerId(l3port2.getId).build()
            l3port2 = l3port2.toBuilder.addRouteIds(route2.getId)
                .setPeerId(l3port1.getId).build()
            store.update(l3port1)
            store.update(l3port2)

            var l2port1 = createRouterPort(routerId = Some(router1.getId),
                                           hostId = Some(host.getId),
                                           interfaceName = Some("l2port1"),
                                           adminStateUp = true)
            l2port1 = l2port1.toBuilder.setVni(10)
                .setDefaultRemoteVtep(l3Port2Ip).build()
            store.create(l2port1)

            var l2port2 = l2port1.toBuilder
                .setId(UUID.randomUUID.asProto)
                .setRouterId(router2.getId)
                .setInterfaceName("l2port2")
                .build() // Use the same VNI (different routers)
            store.create(l2port2)


            // Load the cache to avoid NotYetException at simulation time
            for (port <- List(l3port1, l3port2, l2port1, l2port2)) {
                tryGet(() => VirtualTopology.tryGet[Port](port.getId))
            }
            tryGet(() => VirtualTopology.tryGet[Router](router1.getId))
            tryGet(() => VirtualTopology.tryGet[Router](router2.getId))

            // Set all the ports to "Active"
            for (port <- List(l3port1, l3port2, l2port1, l2port2)) {
                VirtualTopology.tryGet[Port](port.getId).toggleActive(true)
            }

            // Don't neeed to seed the ARP cache for internal router ports.

            // Now inject the inner packet into R1's L2 port and verify
            // that it's encapsulated and emitted from the R2's L2 port.
            var innerPkt = packet("02:aa:bb:cc:dd:11", "02:aa:bb:cc:dd:22")
            When("An ethernet packet ingresses the first router's L2 port")
            var packetContext = packetContextFor(innerPkt, l2port1.getId)
            var result = simulate(packetContext)

            Then("It should egress the second router's L2 port without change")
            result should be(toPort(l2port2.getId)
                                 (FlowTagger.tagForPortRx(l2port1.getId),
                                  FlowTagger.tagForRouter(router1.getId),
                                  FlowTagger.tagForPortTx(l3port1.getId),
                                  FlowTagger.tagForPortRx(l3port2.getId),
                                  FlowTagger.tagForRouter(router2.getId),
                                  FlowTagger.tagForPortTx(l2port2.getId)))
        }

        ignore("Test two vtep routers connected via their L2 ports") {
            // In this test we want to check that:
            // 1) when a VXLAN packet ingresses R1's L3 port, it
            // gets decap'ed, emitted via the L2 port, ingresses R2's L2 port,
            // gets encap'ed, and finally emitted via R2's L3 port.
            // The outer Ethernet, IP, UDP source, and VNI should change
            val host = createHost()
            store.create(host)

            val router1 = createRouter(name = Some("rtr1"),
                                       adminStateUp = true)
            val router2 = createRouter(name = Some("rtr2"),
                                       adminStateUp = true)
            store.create(router1)
            store.create(router2)

            val defaultGW1 = IPv4Addr("10.10.0.2")
            val l3Port1Ip = IPv4Addr("10.10.0.1")
            val defaultGW2 = IPv4Addr("10.20.0.2")
            val l3Port2Ip = IPv4Addr("10.20.0.1")
            var l3port1 = createRouterPort(routerId = Some(router1.getId),
                                           hostId = Some(host.getId),
                                           interfaceName = Some("l3port1"),
                                           adminStateUp = true,
                                           portAddress = l3Port1Ip,
                                           portSubnet = new IPv4Subnet(l3Port1Ip, 30))
            var l3port2 = createRouterPort(routerId = Some(router2.getId),
                                           hostId = Some(host.getId),
                                           interfaceName = Some("l3port2"),
                                           adminStateUp = true,
                                           portAddress = l3Port2Ip,
                                           portSubnet = new IPv4Subnet(l3Port2Ip, 30))
            store.create(l3port1)
            store.create(l3port2)

            // Create a route to the default gateway via the lone L3 port
            val route1 = createRoute(routerId = Some(router1.getId),
                                     srcNetwork = new IPv4Subnet("0.0.0.0", 0),
                                     dstNetwork = new IPv4Subnet("0.0.0.0", 0),
                                     nextHop = NextHop.PORT,
                                     nextHopPortId = Some(l3port1.getId),
                                     nextHopGateway = Some(defaultGW1.toString))
            val route2 = createRoute(routerId = Some(router2.getId),
                                     srcNetwork = new IPv4Subnet("0.0.0.0", 0),
                                     dstNetwork = new IPv4Subnet("0.0.0.0", 0),
                                     nextHop = NextHop.PORT,
                                     nextHopPortId = Some(l3port2.getId),
                                     nextHopGateway = Some(defaultGW2.toString))
            store.create(route1)
            store.create(route2)

            l3port1 = l3port1.toBuilder.addRouteIds(route1.getId).build()
            l3port2 = l3port2.toBuilder.addRouteIds(route2.getId).build()
            store.update(l3port1)
            store.update(l3port2)

            // The L2 ports are interior, no host or interface name needed
            var l2port1 = createRouterPort(routerId = Some(router1.getId),
                                           adminStateUp = true)
            var l2port2 = createRouterPort(routerId = Some(router2.getId),
                                           adminStateUp = true)
            store.create(l2port1)
            store.create(l2port2)
            l2port1 = l2port1.toBuilder.setVni(10)
                .setDefaultRemoteVtep(defaultGW1).setPeerId(l2port2.getId).build()
            l2port2 = l2port2.toBuilder.setVni(20)
                .setDefaultRemoteVtep(defaultGW2).setPeerId(l2port1.getId).build()
            store.update(l2port1)
            store.update(l2port2)

            // Load the cache to avoid NotYetException at simulation time
            for (port <- List(l3port1, l3port2, l2port1, l2port2)) {
                tryGet(() => VirtualTopology.tryGet[Port](port.getId))
            }
            tryGet(() => VirtualTopology.tryGet[Router](router1.getId))
            tryGet(() => VirtualTopology.tryGet[Router](router2.getId))

            // Set all the ports to "Active"
            for (port <- List(l3port1, l3port2, l2port1, l2port2)) {
                VirtualTopology.tryGet[Port](port.getId).toggleActive(true)
            }

            // Seed the router's ARP cache with the default gateway
            VirtualTopology.tryGet[Router](router1.getId).arpCache.add(
                defaultGW1,
                new ArpCacheEntry(
                    MAC.fromString("02:11:22:55:55:55"),
                    System.currentTimeMillis()+60000,
                    System.currentTimeMillis()+30000,
                    0 ))
            VirtualTopology.tryGet[Router](router2.getId).arpCache.add(
                defaultGW2,
                new ArpCacheEntry(
                    MAC.fromString("02:11:22:66:66:66"),
                    System.currentTimeMillis()+60000,
                    System.currentTimeMillis()+30000,
                    0 ))

            // Now build a VXLAN packet and inject it into the L3 port
            var innerPkt = packet("02:aa:bb:cc:dd:11", "02:aa:bb:cc:dd:22")
            var vxlanPkt = vxlanPacket("02:dd:dd:dd:dd:01", l3port1.getPortMac,
                                       "10.11.12.13",
                                       l3Port1Ip.toString,
                                       10, innerPkt)
            When("The vxlan packet with vni=10 ingresses the R1's L3 port")
            var packetContext = packetContextFor(vxlanPkt, l3port1.getId)
            var result = simulate(packetContext)

            Then("It egresses R2's L3 port with vni=20")
            result should be(toPort(l3port2.getId)
                                 (FlowTagger.tagForPortRx(l3port1.getId),
                                  FlowTagger.tagForRouter(router1.getId),
                                  FlowTagger.tagForPortTx(l2port1.getId),
                                  FlowTagger.tagForPortRx(l2port2.getId),
                                  FlowTagger.tagForRouter(router2.getId),
                                  FlowTagger.tagForPortTx(l3port2.getId)))
        }
    }
}
