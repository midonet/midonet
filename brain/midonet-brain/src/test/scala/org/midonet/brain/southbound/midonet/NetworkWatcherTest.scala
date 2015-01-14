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

package org.midonet.brain.southbound.midonet

import java.util.UUID
import java.util.concurrent.TimeUnit._

import com.google.inject.{Guice, Injector}
import org.apache.commons.configuration.HierarchicalConfiguration
import org.apache.zookeeper.{KeeperException, WatchedEvent}
import org.junit.Assert._
import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner
import org.slf4j.LoggerFactory

import org.midonet.brain.BrainTestUtils._
import org.midonet.brain.services.vxgw.MacLocation
import org.midonet.brain.southbound.vtep.VtepConstants.bridgeIdToLogicalSwitchName
import org.midonet.brain.southbound.vtep.VtepMAC.fromMac
import org.midonet.cluster.DataClient
import org.midonet.cluster.data.Bridge.UNTAGGED_VLAN_ID
import org.midonet.cluster.data.host.Host
import org.midonet.cluster.data.ports.BridgePort
import org.midonet.cluster.data.{Bridge, TunnelZone, VTEP}
import org.midonet.cluster.util.ObservableTestUtils._
import org.midonet.midolman.state._
import org.midonet.packets.{IPv4Addr, MAC}

@RunWith(classOf[JUnitRunner])
class NetworkWatcherTest extends FlatSpec with Matchers with BeforeAndAfter with GivenWhenThen {

    var injector: Injector = _
    var dataClient: DataClient = _

    val VTEP_PORT = 6632

    val mac1 = MAC.fromString("aa:bb:11:11:11:11")
    val mac2 = MAC.fromString("bb:cc:22:22:22:22")
    val mac3 = MAC.fromString("bb:cc:33:33:33:33")

    var vni1 = 1111
    var vni2 = 2222

    private def makeVtep(ip: IPv4Addr, port: Int, tzId: UUID): VTEP = {
        val vtep = new VTEP()
        vtep.setId(ip)
        vtep.setMgmtPort(port)
        vtep.setTunnelZone(tzId)
        dataClient.vtepCreate(vtep)
        dataClient.vtepGet(ip)
    }

    def makeExtPort(deviceId: UUID, hostId: UUID, mac: MAC): BridgePort = {
        val p = new BridgePort()
        p.setDeviceId(deviceId)
        p.setHostId(hostId)
        p.setInterfaceName(s"eth-$mac1")
        val portId = dataClient.portsCreate(p)
        dataClient.hostsAddVrnPortMappingAndReturnPort(hostId, portId,
                                                       p.getInterfaceName)
        dataClient.portsGet(portId).asInstanceOf[BridgePort]
    }

    before {
        val config = new HierarchicalConfiguration()
        fillTestConfig(new HierarchicalConfiguration)
        val injector = Guice.createInjector(modules(config))
        val directory = injector.getInstance(classOf[Directory])
        setupZkTestDirectory(directory)

        dataClient = injector.getInstance(classOf[DataClient])
        assertNotNull(dataClient)
    }

    class BridgeWithTwoLocalPortsOneVTEP {
        val host1Ip = IPv4Addr.random
        val host = new Host()
        host.setName("Test")
        val hostId = dataClient.hostsCreate(UUID.randomUUID(), host)

        val tz = new TunnelZone()
        tz.setName("test")
        val tzId = dataClient.tunnelZonesCreate(tz)
        val zoneHost = new TunnelZone.HostConfig(hostId)
        zoneHost.setIp(host1Ip)
        dataClient.tunnelZonesAddMembership(tzId, zoneHost)

        val _b = new Bridge()
        _b.setName("Test_")

        val nwId = dataClient.bridgesCreate(_b)
        dataClient.bridgesGet(nwId)

        // Two ports on the same host
        val port1 = makeExtPort(nwId, hostId, mac1)
        val port2 = makeExtPort(nwId, hostId, mac1)

        val macPortMap = dataClient.bridgeGetMacTable(nwId, UNTAGGED_VLAN_ID,
                                                      false)

    }

    private val zkConnWatcher = new ZookeeperConnectionWatcher {
        val log = LoggerFactory.getLogger(this.getClass)
        override def getZkConnection: ZkConnection = ???
        override def setZkConnection(conn: ZkConnection): Unit = ???
        override def scheduleOnDisconnect(runnable: Runnable): Unit = ???
        override def scheduleOnReconnect(runnable: Runnable): Unit = ???
        override def handleError(objectDesc: String, retry: Runnable,
                                 e: KeeperException): Unit = retry.run()
        override def handleError(objectDesc: String, retry: Runnable,
                                 e: StateAccessException): Unit = retry.run()
        override def handleDisconnect(runnable: Runnable): Unit = runnable.run()
        override def handleTimeout(runnable: Runnable): Unit = runnable.run()
        override def process(event: WatchedEvent): Unit = {
            log.info(s"Ignoring $event")
        }
    }

    class TwoVtepsOn(tunnelZoneId: UUID) {
        val ip1 = IPv4Addr.fromString("10.0.0.100")
        val ip2 = IPv4Addr.fromString("10.0.0.200")

        val tunIp1 = IPv4Addr.fromString("10.0.0.100")
        val tunIp2 = IPv4Addr.fromString("10.0.0.200")

