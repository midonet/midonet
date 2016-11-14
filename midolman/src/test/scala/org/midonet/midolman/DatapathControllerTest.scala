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

import scala.concurrent.{ExecutionContext, Future}

import akka.actor._

import com.typesafe.config.{Config, ConfigValueFactory}

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.DatapathController.InterfacesUpdate
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.host.interfaces.InterfaceDescription
import org.midonet.midolman.io.{ChannelType, UpcallDatapathConnectionManager}
import org.midonet.midolman.services.HostIdProvider
import org.midonet.midolman.state.{FlowStateStorageFactory, MockStateStorage}
import org.midonet.midolman.topology.VirtualToPhysicalMapper.TunnelZoneUpdate
import org.midonet.midolman.topology.devices.Host
import org.midonet.midolman.util.mock.{MessageAccumulator, MockInterfaceScanner}
import org.midonet.midolman.util.{MidolmanSpec, MockNetlinkChannelFactory}
import org.midonet.odp.ports._
import org.midonet.odp.{Datapath, DpPort}
import org.midonet.packets.IPv4Addr
import org.midonet.sdn.flows.FlowTagger

@RunWith(classOf[JUnitRunner])
class DatapathControllerTest extends MidolmanSpec {

    registerActors(DatapathController -> (() => new TestableDpC
                                                    with MessageAccumulator))

    override def fillConfig(config: Config) = {
        super.fillConfig(config.withValue("agent.midolman.dhcp_mtu",
                                          ConfigValueFactory
                                              .fromAnyRef(4200)))
    }

    private def initialize(): Unit = {
        while (scheduler.pop exists { r => r.run(); true }) { }
        DatapathController.getAndClear()
    }

    class TestableDpC extends DatapathController(
        new DatapathStateDriver(new Datapath(0, "midonet")),
        injector.getInstance(classOf[HostIdProvider]),
        new MockInterfaceScanner(),
        config,
        new UpcallDatapathConnectionManager {
            var ports = Set.empty[DpPort]

            override def createAndHookDpPort(dp: Datapath, port: DpPort,
                                             t: ChannelType)
                                            (implicit ec: ExecutionContext,
                                             as: ActorSystem) = {
                if (ports contains port) {
                    Future.successful((DpPort.fakeFrom(port, 0), 0))
                } else {
                    ports += port
                    Future.failed(new IllegalArgumentException("fake error"))
                }
            }

            override def deleteDpPort(datapath: Datapath, port: DpPort)
                                     (implicit ec: ExecutionContext,
                                      as: ActorSystem) = {
                Future(true)(ec)
            }
        },
        simBackChannel,
        clock,
        new FlowStateStorageFactory() {
            override def create() = Future.successful(new MockStateStorage())
        },
        new MockNetlinkChannelFactory) {

        override def updateInterfaces(i: java.util.Set[InterfaceDescription])
        : Unit = { }
    }

    private var dpc: TestableDpC = _

    protected override def beforeTest() = {
        dpc = DatapathController.as[TestableDpC]
    }

