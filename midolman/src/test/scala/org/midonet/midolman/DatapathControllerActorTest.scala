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

import scala.concurrent.{ExecutionContext, Future}

import akka.actor._
import com.typesafe.config.{Config, ConfigValueFactory}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.host.interfaces.InterfaceDescription
import org.midonet.midolman.io.{ChannelType, UpcallDatapathConnectionManager}
import org.midonet.midolman.services.HostIdProviderService
import org.midonet.midolman.state.{FlowStateStorageFactory, MockStateStorage}
import org.midonet.midolman.topology.VirtualToPhysicalMapper
import org.midonet.midolman.topology.rcu.ResolvedHost
import org.midonet.midolman.util.mock.{MessageAccumulator, MockInterfaceScanner}
import org.midonet.midolman.util.{MidolmanSpec, MockNetlinkChannelFactory}
import org.midonet.netlink.NetlinkProtocol
import org.midonet.odp.ports._
import org.midonet.odp.{Datapath, DpPort}
import org.midonet.packets.IPv4Addr
import org.midonet.sdn.flows.FlowTagger

object DatapathControllerActorTest {
    val TestDhcpMtu: Short = 4200
}

@RunWith(classOf[JUnitRunner])
class DatapathControllerActorTest extends MidolmanSpec {
    import org.midonet.midolman.DatapathController._
    import org.midonet.midolman.DatapathControllerActorTest._
    import org.midonet.midolman.topology.VirtualToPhysicalMapper.{ZoneChanged, ZoneMembers}

    registerActors(DatapathController -> (() => new TestableDpC
                                                with MessageAccumulator),
                   VirtualToPhysicalMapper -> (() => new VirtualToPhysicalMapper))

    val emptyJSet = new java.util.HashSet[InterfaceDescription]()

    val dpPortGre = new GreTunnelPort("gre")
    val dpPortInt = new InternalPort("int")
    val dpPortDev = new NetDevPort("eth0")

    override def fillConfig(config: Config) = {
        super.fillConfig(config.withValue("agent.midolman.dhcp_mtu",
            ConfigValueFactory.fromAnyRef(TestDhcpMtu)))
    }

    class TestableDpC extends DatapathController(
            new DatapathStateDriver(new Datapath(0, "midonet")),
            injector.getInstance(classOf[HostIdProviderService]),
            new MockInterfaceScanner(),
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
            flowInvalidator,
            clock,
            new FlowStateStorageFactory() {
                override def create() = Future.successful(new MockStateStorage())
            },
        new MockNetlinkChannelFactory) {
    }

    var dpc: TestableDpC = _

    protected override def beforeTest() = {
        dpc = DatapathController.as[TestableDpC]
        newHost("host1", hostId)
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
        DatapathController.messages.collect { case p: ZoneMembers => p } should have size 1
        DatapathController.messages.collect { case p: ZoneChanged => p } should have size 1
        DatapathController.getAndClear() should have size 3

        val output = dpc.driver.asInstanceOf[DatapathStateDriver]
            .tunnelOverlayGre.toOutputAction
        val route1 = UnderlayResolver.Route(srcIp.toInt, dstIp1.toInt, output)
        dpc.driver.peerTunnelInfo(host2) should be (Some(route1))

        val tag1 = FlowTagger tagForTunnelRoute (srcIp.toInt, dstIp1.toInt)
        flowInvalidator should invalidate(tag1)

        // update the gre ip of the second host
        val dstIp2 = IPv4Addr("192.168.210.1")
        deleteTunnelZoneMember(tunnelZone, host2)
        deleteTunnelZoneMember(tunnelZone, host2)
        addTunnelZoneMember(tunnelZone, host2, dstIp2)

        DatapathController.messages.collect { case p: ZoneChanged => p } should have size 2
        DatapathController.getAndClear() should have size 2

        val route2 = UnderlayResolver.Route(srcIp.toInt, dstIp2.toInt, output)
        dpc.driver.peerTunnelInfo(host2) should be (Some(route2))

        val tag2 = FlowTagger tagForTunnelRoute (srcIp.toInt, dstIp2.toInt)
        flowInvalidator should invalidate(tag1, tag2)
    }

    scenario("Duplicate tunnelzone ips") {
        initialize()

        val tunnelZone = greTunnelZone("default")
        val host2 = newHost("host2")
        val host3 = newHost("host3")

        val srcIp = IPv4Addr("192.168.100.1")
        val dstIp1 = IPv4Addr("192.168.125.1")

        addTunnelZoneMember(tunnelZone, hostId, srcIp)
        addTunnelZoneMember(tunnelZone, host2, dstIp1)

        // The actor is started when the test is, but we
        // want to test behaviour that occurs when all tunnel zones
        // are loaded at once. This can be simulated by clearing
        // the DeviceCaches
        VirtualToPhysicalMapper.DeviceCaches.clear()
        addTunnelZoneMember(tunnelZone, host3, dstIp1)

        val output = dpc.driver.asInstanceOf[DatapathStateDriver]
            .tunnelOverlayGre.toOutputAction
        val route1 = UnderlayResolver.Route(srcIp.toInt, dstIp1.toInt, output)
        dpc.driver.peerTunnelInfo(host2) should be (Some(route1))
        dpc.driver.peerTunnelInfo(host3) should be (Some(route1))
    }


    private def initialize(): Unit = {
        DatapathController ! Initialize
        while (scheduler.pop exists { r => r.run(); true }) { }
        DatapathController.getAndClear()
    }
}