        val _1 = makeVtep(ip1, VTEP_PORT, tunnelZoneId)
        val _2 = makeVtep(ip2, VTEP_PORT, tunnelZoneId)
    }

    "Initialization" should "generate the right logical switch name" in {
        Given("A bridge bound to a vtep")
        val ctx = new BridgeWithTwoLocalPortsOneVTEP
        val peer = new NetworkWatcher(ctx.nwId, dataClient, zkConnWatcher)
        peer.lsName shouldBe bridgeIdToLogicalSwitchName(ctx.nwId)
    }

    "A bridge with local ports" should "publish mac-port updates" in {

        Given("A bridge bound to a VTEP")
        val ctx = new BridgeWithTwoLocalPortsOneVTEP
        val peer = new NetworkWatcher(ctx.nwId, dataClient, zkConnWatcher)

        // Pre populate the mac port map with some VM macs
        ctx.macPortMap.put(mac1, ctx.port1.getId)
        ctx.macPortMap.put(mac2, ctx.port2.getId)

        // bind the network to a VTEP
        When("two VTEPs are created")
        val vteps = new TwoVtepsOn(ctx.tzId)
        dataClient.bridgeCreateVxLanPort(ctx.nwId, vteps.ip1, VTEP_PORT, vni1,
                                         vteps.tunIp1, ctx.tzId)

        And("the peer starts")
        peer.start()

        And("a subscriber subscribes")
        val subscriber1 = observer[MacLocation](2, 0, 0)
        val subscription1 = peer.observableUpdates().subscribe(subscriber1)

        Then("it should receive the initial state")
        assertTrue(subscriber1.n.await(1, SECONDS))
        subscriber1.getOnErrorEvents shouldBe empty
        subscriber1.getOnCompletedEvents shouldBe empty
        subscriber1.getOnNextEvents should contain only (
            MacLocation(fromMac(mac1), null, peer.lsName, ctx.host1Ip),
            MacLocation(fromMac(mac2), null, peer.lsName, ctx.host1Ip)
        )

        When("a new mac-port entry is added")
        subscriber1.reset(1, 0, 0)
        ctx.macPortMap.put(mac3, ctx.port1.getId)
        assertTrue(subscriber1.n.await(1, SECONDS))

        Then("the update should be received")
        subscriber1.getOnNextEvents should contain only (
            MacLocation(fromMac(mac1), null, peer.lsName, ctx.host1Ip),
            MacLocation(fromMac(mac2), null, peer.lsName, ctx.host1Ip),
            MacLocation(fromMac(mac3), null, peer.lsName, ctx.host1Ip)
        )

        When("a second subscriber comes")
        val subscriber2 = observer[MacLocation](3, 0, 0)
        val subscription2 = peer.observableUpdates().subscribe(subscriber2)
        assertTrue(subscriber1.n.await(2, SECONDS))

        Then("the initial state should now include the last update")
        subscriber2.getOnErrorEvents shouldBe empty
        subscriber2.getOnCompletedEvents shouldBe empty
        subscriber2.getOnNextEvents should contain only (
            MacLocation(fromMac(mac1), null, peer.lsName, ctx.host1Ip),
            MacLocation(fromMac(mac2), null, peer.lsName, ctx.host1Ip),
            MacLocation(fromMac(mac3), null, peer.lsName, ctx.host1Ip)
        )

        And("the first subscriber should not have any more updates")
        subscriber1.getOnNextEvents should have size 3 // unchanged

        When("the first subscriber unsubscribes")
        subscription1.unsubscribe()

        And("the first mac migrates to the second port")
        subscriber2.reset(1, 0, 0)
        dataClient.bridgeDeleteMacPort(ctx.nwId, UNTAGGED_VLAN_ID, mac1,
                                       ctx.port1.getId)
        dataClient.bridgeAddMacPort(ctx.nwId, UNTAGGED_VLAN_ID, mac1,
                                    ctx.port2.getId)
        assertTrue(subscriber2.n.await(1, SECONDS))

        Then("the first subscriber won't receive any more updates")
        subscriber1.getOnNextEvents should have size 3
        subscriber1.getOnErrorEvents shouldBe empty
        subscriber1.getOnCompletedEvents shouldBe empty

        And("the second subscriber will receive it")
        subscriber2.getOnErrorEvents shouldBe empty
        subscriber2.getOnCompletedEvents shouldBe empty
        subscriber2.getOnNextEvents should have size 5 // + deletion, + update
        subscriber2.getOnNextEvents.get(4) shouldBe
            MacLocation(fromMac(mac1), null, peer.lsName, ctx.host1Ip)

        // Yes, this last MacLocation was redundant, but for now we have no way
        // of knowing that the last time we emitted was with the same hostIp

        When("The binding to the VTEP is deleted")
        subscriber2.reset(0, 0, 1)
        dataClient.bridgeDeleteVxLanPort(ctx.nwId, vteps.ip1)

        Then("The second subscriber should notice a completion")
        assertTrue(subscriber2.c.await(1, SECONDS))

        // some deletes might have passed
        assertTrue(subscriber2.getOnNextEvents.size >= 5)
        subscriber2.getOnCompletedEvents should have size 1
        subscriber2.getOnErrorEvents shouldBe empty

        And("The first subscriber should not, since it's unsubscribed")
        subscriber1.getOnNextEvents should have size 3
        subscriber1.getOnCompletedEvents shouldBe empty
        subscriber1.getOnErrorEvents shouldBe empty

    }

}
