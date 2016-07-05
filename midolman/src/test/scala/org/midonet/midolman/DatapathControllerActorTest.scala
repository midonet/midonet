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

import akka.actor._
import com.typesafe.config.{Config, ConfigValueFactory}
import org.junit.runner.RunWith
import org.midonet.midolman.DatapathController.InterfacesUpdate_
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.host.interfaces.InterfaceDescription
import org.midonet.midolman.io.{ChannelType, UpcallDatapathConnectionManager}
import org.midonet.midolman.services.HostIdProvider
import org.midonet.midolman.state.{FlowStateStorageFactory, MockStateStorage}
import org.midonet.midolman.topology.VirtualToPhysicalMapper.TunnelZoneUpdate
import org.midonet.midolman.topology.rcu.{PortBinding, ResolvedHost}
import org.midonet.midolman.util.mock.{MessageAccumulator, MockInterfaceScanner}
import org.midonet.midolman.util.{MidolmanSpec, MockNetlinkChannelFactory}
import org.midonet.odp.ports._
import org.midonet.odp.{Datapath, DpPort}
import org.midonet.packets.IPv4Addr
import org.midonet.sdn.flows.FlowTagger
import org.scalatest.junit.JUnitRunner

import scala.collection.immutable
import scala.concurrent.{ExecutionContext, Future}

object DatapathControllerActorTest {
    val TestDhcpMtu: Short = 4200
}

@RunWith(classOf[JUnitRunner])
class DatapathControllerActorTest extends MidolmanSpec {
    import org.midonet.midolman.DatapathControllerActorTest._

    registerActors(DatapathController -> (() => new TestableDpC
                                                with MessageAccumulator))

    val emptyJSet = new java.util.HashSet[InterfaceDescription]()

    val dpPortGre = new GreTunnelPort("gre")
    val dpPortInt = new InternalPort("int")
    val dpPortDev = new NetDevPort("eth0")

    val interfaceScanner = new MockInterfaceScanner

    private def addInterface(name: String, mtu: Int, ipAddr: IPv4Addr) {
        val intf = new InterfaceDescription(name)
        intf.setInetAddress(ipAddr.toString)
        intf.setMtu(mtu)
        intf.setUp(true)
        intf.setHasLink(true)
        interfaceScanner.addInterface(intf)
    }

    override def fillConfig(config: Config) = {
        super.fillConfig(config.withValue("agent.midolman.dhcp_mtu",
            ConfigValueFactory.fromAnyRef(TestDhcpMtu)))
    }

    class TestableDpC extends DatapathController(
            new DatapathStateDriver(new Datapath(0, "midonet")),
            injector.getInstance(classOf[HostIdProvider]),
            interfaceScanner,
            config,
            new UpcallDatapathConnectionManager {
                var ports = Set.empty[DpPort]

                override def createAndHookDpPort(dp: Datapath, port: DpPort, t: ChannelType)
                                                (implicit ec: ExecutionContext,
                                                          as: ActorSystem) =
                    if (ports contains port) {
                        Future.successful((DpPort.fakeFrom(port, 0), 0))
                    } else {
                        ports += port
                        Future.failed(new IllegalArgumentException("fake error"))
                    }

                    override def deleteDpPort(datapath: Datapath, port: DpPort)
                                             (implicit ec: ExecutionContext,
                                                       as: ActorSystem) =
                        Future(true)(ec)

                },
            simBackChannel,
            clock,
            new FlowStateStorageFactory() {
                override def create() = Future.successful(new MockStateStorage())
            },
        new MockNetlinkChannelFactory) {
    }

    var dpc: TestableDpC = _

    protected override def beforeTest() = {
        dpc = DatapathController.as[TestableDpC]
    }

    scenario("The default MTU should be retrieved from the config file") {
        val config = injector.getInstance(classOf[MidolmanConfig])
        DatapathController.defaultMtu should be (config.dhcpMtu)
        DatapathController.defaultMtu should be (TestDhcpMtu)
        DatapathController.defaultMtu should not be MidolmanConfig.DEFAULT_MTU
    }

