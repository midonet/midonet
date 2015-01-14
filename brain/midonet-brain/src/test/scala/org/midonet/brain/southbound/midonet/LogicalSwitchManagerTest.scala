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

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit._

import scala.collection.mutable

import com.google.inject.{Guice, Injector}
import org.apache.commons.configuration.HierarchicalConfiguration
import org.junit.Assert._
import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.concurrent.Eventually._
import org.scalatest.junit.JUnitRunner
import org.slf4j.LoggerFactory

import org.midonet.brain.BrainTestUtils._
import org.midonet.brain.services.vxgw.MacLocation
import org.midonet.brain.southbound.vtep.VtepConstants.bridgeIdToLogicalSwitchName
import org.midonet.brain.southbound.vtep.VtepMAC.fromMac
import org.midonet.brain.southbound.vtep.{VtepPeer, VtepPeerImpl, VtepPeerPool}
import org.midonet.brain.util.TestZkTools
import org.midonet.cluster.DataClient
import org.midonet.cluster.data.Bridge.UNTAGGED_VLAN_ID
import org.midonet.cluster.util.ObservableTestUtils._
import org.midonet.midolman.state._
import org.midonet.packets.{IPv4Addr, MAC}

@RunWith(classOf[JUnitRunner])
class LogicalSwitchManagerTest extends FlatSpec with Matchers
                                                with BeforeAndAfter
                                                with GivenWhenThen
                                                with VxGWFixtures {

    val log = LoggerFactory.getLogger(classOf[LogicalSwitchManagerTest])

    var injector: Injector = _
    override var dataClient: DataClient = _

    val VTEP_PORT = 6632

    val mac1 = MAC.fromString("aa:bb:11:11:11:11")
    val mac2 = MAC.fromString("bb:cc:22:22:22:22")
    val mac3 = MAC.fromString("bb:cc:33:33:33:33")

    var vni1 = 1111
    var vni2 = 2222

    /** Add a tap to the VTEP peer */
    class MockVtepPeer(ip: IPv4Addr, port: Int) extends VtepPeerImpl(ip, port) {
        override val inbound = observer[MacLocation](0, 0, 0)
    }

    /** Hook on the VtepPool and inject our mocks whenever a VTEP Peer is
      * requested */
    val vtepPeers = mutable.Map[IPv4Addr, MockVtepPeer]()
    var vtepPool: VtepPeerPool = new VtepPeerPool {
        override def create(ip: IPv4Addr, port: Int): VtepPeer = {
            vtepPeers += ip -> new MockVtepPeer(ip, port)
            vtepPeers.get(ip).get
        }
    }

    val zkConnWatcher = TestZkTools.instantZkConnWatcher

    var mgrClosedLatch: CountDownLatch = _

    before {
        val config = new HierarchicalConfiguration()
        fillTestConfig(new HierarchicalConfiguration)
        val injector = Guice.createInjector(modules(config))
        val directory = injector.getInstance(classOf[Directory])
        setupZkTestDirectory(directory)

        dataClient = injector.getInstance(classOf[DataClient])
        assertNotNull(dataClient)

        mgrClosedLatch = new CountDownLatch(1)
    }

    "Initialization" should "generate the right logical switch name" in {
        Given("A bridge bound to a vtep")
        val ctx = new BridgeWithTwoPortsOnOneHost(mac1, mac2)
        val mgr = new LogicalSwitchManager(ctx.nwId, dataClient,
                                            null, zkConnWatcher,
                                            () => mgrClosedLatch.countDown() )
        mgr.lsName shouldBe bridgeIdToLogicalSwitchName(ctx.nwId)
    }

    "A bridge with local ports" should "publish mac-port updates" in {

        Given("A bridge bound to a VTEP")
        val ctx = new BridgeWithTwoPortsOnOneHost(mac1, mac2)

        // Pre populate the mac port map with some VM macs
        ctx.macPortMap.put(mac1, ctx.port1.getId)
        ctx.macPortMap.put(mac2, ctx.port2.getId)

        // bind the network to a VTEP
        Given("two VTEPs")
        val vteps = new TwoVtepsOn(ctx.tzId)

        When("A VxLAN port appears on a Network with")
        // The API would do both vxlan port creation + binding creation
        val vxPort1 = dataClient.bridgeCreateVxLanPort(ctx.nwId, vteps.ip1,
                                                       VTEP_PORT, vni1,
                                                       vteps.tunIp1, ctx.tzId)
        dataClient.vtepAddBinding(vteps.ip1, "eth0", 10, ctx.nwId)
        dataClient.vtepAddBinding(vteps.ip1, "eth0", 66, ctx.nwId)
        dataClient.vtepAddBinding(vteps.ip1, "eth1", 66, ctx.nwId)

        And("the peer starts")
        val mgr = new LogicalSwitchManager(ctx.nwId, dataClient, vtepPool,
                                           zkConnWatcher,
                                           () => { mgrClosedLatch.countDown() })
        mgr.start()

        while (vtepPeers.isEmpty) {
            Thread.`yield`()
        }
        val mockVtepPeer1 = vtepPeers.get(vteps.ip1).get

        Then("The VtepPeer for the bound VTEP joins the Logical Switch")
        assert(mockVtepPeer1.inbound.n.await(1, SECONDS))
        mockVtepPeer1.memberships should have size 1
        mockVtepPeer1.memberships.next().name shouldBe mgr.lsName

        mockVtepPeer1.inbound.getOnErrorEvents shouldBe empty
        mockVtepPeer1.inbound.getOnCompletedEvents shouldBe empty
        mockVtepPeer1.inbound.getOnNextEvents should contain only (
            MacLocation(fromMac(mac1), null, mgr.lsName, ctx.host1Ip),
            MacLocation(fromMac(mac2), null, mgr.lsName, ctx.host1Ip)
        )

        When("a new mac-port entry is added")
        mockVtepPeer1.inbound.reset(1, 0, 0)
        ctx.macPortMap.put(mac3, ctx.port1.getId)
        assertTrue(mockVtepPeer1.inbound.n.await(1, SECONDS))

        Then("the update should be received")
        mockVtepPeer1.inbound.getOnNextEvents should contain only (
            MacLocation(fromMac(mac1), null, mgr.lsName, ctx.host1Ip),
            MacLocation(fromMac(mac2), null, mgr.lsName, ctx.host1Ip),
            MacLocation(fromMac(mac3), null, mgr.lsName, ctx.host1Ip)
        )

        When("a second VTEP is bound to the same neutron network (same VNI)")
        dataClient.bridgeCreateVxLanPort(ctx.nwId, vteps.ip2, VTEP_PORT, vni1,
                                         vteps.tunIp2, ctx.tzId)
        dataClient.vtepAddBinding(vteps.ip2, "eth0", 10, ctx.nwId)
        dataClient.vtepAddBinding(vteps.ip2, "eth1", 43, ctx.nwId)
        while (vtepPeers.size < 2) {
            Thread.`yield`()
        }

        val mockVtepPeer2 = vtepPeers.get(vteps.ip2).get

        Then("a new VTEP Peer is added to the Logical Switch")
        assert(mockVtepPeer2.inbound.n.await(1, SECONDS))   // wait until primed
        mockVtepPeer2.memberships should have size 1
        mockVtepPeer2.memberships.next().name shouldBe mgr.lsName
        mockVtepPeer2.inbound.getOnErrorEvents shouldBe empty
        mockVtepPeer2.inbound.getOnCompletedEvents shouldBe empty
        mockVtepPeer2.inbound.getOnNextEvents should contain only (
            MacLocation(fromMac(mac1), null, mgr.lsName, ctx.host1Ip),
            MacLocation(fromMac(mac2), null, mgr.lsName, ctx.host1Ip),
            MacLocation(fromMac(mac3), null, mgr.lsName, ctx.host1Ip)
        )

        And("the first VTEP subscriber should not have any more updates")
        mockVtepPeer1.inbound.getOnNextEvents should have size 3 // unchanged

        When("the the VxLAN port of the first VTEP is deleted")
        dataClient.bridgeDeleteVxLanPort(vxPort1)

        eventually {
            mockVtepPeer1.memberships shouldBe empty
        }

        And("the first mac migrates to the second port")
        mockVtepPeer2.inbound.reset(2, 0, 0)
        dataClient.bridgeDeleteMacPort(ctx.nwId, UNTAGGED_VLAN_ID, mac1,
                                       ctx.port1.getId)
        dataClient.bridgeAddMacPort(ctx.nwId, UNTAGGED_VLAN_ID, mac1,
                                    ctx.port2.getId)
        assertTrue(mockVtepPeer2.inbound.n.await(1, SECONDS))

        Then("the first VTEP won't receive any more updates")
        mockVtepPeer1.inbound.getOnNextEvents should have size 3
        mockVtepPeer1.inbound.getOnErrorEvents shouldBe empty
        mockVtepPeer1.inbound.getOnCompletedEvents shouldBe empty

        And("the second VTEP will receive the new one")
        mockVtepPeer2.inbound.getOnErrorEvents shouldBe empty
        mockVtepPeer2.inbound.getOnCompletedEvents shouldBe empty
        // + deletion, + update
        mockVtepPeer2.inbound.getOnNextEvents should have size 5
        mockVtepPeer2.inbound.getOnNextEvents.get(4) shouldBe
            MacLocation(fromMac(mac1), null, mgr.lsName, ctx.host1Ip)

        // Yes, this last MacLocation was redundant, but for now we have no way
        // of knowing that the last time we emitted was with the same hostIp

        When("the VxLAN port of the second VTEP is deleted")
        mockVtepPeer2.inbound.reset(0, 0, 1)
        dataClient.bridgeDeleteVxLanPort(ctx.nwId, vteps.ip2)

        Then("the logical switch manager should close")
        assert(mgrClosedLatch.await(1, SECONDS))

        And("the second VTEP peer should abandon the logical switch")
        mockVtepPeer2.memberships shouldBe empty

        assertTrue(mockVtepPeer2.inbound.getOnNextEvents.size >= 5)
        mockVtepPeer2.inbound.getOnCompletedEvents shouldBe empty
        mockVtepPeer2.inbound.getOnErrorEvents shouldBe empty

        And("The first subscriber should not, since it's unsubscribed")
        mockVtepPeer1.inbound.getOnNextEvents should have size 3
        mockVtepPeer1.inbound.getOnCompletedEvents shouldBe empty
        mockVtepPeer1.inbound.getOnErrorEvents shouldBe empty

    }

}