    feature("The datapath controller handles the MTU") {
        scenario("The default MTU is read from the configuration") {
            Given("A configuration")
            val config = injector.getInstance(classOf[MidolmanConfig])

            Then("The default and minimum MTU are read from configuration")
            DatapathController.defaultMtu shouldBe config.dhcpMtu
            DatapathController.minMtu shouldBe config.dhcpMtu
        }

        scenario("The minimum MTU is computed for current interfaces") {
            Given("An interface")
            val interface1 = new InterfaceDescription("eth0", 1)
            interface1.setMtu(1500)
            interface1.setInetAddress("1.0.0.1")

            When("The datapath controller receives the host")
            val host = Host(UUID.randomUUID(), alive = true,
                            Map(UUID.randomUUID() -> IPv4Addr("1.0.0.1"),
                                UUID.randomUUID() -> IPv4Addr("1.0.0.2")), Map())
            DatapathController ! host

            When("The datapath controller receives the first interface")
            DatapathController ! InterfacesUpdate(Set(interface1))

            Then("The minimum MTU is updated")
            DatapathController.minMtu shouldBe 1500 - VxLanTunnelPort.TUNNEL_OVERHEAD

            Given("Another interface")
            val interface2 = new InterfaceDescription("eth0", 1)
            interface2.setMtu(1000)
            interface2.setInetAddress("1.0.0.2")

            When("The datapath controller receives both interfaces")
            DatapathController ! InterfacesUpdate(Set(interface1, interface2))

            Then("The minimum MTU is updated")
            DatapathController.minMtu shouldBe 1000 - VxLanTunnelPort.TUNNEL_OVERHEAD

            When("The datapath controller receives the first interface")
            DatapathController ! InterfacesUpdate(Set(interface1))

            Then("The minimum MTU is updated")
            DatapathController.minMtu shouldBe 1500 - VxLanTunnelPort.TUNNEL_OVERHEAD
        }

        scenario("Datapath controller handles maximum MTU") {
            Given("An interface")
            val interface = new InterfaceDescription("eth0", 1)
            interface.setMtu(0xffff)
            interface.setInetAddress("1.0.0.1")

            When("The datapath controller receives the host")
            val host = Host(UUID.randomUUID(), alive = true,
                            Map(UUID.randomUUID() -> IPv4Addr("1.0.0.1")), Map())
            DatapathController ! host

            When("The datapath controller receives the interface")
            DatapathController ! InterfacesUpdate(Set(interface))

            Then("The minimum MTU is updated")
            DatapathController.minMtu shouldBe 0xffff - VxLanTunnelPort.TUNNEL_OVERHEAD
        }

        scenario("Datapath controller ignores interfaces without tunnel zone") {
            Given("An interface")
            val interface = new InterfaceDescription("eth0", 1)
            interface.setMtu(0xffff)
            interface.setInetAddress("1.0.0.1")

            When("The datapath controller receives the host")
            val host = Host(UUID.randomUUID(), alive = true,
                            Map(UUID.randomUUID() -> IPv4Addr("1.0.0.2")), Map())
            DatapathController ! host

            When("The datapath controller receives the interface")
            DatapathController ! InterfacesUpdate(Set(interface))

            Then("The minimum MTU is updated")
            DatapathController.minMtu shouldBe DatapathController.defaultMtu
        }

        scenario("Datapath controller ignores interfaces without addresses") {
            Given("An interface")
            val interface = new InterfaceDescription("eth0", 1)
            interface.setMtu(0xffff)

            When("The datapath controller receives the host")
            val host = Host(UUID.randomUUID(), alive = true,
                            Map(UUID.randomUUID() -> IPv4Addr("1.0.0.1")), Map())
            DatapathController ! host

            When("The datapath controller receives the interface")
            DatapathController ! InterfacesUpdate(Set(interface))

            Then("The minimum MTU is updated")
            DatapathController.minMtu shouldBe DatapathController.defaultMtu
        }

        scenario("The MTU does not exceed 0xffff") {
            Given("An interface")
            val interface = new InterfaceDescription("eth0", 1)
            interface.setMtu(Int.MaxValue)
            interface.setInetAddress("1.0.0.1")

            When("The datapath controller receives the host")
            val host = Host(UUID.randomUUID(), alive = true,
                            Map(UUID.randomUUID() -> IPv4Addr("1.0.0.1")), Map())
            DatapathController ! host

            When("The datapath controller receives the interface")
            DatapathController ! InterfacesUpdate(Set(interface))

            Then("The minimum MTU is updated")
            DatapathController.minMtu shouldBe 0xffff
        }

        scenario("The MTU reverts to default with no tunnel interfaces") {
            Given("An interface")
            val interface = new InterfaceDescription("eth0", 1)
            interface.setMtu(1000)
            interface.setInetAddress("1.0.0.1")

            When("The datapath controller receives the host")
            val host = Host(UUID.randomUUID(), alive = true,
                            Map(UUID.randomUUID() -> IPv4Addr("1.0.0.1")), Map())
            DatapathController ! host

            When("The datapath controller receives the interface")
            DatapathController ! InterfacesUpdate(Set(interface))

            Then("The minimum MTU is updated")
            DatapathController.minMtu shouldBe 1000 - VxLanTunnelPort.TUNNEL_OVERHEAD

            When("The datapath controller receives no interfaces")
            DatapathController ! InterfacesUpdate(Set())

            Then("The minimum MTU reverts to default")
            DatapathController.minMtu shouldBe DatapathController.defaultMtu
        }
    }

    scenario("The datapath controller initializes tunnel ports")
    {
        dpc.driver.tunnelOverlayGre should be(null)
        dpc.driver.tunnelOverlayVxLan should be(null)
        dpc.driver.tunnelVtepVxLan should be(null)
        dpc.driver.tunnelVppVxlan shouldBe null

        initialize()

        dpc.driver.tunnelOverlayGre should not be null
        dpc.driver.tunnelOverlayVxLan should not be null
        dpc.driver.tunnelVtepVxLan should not be null
        dpc.driver.tunnelVppVxlan should not be null
    }

    scenario("The datapath controller handles tunnel zones") {
        initialize()

        val tunnelZone = greTunnelZone("default")
        val host2 = newHost("host2")

        val srcIp = IPv4Addr("192.168.100.1")
        val dstIp1 = IPv4Addr("192.168.125.1")

        addTunnelZoneMember(tunnelZone, hostId, srcIp)
        addTunnelZoneMember(tunnelZone, host2, dstIp1)

        DatapathController.messages
            .collect { case p: Host => p } should have size 1
        DatapathController.messages
            .collect { case p: TunnelZoneUpdate => p } should have size 2
        DatapathController.getAndClear() should have size 3

        val output = dpc.driver.asInstanceOf[DatapathStateDriver]
            .tunnelOverlayGre.toOutputAction
        val route1 = UnderlayResolver
            .Route(srcIp.toInt, dstIp1.toInt, output)
        dpc.driver.peerTunnelInfo(host2) should be(Some(route1))

        val tag1 = FlowTagger tagForTunnelRoute(srcIp.toInt, dstIp1.toInt)
        simBackChannel should invalidate(tag1)

        // update the gre ip of the second host
        val dstIp2 = IPv4Addr("192.168.210.1")
        deleteTunnelZoneMember(tunnelZone, host2)
        deleteTunnelZoneMember(tunnelZone, host2)
        addTunnelZoneMember(tunnelZone, host2, dstIp2)

        DatapathController.messages
            .collect { case p: TunnelZoneUpdate => p } should have size 2
        DatapathController.getAndClear() should have size 2

        val route2 = UnderlayResolver
            .Route(srcIp.toInt, dstIp2.toInt, output)
        dpc.driver.peerTunnelInfo(host2) should be(Some(route2))

        val tag2 = FlowTagger tagForTunnelRoute(srcIp.toInt, dstIp2.toInt)
        simBackChannel should invalidate(tag1, tag2)
    }

}