    scenario("The DPC retries when the port creation fails") {
        dpc.driver.tunnelOverlayGre should be (null)
        dpc.driver.tunnelOverlayVxLan should be (null)
        dpc.driver.tunnelVtepVxLan should be (null)

        initialize()

        dpc.driver.tunnelOverlayGre should not be null
        dpc.driver.tunnelOverlayVxLan should not be null
        dpc.driver.tunnelVtepVxLan should not be null
    }

    scenario("DatapathActor handles tunnel zones") {
        initialize()

        val tunnelZone = greTunnelZone("default")
        val host2 = newHost("host2")

        val srcIp = IPv4Addr("192.168.100.1")
        val dstIp1 = IPv4Addr("192.168.125.1")

        addTunnelZoneMember(tunnelZone, hostId, srcIp)
        addTunnelZoneMember(tunnelZone, host2, dstIp1)

        DatapathController.messages.collect { case p: ResolvedHost => p } should have size 1
        DatapathController.messages.collect { case p: TunnelZoneUpdate => p } should have size 2
        DatapathController.getAndClear() should have size 3

        val output = dpc.driver.asInstanceOf[DatapathStateDriver]
            .tunnelOverlayGre.toOutputAction
        val route1 = UnderlayResolver.Route(srcIp.toInt, dstIp1.toInt, output)
        dpc.driver.peerTunnelInfo(host2) should be (Some(route1))

        val tag1 = FlowTagger tagForTunnelRoute (srcIp.toInt, dstIp1.toInt)
        simBackChannel should invalidate(tag1)

        // update the gre ip of the second host
        val dstIp2 = IPv4Addr("192.168.210.1")
        deleteTunnelZoneMember(tunnelZone, host2)
        deleteTunnelZoneMember(tunnelZone, host2)
        addTunnelZoneMember(tunnelZone, host2, dstIp2)

        DatapathController.messages.collect { case p: TunnelZoneUpdate => p } should have size 2
        DatapathController.getAndClear() should have size 2

        val route2 = UnderlayResolver.Route(srcIp.toInt, dstIp2.toInt, output)
        dpc.driver.peerTunnelInfo(host2) should be (Some(route2))

        val tag2 = FlowTagger tagForTunnelRoute (srcIp.toInt, dstIp2.toInt)
        simBackChannel should invalidate(tag1, tag2)
    }

    scenario("No subscription to interface scanner until ResolvedHost received")
    {
        Given("An initialized datapath controller")
        initialize()
        val tunnelZone = greTunnelZone("default")
        val ip = IPv4Addr("192.168.0.1")

        Then("No messages in queue and no subscription to interface scanner")
        dpc.portWatcher shouldBe null
        DatapathController.messages should have size 0

        When("Adding a tunnel interface in a tunnel zone")
        addInterface("if1", 2000, ip)

        Then("No interfaces cached because no updates received")
        dpc.cachedInterfaces shouldBe null
        dpc.portWatcher shouldBe null
        DatapathController.messages.collect { case p: ResolvedHost => p } should have size 0
        DatapathController.messages.collect { case p: InterfacesUpdate_ => p } should have size 0
        DatapathController.messages.collect { case p: TunnelZoneUpdate => p } should have size 0

        When("Sending a resolved host message")
        val resolved = ResolvedHost(hostId, true,
                     immutable.Map.empty[UUID, PortBinding],
                     immutable.HashMap(tunnelZone -> ip))
        dpc.self ! resolved

        Then("We are subscribed to the interface scanner")
        dpc.portWatcher should not be null
        dpc.portWatcher.isUnsubscribed shouldBe false
        DatapathController.messages.collect { case p: ResolvedHost => p } should have size 1

        And("We receive the initial set of local interfaces")
        dpc.cachedInterfaces should not be null
        dpc.cachedInterfaces should have size 1
        dpc.zones should have size 1
        DatapathController.messages.collect { case p: InterfacesUpdate_ => p } should have size 2
    }

    private def initialize(): Unit = {
        while (scheduler.pop exists { r => r.run(); true }) { }
        DatapathController.getAndClear()
        dpc.portWatcher.unsubscribe()
        dpc.portWatcher = null
        dpc.cachedInterfaces = null
        dpc.initialize()
    }
}
