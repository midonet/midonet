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

package org.midonet.brain.services.vxgw

import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit._

import scala.collection.mutable.ListBuffer

import com.google.inject.{Guice, Injector}
import org.apache.commons.configuration.HierarchicalConfiguration
import org.junit.Assert._
import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.concurrent.Eventually._
import org.scalatest.junit.JUnitRunner
import org.slf4j.LoggerFactory

import org.midonet.brain.BrainTestUtils._
import org.midonet.brain.southbound.vtep.VtepConstants.bridgeIdToLogicalSwitchName
import org.midonet.brain.southbound.vtep.VtepMAC
import org.midonet.brain.southbound.vtep.VtepMAC.fromMac
import org.midonet.brain.util.TestZkTools
import org.midonet.cluster.DataClient
import org.midonet.cluster.data.Bridge.UNTAGGED_VLAN_ID
import org.midonet.midolman.state._
import org.midonet.packets.{IPv4Addr, MAC}

@RunWith(classOf[JUnitRunner])
class VxlanGatewayManagerTest extends FlatSpec with Matchers
                                               with BeforeAndAfter
                                               with GivenWhenThen
                                               with VxlanGatewayTest {

    val log = LoggerFactory.getLogger(classOf[VxlanGatewayManagerTest])

    var injector: Injector = _
    override var dataClient: DataClient = _

    val VTEP_PORT = 6632

    val mac1 = MAC.fromString("aa:bb:11:11:11:11")
    val mac2 = MAC.fromString("bb:cc:22:22:22:22")
    val mac3 = MAC.fromString("bb:cc:33:33:33:33")

    var vni1 = 1111
    var vni2 = 2222

    val vtepConfigs = ListBuffer[MockVtepConfig]()

    var vtepPool: VtepPool = _

    val zkConnWatcher = TestZkTools.instantZkConnWatcher

    var mgrClosedLatch: CountDownLatch = _

    var tzState: TunnelZoneStatePublisher = _
    var hostState: HostStatePublisher = _

    before {
        val config = new HierarchicalConfiguration()
        fillTestConfig(new HierarchicalConfiguration)
        injector = Guice.createInjector(modules(config))
        val directory = injector.getInstance(classOf[Directory])
        setupZkTestDirectory(directory)

        dataClient = injector.getInstance(classOf[DataClient])
        assertNotNull(dataClient)

        hostState = new HostStatePublisher(dataClient, zkConnWatcher)
        tzState = new TunnelZoneStatePublisher(dataClient, zkConnWatcher,
                                                   hostState, new Random)

        mgrClosedLatch = new CountDownLatch(1)

        // WATCH OUT: this factory assumes that VxlanGatewayTest.TwoVtepsOn
        // generates the tunnel ip as the next to management ip.
        vtepPool = new VtepPool(dataClient, zkConnWatcher, tzState) {
            override def create(ip: IPv4Addr, port: Int): Vtep = {
                val mockConfig = new MockVtepConfig(ip, port, ip.next,
                                                     Seq.empty)
                vtepConfigs += mockConfig
                new VtepController(mockConfig, dataClient, zkConnWatcher,
                                   tzState)
            }
        }
    }

    after {
        tzState.dispose()
        hostState.dispose()
        vtepConfigs.clear()
    }

    "Initialization" should "generate the right logical switch name" in {
        Given("A bridge bound to a vtep")
        val host = new HostOnVtepTunnelZone(1)
        val ctx = new BridgeWithTwoPortsOnOneHost(mac1, mac2, host.id)
        val mgr = new VxlanGatewayManager(ctx.nwId, dataClient, null,
                                          zkConnWatcher,
                                          () => mgrClosedLatch.countDown() )
        mgr.lsName shouldBe bridgeIdToLogicalSwitchName(ctx.nwId)

        mgr.terminate()

        ctx.delete()
        host.delete()
    }

    "A bridge with local ports" should "publish mac updates from Midonet" in {

        Given("A bridge bound to a VTEP")
        val host = new HostOnVtepTunnelZone(1)
        val ctx = new BridgeWithTwoPortsOnOneHost(mac1, mac2, host.id)

        // Pre populate the mac port map with some VM macs
        ctx.macPortMap.put(mac1, ctx.port1.getId)
        ctx.macPortMap.put(mac2, ctx.port2.getId)

        // bind the network to a VTEP
        Given("two VTEPs")
        val vteps = new TwoVtepsOn(host.tzId)

        When("a VxLAN port appears on a Network")
        // The API would do both vxlan port creation + binding creation
        val vxPort1 = dataClient.bridgeCreateVxLanPort(ctx.nwId, vteps.ip1,
                                                       VTEP_PORT, vni1,
                                                       vteps.tunIp1, host.id)
        dataClient.vtepAddBinding(vteps.ip1, "eth0", 10, ctx.nwId)
        dataClient.vtepAddBinding(vteps.ip1, "eth0", 66, ctx.nwId)
        dataClient.vtepAddBinding(vteps.ip1, "eth1", 66, ctx.nwId)

        And("a vxlan gateway manager starts")
        val mgr = new VxlanGatewayManager(ctx.nwId, dataClient, vtepPool,
                                          zkConnWatcher,
                                          () => { mgrClosedLatch.countDown() })
        mgr.start()

        Then("the VTEP joins the Vxlan Gateway")
        eventually {    // the ovsdb link to the first VTEP is stablished
            vtepConfigs should have size 1
        }

        val vtep1 = vtepPool.fishIfExists(vteps.ip1, vteps.vtepPort).get
        val vtep1MacRemotes = vtepConfigs(0).macRemoteUpdates
        vtep1.memberships should have size 1
        vtep1.memberships(0).name shouldBe mgr.lsName

        eventually {    // the initial state reaches the VTEP
            vtep1MacRemotes.getOnNextEvents should have size 2
        }
        vtep1MacRemotes.getOnErrorEvents shouldBe empty
        vtep1MacRemotes.getOnCompletedEvents shouldBe empty
        vtep1MacRemotes.getOnNextEvents should contain only (
            MacLocation(fromMac(mac1), null, mgr.lsName, host.ip),
            MacLocation(fromMac(mac2), null, mgr.lsName, host.ip)
        )

        When("a new mac-port entry is added")
        ctx.macPortMap.put(mac3, ctx.port1.getId)
        eventually {
           vtep1MacRemotes.getOnNextEvents should have size 3
        }

        Then("the update should be received")
        vtep1MacRemotes.getOnNextEvents should contain only (
            MacLocation(fromMac(mac1), null, mgr.lsName, host.ip),
            MacLocation(fromMac(mac2), null, mgr.lsName, host.ip),
            MacLocation(fromMac(mac3), null, mgr.lsName, host.ip)
        )

        When("a second VTEP is bound to the same neutron network (same VNI)")
        dataClient.bridgeCreateVxLanPort(ctx.nwId, vteps.ip2, VTEP_PORT, vni1,
                                         vteps.tunIp2, host.tzId)
        dataClient.vtepAddBinding(vteps.ip2, "eth0", 10, ctx.nwId)
        dataClient.vtepAddBinding(vteps.ip2, "eth1", 43, ctx.nwId)

        eventually {
            vtepConfigs should have size 2
        }

        val vtep2 = vtepPool.fishIfExists(vteps.ip2, vteps.vtepPort).get
        val vtep2MacRemotes = vtepConfigs(1).macRemoteUpdates

        Then("a new VTEP joins the Vxlan Gateway")
        eventually {
            vtep2MacRemotes.getOnNextEvents should have size 3
        }
        vtep2.memberships should have size 1
        vtep2.memberships(0).name shouldBe mgr.lsName
        vtep2MacRemotes.getOnErrorEvents shouldBe empty
        vtep2MacRemotes.getOnCompletedEvents shouldBe empty
        vtep2MacRemotes.getOnNextEvents should contain only (
            MacLocation(fromMac(mac1), null, mgr.lsName, host.ip),
            MacLocation(fromMac(mac2), null, mgr.lsName, host.ip),
            MacLocation(fromMac(mac3), null, mgr.lsName, host.ip)
        )

        And("the first VTEP didn't see any more updates")
        vtep1MacRemotes.getOnNextEvents should have size 3

        When("a new IP appears on a MidoNet port")
        val newIp = IPv4Addr.random
        ctx.arpTable.put(newIp, mac2)

        Then("both VTEPs should see the update")
        eventually {
            vtep1MacRemotes.getOnNextEvents should have size 4
            vtep2MacRemotes.getOnNextEvents should have size 4
        }

        val vtepMac2 = VtepMAC.fromMac(mac2)
        val newMl = MacLocation(vtepMac2, newIp, mgr.lsName, host.ip)
        vtep1MacRemotes.getOnNextEvents.get(3) shouldBe newMl
        vtep1MacRemotes.getOnNextEvents.get(3) shouldBe newMl

        When("the VxLAN port corresponding to the first VTEP is deleted")
        dataClient.bridgeDeleteVxLanPort(vxPort1)

        eventually {
            vtep1.memberships shouldBe empty
        }

        And("the first mac migrates to the second port")
        vtep2MacRemotes.reset(2, 0, 0)
        dataClient.bridgeDeleteMacPort(ctx.nwId, UNTAGGED_VLAN_ID, mac1,
                                       ctx.port1.getId)
        dataClient.bridgeAddMacPort(ctx.nwId, UNTAGGED_VLAN_ID, mac1,
                                    ctx.port2.getId)

        assertTrue(vtep2MacRemotes.n.await(1, SECONDS))

        Then("the first VTEP won't receive any more updates")
        vtep1MacRemotes.getOnNextEvents should have size 4
        vtep1MacRemotes.getOnErrorEvents shouldBe empty
        vtep1MacRemotes.getOnCompletedEvents shouldBe empty

        And("the second VTEP will receive the new one")
        vtep2MacRemotes.getOnErrorEvents shouldBe empty
        vtep2MacRemotes.getOnCompletedEvents shouldBe empty
        // + deletion, + update
        vtep2MacRemotes.getOnNextEvents should have size 6
        vtep2MacRemotes.getOnNextEvents.get(5) shouldBe
            MacLocation(fromMac(mac1), null, mgr.lsName, host.ip)

        // Yes, this last MacLocation was redundant, but for now we have no way
        // of knowing that the last time we emitted was with the same hostIp

        When("the VxLAN port corresponding to the second VTEP is deleted")
        vtep2MacRemotes.reset(0, 0, 1)
        dataClient.bridgeDeleteVxLanPort(ctx.nwId, vteps.ip2)

        Then("the VxLAN Gateway manager should close")
        assert(mgrClosedLatch.await(1, SECONDS))

        And("the second VTEP should abandon the VxLAN Gateway")
        vtep2.memberships shouldBe empty

        assertTrue(vtep2MacRemotes.getOnNextEvents.size >= 6)
        vtep2MacRemotes.getOnCompletedEvents shouldBe empty
        vtep2MacRemotes.getOnErrorEvents shouldBe empty

        And("The first subscriber should not, since it's unsubscribed")
        vtep1MacRemotes.getOnNextEvents should have size 4
        vtep1MacRemotes.getOnCompletedEvents shouldBe empty
        vtep1MacRemotes.getOnErrorEvents shouldBe empty

        ctx.delete()
        host.delete()
    }

    "A bridge with local ports" should "process updates from VTEPs" in {

        Given("A bridge bound to a VTEP")
        val host = new HostOnVtepTunnelZone(1)
        val ctx = new BridgeWithTwoPortsOnOneHost(mac1, mac2, host.id)

        // Pre populate the mac port map with some VM macs
        ctx.macPortMap.put(mac1, ctx.port1.getId)
        ctx.macPortMap.put(mac2, ctx.port2.getId)

        // bind the network to a VTEP
        Given("two VTEPs")
        val vteps = new TwoVtepsOn(host.tzId)

        When("a VxLAN port appears on a Network")
        // The API would do both vxlan port creation + binding creation
        val vxPort1 = dataClient.bridgeCreateVxLanPort(ctx.nwId, vteps.ip1,
                                                       VTEP_PORT, vni1,
                                                       vteps.tunIp1, host.id)
        dataClient.vtepAddBinding(vteps.ip1, "eth0", 10, ctx.nwId)
        dataClient.vtepAddBinding(vteps.ip1, "eth0", 66, ctx.nwId)
        dataClient.vtepAddBinding(vteps.ip1, "eth1", 66, ctx.nwId)

        And("a vxlan gateway manager starts")
        val mgr = new VxlanGatewayManager(ctx.nwId, dataClient, vtepPool,
                                          zkConnWatcher,
                                          () => { mgrClosedLatch.countDown() })
        mgr.start()

        Then("the VTEP joins the Vxlan Gateway")
        eventually {    // the ovsdb link to the first VTEP is stablished
            vtepConfigs should have size 1
        }

        val vtep1 = vtepPool.fishIfExists(vteps.ip1, vteps.vtepPort).get
        val vtep1MacRemotes = vtepConfigs(0).macRemoteUpdates
        vtep1.memberships should have size 1
        vtep1.memberships(0).name shouldBe mgr.lsName

        eventually {    // the initial state reaches the VTEP
           vtep1MacRemotes.getOnNextEvents should have size 2
        }
        vtep1MacRemotes.getOnErrorEvents shouldBe empty
        vtep1MacRemotes.getOnCompletedEvents shouldBe empty
        vtep1MacRemotes.getOnNextEvents should contain only (
            MacLocation(fromMac(mac1), null, mgr.lsName, host.ip),
            MacLocation(fromMac(mac2), null, mgr.lsName, host.ip)
        )

        When("the VTEP reports a new MAC")
        val macOnVtep = VtepMAC.fromString("aa:aa:bb:bb:cc:cc")
        val ipOnVtep = IPv4Addr.random
        var ml = MacLocation(macOnVtep, ipOnVtep, mgr.lsName, vteps.tunIp1)
        vtepConfigs(0).macLocalUpdates.onNext(ml)

        Then("the MAC sent from the hardware VTEP should reach MidoNet")
        eventually {
            ctx.macPortMap.get(macOnVtep.IEEE802()) shouldBe vxPort1.getId
        }

        And("the VxGW manager doesn't report it to the bus")
        vtepConfigs(0).macRemoteUpdates.getOnNextEvents should have size 2

        When("a second VTEP is bound to the same neutron network (same VNI)")
        val vxPort2 = dataClient.bridgeCreateVxLanPort(ctx.nwId, vteps.ip2,
                                                       VTEP_PORT, vni1,
                                                       vteps.tunIp2, host.tzId)
        dataClient.vtepAddBinding(vteps.ip2, "eth0", 10, ctx.nwId)
        dataClient.vtepAddBinding(vteps.ip2, "eth1", 43, ctx.nwId)


        Then("the second VTEP gets a new controller")
        eventually {
            vtepConfigs should have size 2
        }

        And("the second VTEP sees the 3 MACs, plus a default one")
        val vtep2MacRemotes = vtepConfigs(1).macRemoteUpdates
        eventually {
            // see below to understand the extra entry
             vtep2MacRemotes.getOnNextEvents should have size 4
        }

        vtep2MacRemotes.getOnErrorEvents shouldBe empty
        vtep2MacRemotes.getOnCompletedEvents shouldBe empty
        vtep2MacRemotes.getOnNextEvents should contain theSameElementsAs Seq(
            MacLocation(fromMac(mac1), null, mgr.lsName, host.ip),
            MacLocation(fromMac(mac2), null, mgr.lsName, host.ip),
            ml,
            // The default one is the same as was emitted from the vtep just
            // before but this has an IP, so the VxGW Manager also injects
            // another without just to get anything addressed to that MAC sent
            // to the right VTEP
            MacLocation(ml.mac, null, mgr.lsName, ml.vxlanTunnelEndpoint)
        )

        When("the MAC moves from the first to the second VTEP")
        ml = MacLocation(macOnVtep, ipOnVtep, mgr.lsName, vteps.tunIp2)
        vtepConfigs(1).macLocalUpdates.onNext(ml)

        Then("the MAC is seen by both the first VTEP and MidoNet")
        eventually {
            vtep1MacRemotes.getOnNextEvents should have size 3
        }
        vtep1MacRemotes.getOnNextEvents should have size 3
        vtep1MacRemotes.getOnNextEvents.get(2) shouldBe ml

        And("the MAC wasn't seen by the second VTEP as it's its own")
        vtep2MacRemotes.getOnNextEvents should have size 4

        And("the Mac Port map in the Network sees the new value")
        eventually {
            ctx.macPortMap.get(ml.mac.IEEE802()) shouldBe vxPort2.getId
        }

        And("the IP should remain on the same MAC:")
        ctx.arpTable.get(ipOnVtep) shouldBe ml.mac.IEEE802()

        ctx.delete()
        host.delete()
    }

}
