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

package org.midonet.cluster.services.vxgw

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit._
import java.util.{Random, UUID}

import scala.collection.mutable.ListBuffer

import com.google.inject.{Guice, Injector}
import org.junit.Assert._
import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.concurrent.Eventually._
import org.scalatest.junit.JUnitRunner
import org.slf4j.LoggerFactory

import org.midonet.cluster.ClusterTestUtils._
import org.midonet.cluster.southbound.vtep.VtepConstants.bridgeIdToLogicalSwitchName
import org.midonet.cluster.util.TestZkTools
import org.midonet.cluster.DataClient
import org.midonet.cluster.data.Bridge.UNTAGGED_VLAN_ID
import org.midonet.cluster.data.vtep.model.{VtepMAC, MacLocation}
import org.midonet.cluster.data.vtep.model.VtepMAC.{UNKNOWN_DST, fromMac}
import org.midonet.midolman.host.state.HostZkManager
import org.midonet.midolman.state._
import org.midonet.packets.{IPv4Addr, MAC}
import org.midonet.util.MidonetEventually

@RunWith(classOf[JUnitRunner])
class VxlanGatewayManagerTest extends FlatSpec with Matchers
                                               with BeforeAndAfter
                                               with GivenWhenThen
                                               with VxlanGatewayTest
                                               with MidonetEventually {

    val log = LoggerFactory.getLogger(classOf[VxlanGatewayManagerTest])

    var injector: Injector = _

    override var hostManager: HostZkManager = _
    override var dataClient: DataClient = _

    val VTEP_PORT = 6632

    // Replace with the fixed ones for debug-friendly MACs
    val mac1 = MAC.random() // fromString("11:11:11:11:11:11")
    val mac2 = MAC.random() // fromString("22:22:22:22:22:22")
    val mac3 = MAC.random() // fromString("33:33:33:33:33:33")

    var vni1 = 1111
    var vni2 = 2222

    val vtepConfigs = ListBuffer[MockVtepConfig]()

    var vtepPool: VtepPool = _

    val zkConnWatcher = TestZkTools.instantZkConnWatcher

    var mgrClosedLatch: CountDownLatch = _

    var tzState: TunnelZoneStatePublisher = _
    var hostState: HostStatePublisher = _

    var nodeId: UUID = _

    before {
        injector = Guice.createInjector(modules())
        val directory = injector.getInstance(classOf[Directory])
        setupZkTestDirectory(directory)

        dataClient = injector.getInstance(classOf[DataClient])
        assertNotNull(dataClient)

        hostState = new HostStatePublisher(dataClient, zkConnWatcher)
        tzState = new TunnelZoneStatePublisher(dataClient, zkConnWatcher,
                                               hostState, new Random)

        hostManager = injector.getInstance(classOf[HostZkManager])
        assertNotNull(hostManager)

        mgrClosedLatch = new CountDownLatch(1)
        nodeId = UUID.randomUUID


        // WATCH OUT: this factory assumes that VxlanGatewayTest.TwoVtepsOn
        // generates the tunnel ip as the next to management ip.
        vtepPool = new VtepPool(nodeId, dataClient, zkConnWatcher, tzState,
                                null) {
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
                                          tzState, zkConnWatcher,
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
                                                       vteps.tunIp1, host.tzId)
        dataClient.vtepAddBinding(vteps.ip1, "eth0", 10, ctx.nwId)
        dataClient.vtepAddBinding(vteps.ip1, "eth0", 66, ctx.nwId)
        dataClient.vtepAddBinding(vteps.ip1, "eth1", 66, ctx.nwId)

        And("a vxlan gateway manager starts")
        val mgr = new VxlanGatewayManager(ctx.nwId, dataClient, vtepPool,
                                          tzState, zkConnWatcher,
                                          () => { mgrClosedLatch.countDown() })
        mgr.start()

        Then("the VTEP joins the Vxlan Gateway")
        eventually {    // the ovsdb link to the first VTEP is stablished
            vtepConfigs should have size 1
        }

        val vtep1 = eventually {
            vtepPool.fishIfExists(vteps.ip1, vteps.vtepPort).get
        }
        val vtep1MacRemotes = vtepConfigs.head.macRemoteUpdater
        vtep1.memberships should have size 1
        vtep1.memberships.head.name shouldBe mgr.lsName

        eventually {    // the initial state reaches the VTEP
            vtep1MacRemotes.getOnErrorEvents shouldBe empty
            vtep1MacRemotes.getOnCompletedEvents shouldBe empty
            vtep1MacRemotes.getOnNextEvents should contain only (
                MacLocation(fromMac(mac1), null, mgr.lsName, host.ip),
                MacLocation(fromMac(mac2), null, mgr.lsName, host.ip),
                MacLocation(UNKNOWN_DST, null, mgr.lsName, host.ip)
                // the last one might be duplicated because we emit the initial
                // flooding proxy twice: first because it's the first
                // subscription to the tz, another on the VTEP preseed, both
                // race, so we just emit both.
            )
        }

        // We expect 4, but they are duplicate. This is because the
        // mgr initialization races with the first vtep load. We can't rely
        // on the mac-port watchers to be triggered in time to get to the VTEP
        // so we just emit a snapshot inside ensureInitialized() to be sure

        When("a new mac-port entry is added")
        ctx.macPortMap.put(mac3, ctx.port1.getId)

        Then("the update should be received")
        eventually {
            vtep1MacRemotes.getOnNextEvents should contain only (
                MacLocation(fromMac(mac1), null, mgr.lsName, host.ip),
                MacLocation(fromMac(mac2), null, mgr.lsName, host.ip),
                MacLocation(UNKNOWN_DST, null, mgr.lsName, host.ip), // x2
                MacLocation(fromMac(mac3), null, mgr.lsName, host.ip)
            )
        }

        When("a second VTEP is bound to the same neutron network (same VNI)")
        dataClient.bridgeCreateVxLanPort(ctx.nwId, vteps.ip2, VTEP_PORT, vni1,
                                         vteps.tunIp2, host.tzId)
        dataClient.vtepAddBinding(vteps.ip2, "eth0", 10, ctx.nwId)
        dataClient.vtepAddBinding(vteps.ip2, "eth1", 43, ctx.nwId)

        eventually {
            vtepConfigs should have size 2
        }

        val vtep2 = eventually {
            vtepPool.fishIfExists(vteps.ip2, vteps.vtepPort).get
        }
        val vtep2MacRemotes = vtepConfigs(1).macRemoteUpdater

        Then("a new VTEP joins the Vxlan Gateway")
        eventually {
            vtep2MacRemotes.getOnNextEvents should have size 6
        }
        vtep2.memberships should have size 1
        vtep2.memberships.head.name shouldBe mgr.lsName
        vtep2MacRemotes.getOnErrorEvents shouldBe empty
        vtep2MacRemotes.getOnCompletedEvents shouldBe empty
        vtep2MacRemotes.getOnNextEvents should contain only (
            MacLocation(fromMac(mac1), null, mgr.lsName, host.ip),
            MacLocation(fromMac(mac2), null, mgr.lsName, host.ip),
            MacLocation(fromMac(mac3), null, mgr.lsName, host.ip),
            MacLocation(UNKNOWN_DST, null, mgr.lsName, host.ip), // x2
            MacLocation(UNKNOWN_DST, null, mgr.lsName, vteps.tunIp1)
        )

        And("the first VTEP just saw an extra update with vtep2's unknown-dst")
        vtep1MacRemotes.getOnNextEvents should have size 6
        vtep1MacRemotes.getOnNextEvents should contain only (
            MacLocation(fromMac(mac1), null, mgr.lsName, host.ip),
            MacLocation(fromMac(mac2), null, mgr.lsName, host.ip),
            MacLocation(UNKNOWN_DST, null, mgr.lsName, host.ip), // x2
            MacLocation(fromMac(mac3), null, mgr.lsName, host.ip),
            MacLocation(UNKNOWN_DST, null, mgr.lsName, vteps.tunIp2)
        )

        When("a new IP appears on a MidoNet port")
        val newIp = IPv4Addr.random
        ctx.arpTable.put(newIp, mac2)

        Then("both VTEPs should see the update")
        eventually {
            val vtepMac2 = VtepMAC.fromMac(mac2)
            val newMl = MacLocation(vtepMac2, newIp, mgr.lsName, host.ip)
            vtep1MacRemotes.getOnNextEvents.get(6) shouldBe newMl
            vtep2MacRemotes.getOnNextEvents.get(6) shouldBe newMl
        }

        When("the VxLAN port corresponding to the first VTEP is deleted")
        dataClient.bridgeDeleteVxLanPort(vxPort1)

        eventually {
            vtep1.memberships shouldBe empty
        }

        And("the first mac migrates to the second port")
        dataClient.bridgeDeleteMacPort(ctx.nwId, UNTAGGED_VLAN_ID, mac1,
                                       ctx.port1.getId)
        dataClient.bridgeAddMacPort(ctx.nwId, UNTAGGED_VLAN_ID, mac1,
                                    ctx.port2.getId)

        Then("the first VTEP won't receive any more updates")
        eventually {
            // we see an update and deletion
            vtep1MacRemotes.getOnNextEvents should have size 7
            vtep1MacRemotes.getOnErrorEvents shouldBe empty
            vtep1MacRemotes.getOnCompletedEvents shouldBe empty
        }


        And("the second VTEP will receive the new one")
        vtep2MacRemotes.getOnErrorEvents shouldBe empty
        vtep2MacRemotes.getOnCompletedEvents shouldBe empty
        vtep2MacRemotes.getOnNextEvents should have size 9
        vtep2MacRemotes.getOnNextEvents.get(8) shouldBe
            MacLocation(fromMac(mac1), null, mgr.lsName, host.ip)

        // Yes, this last MacLocation was redundant, but for now we have no way
        // of knowing that the last time we emitted was with the same hostIp

        When("the VxLAN port corresponding to the second VTEP is deleted")
        dataClient.bridgeDeleteVxLanPort(ctx.nwId, vteps.ip2)

        Then("the VxLAN Gateway manager should close")
        assert(mgrClosedLatch.await(1, SECONDS))

        And("the second VTEP abandoned the VxLAN Gateway")
        vtep2.memberships shouldBe empty

        And("the second VTEP didn't see any further updates")
        assertTrue(vtep2MacRemotes.getOnNextEvents.size == 9)
        vtep2MacRemotes.getOnCompletedEvents shouldBe empty
        vtep2MacRemotes.getOnErrorEvents shouldBe empty

        And("the first subscriber didn't see anything at all")
        vtep1MacRemotes.getOnNextEvents should have size 7
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
                                                       vteps.tunIp1, host.tzId)
        dataClient.vtepAddBinding(vteps.ip1, "eth0", 10, ctx.nwId)
        dataClient.vtepAddBinding(vteps.ip1, "eth0", 66, ctx.nwId)
        dataClient.vtepAddBinding(vteps.ip1, "eth1", 66, ctx.nwId)

        And("a vxlan gateway manager starts")
        val mgr = new VxlanGatewayManager(ctx.nwId, dataClient, vtepPool,
                                          tzState, zkConnWatcher,
                                          () => { mgrClosedLatch.countDown() })
        mgr.start()

        Then("the VTEP joins the Vxlan Gateway")
        eventually {    // the ovsdb link to the first VTEP is stablished
            vtepConfigs should have size 1
        }

        val vtep1 = eventually {
            vtepPool.fishIfExists(vteps.ip1, vteps.vtepPort).get
        }
        val vtep1MacRemotes = vtepConfigs.head.macRemoteUpdater
        vtep1.memberships should have size 1
        vtep1.memberships.head.name shouldBe mgr.lsName

        eventually {    // the initial state reaches the VTEP
           vtep1MacRemotes.getOnNextEvents should have size 4
        }
        vtep1MacRemotes.getOnErrorEvents shouldBe empty
        vtep1MacRemotes.getOnCompletedEvents shouldBe empty
        vtep1MacRemotes.getOnNextEvents should contain only (
            MacLocation(fromMac(mac1), null, mgr.lsName, host.ip),
            MacLocation(fromMac(mac2), null, mgr.lsName, host.ip),
            MacLocation(UNKNOWN_DST, null, mgr.lsName, host.ip)
            // the last one is duplicated because we emit the initial flooding
            // proxy twice: first because it's the first subscription to the tz,
            // another on the VTEP preseed, both race, so we just emit both.
        )

        When("the VTEP reports a new MAC")
        val macOnVtep = VtepMAC.fromString("aa:aa:bb:bb:cc:cc")
        val ipOnVtep = IPv4Addr.random
        var ml = MacLocation(macOnVtep, ipOnVtep, mgr.lsName, vteps.tunIp1)
        vtepConfigs.head.macLocalUpdates.onNext(ml)

        Then("the MAC sent from the hardware VTEP should reach MidoNet")
        eventually {
            ctx.macPortMap.get(macOnVtep.IEEE802) shouldBe vxPort1.getId
        }

        And("the VxGW manager doesn't report it to the bus")
        vtep1MacRemotes.getOnNextEvents should have size 4

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

        And("the second VTEP gets primed as expected")
        val vtep2MacRemotes = vtepConfigs(1).macRemoteUpdater
        eventually { // see below to understand the extra entries
             assert(vtep2MacRemotes.getOnNextEvents.size() >= 6)
             // Could be 6 or 7, depends on a race betwee the flooding proxy
             // watcher and the joining. We do check the right contents below.
        }

        vtep2MacRemotes.getOnErrorEvents shouldBe empty
        vtep2MacRemotes.getOnCompletedEvents shouldBe empty
        vtep2MacRemotes.getOnNextEvents should contain only (
            MacLocation(fromMac(mac1), null, mgr.lsName, host.ip),
            MacLocation(fromMac(mac2), null, mgr.lsName, host.ip),
            MacLocation(UNKNOWN_DST, null, mgr.lsName, host.ip),
            // the one coming from the other VTEP
            ml,
            // The default one is the same as was emitted from the vtep just
            // before but this has an IP, so the VxGW Manager also injects
            // another without just to get anything addressed to that MAC sent
            // to the right VTEP
            MacLocation(ml.mac, null, mgr.lsName, ml.vxlanTunnelEndpoint),
            // Also, we expect that the VTEP gets an unknown destination entry
            // that points at the other VTEP
            MacLocation(UNKNOWN_DST, null, mgr.lsName, vteps.tunIp1)
        )
        val vtep2RemotesSnapshot = new java.util.ArrayList[MacLocation]()
        vtep2RemotesSnapshot.addAll(vtep2MacRemotes.getOnNextEvents)

        When("the MAC moves from the first to the second VTEP")
        ml = MacLocation(macOnVtep, ipOnVtep, mgr.lsName, vteps.tunIp2)
        vtepConfigs(1).macLocalUpdates.onNext(ml)

        Then("the MAC is seen by both the first VTEP and MidoNet")
        eventually {
            vtep1MacRemotes.getOnNextEvents should have size 6
        }
        vtep1MacRemotes.getOnNextEvents should contain only (
            MacLocation(fromMac(mac1), null, mgr.lsName, host.ip),
            MacLocation(fromMac(mac2), null, mgr.lsName, host.ip),
            MacLocation(UNKNOWN_DST, null, mgr.lsName, host.ip), // x2
            // it saw the unknown-dst for the other VTEP
            MacLocation(UNKNOWN_DST, null, mgr.lsName, vteps.tunIp2),
            // and the movement of macOnVtep to the other VTEP
            ml
        )

        And("the second VTEP remains as it was")
        vtep2MacRemotes.getOnErrorEvents shouldBe empty
        vtep2MacRemotes.getOnCompletedEvents shouldBe empty
        vtep2MacRemotes.getOnNextEvents shouldBe vtep2RemotesSnapshot

        And("the Mac Port map in the Network sees the new value")
        eventually {
            ctx.macPortMap.get(ml.mac.IEEE802) shouldBe vxPort2.getId
        }

        And("the IP remains on the same MAC")
        ctx.arpTable.get(ipOnVtep) shouldBe ml.mac.IEEE802

        ctx.delete()
        host.delete()
    }
}
