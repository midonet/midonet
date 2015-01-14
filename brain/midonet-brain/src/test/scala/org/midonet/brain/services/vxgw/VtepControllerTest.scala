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

import java.util.{Random, UUID}
import java.util.concurrent.TimeUnit.SECONDS

import com.google.inject.{Injector, Guice}
import org.apache.commons.configuration.HierarchicalConfiguration
import org.junit.Assert
import org.junit.Assert.assertNotNull
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FlatSpec, GivenWhenThen, Matchers}

import org.midonet.brain.BrainTestUtils._
import org.midonet.brain.southbound.vtep.{VtepConstants, VtepMAC}
import org.midonet.brain.util.TestZkTools
import org.midonet.cluster.DataClient
import org.midonet.cluster.util.ObservableTestUtils._
import org.midonet.midolman.state.Directory
import org.midonet.packets.{IPv4Addr, MAC}

@RunWith(classOf[JUnitRunner])
class VtepControllerTest extends FlatSpec with Matchers
                                    with BeforeAndAfter
                                    with GivenWhenThen
                                    with VxlanGatewayTest {

    override var dataClient: DataClient = _

    var injector: Injector = _

    val nwId = UUID.randomUUID()
    val lsName = VtepConstants.bridgeIdToLogicalSwitchName(nwId)

    var someIp = IPv4Addr("22.0.0.0")

    val zkConnWatcher = TestZkTools.instantZkConnWatcher

    var tzState: TunnelZoneStatePublisher = _
    var hostState: HostStatePublisher = _

    var vteps: TwoVtepsOn = _
    var ctx: HostOnVtepTunnelZone = _

    def randomIp = { someIp = someIp.next ; someIp }
    def randomMacLocation() = MacLocation(VtepMAC.fromMac(MAC.random()),
                                          randomIp, lsName, randomIp)
    def randomMacLocation(tunIp: IPv4Addr) =
        MacLocation(VtepMAC.fromMac(MAC.random()), randomIp, lsName, tunIp)

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

        ctx = new HostOnVtepTunnelZone(10)
        vteps = new TwoVtepsOn(ctx.tzId)
    }

    after {
        tzState.dispose()
        hostState.dispose()
        vteps.delete()
        ctx.delete()
    }

    "A VTEP peer" should "be able to participate in a logical switch" in {

        // These are some entries currently in the OVSDB Mac_Local tables
        val initialVtepSnapshot =  1 to 5 map { _ => randomMacLocation() }
        // These are entries currently on other participants, will be preseeded
        val preseed = 1 to 5 map { _ => randomMacLocation() }

        val vtepOvsdb = new MockVtepConfig(vteps.ip1, vteps.vtepPort,
                                           vteps.tunIp1, initialVtepSnapshot)

        // A tap in the Logical Switch bus
        val tapOnBus = observer[MacLocation](0, 0, 0)

        val ls = new VxlanGateway(nwId)
        ls.vni = 111
        ls.asObservable.subscribe(tapOnBus)

        // The VTEP PEER under test
        Given("a VTEP peer")
        val peer = new VtepController(vtepOvsdb, dataClient, zkConnWatcher,
                                      tzState)

        When("the VTEP is told to join the Logical Switch with a seed")
        vtepOvsdb.updatesToVtep.reset(5, 0, 0)
        tapOnBus.reset(5, 0, 0)

        peer.join(ls, preseed)

        Then("the VTEP memberships includes the Logical Switch")
        peer.memberships should contain only ls

        And("the VTEP's listener receives the seed")
        assert(vtepOvsdb.updatesToVtep.n.await(1, SECONDS))
        vtepOvsdb.updatesToVtep.getOnNextEvents should have size 5
        vtepOvsdb.updatesToVtep
                 .getOnNextEvents should contain theSameElementsInOrderAs preseed

        And("it has emitted its own snapshot")
        assert(tapOnBus.n.await(1, SECONDS))
        tapOnBus.getOnNextEvents should have size 5
        tapOnBus.getOnNextEvents should contain theSameElementsInOrderAs initialVtepSnapshot

        // ----- Updates flow from VTEP -> Peers

        When("new MacLocation updates come from the VTEP")
        val fromVtep = 1 to 5 map { _ => randomMacLocation(vteps.tunIp1) }
        tapOnBus.reset(fromVtep.size, 0, 0)
        fromVtep foreach vtepOvsdb.updatesFromVtep.onNext

        Then("a peer subscribed to the bus should have seen them")
        assert(tapOnBus.n.await(1, SECONDS))

        tapOnBus.getOnNextEvents should have size 10
        tapOnBus.getOnNextEvents
                .subList(5, 10) should contain theSameElementsInOrderAs fromVtep

        And("the VTEP filtered out those updates since they came from itself")
        vtepOvsdb.updatesToVtep.getOnNextEvents should have size 5

        // ------ Updates flow from peers -> VTEP

        When("new MacLocation updates come from other peers")
        val fromOthers = 1 to 5 map { _ => randomMacLocation() }
        vtepOvsdb.updatesToVtep.reset(fromOthers.size, 0, 0)
        fromOthers foreach ls.asObserver.onNext // emit on the log. switch bus

        Then("the peer should send them to the VTEP")
        assert(vtepOvsdb.updatesToVtep.n.await(1, SECONDS))
        vtepOvsdb.updatesToVtep.getOnNextEvents should have size 10

        And("the VTEP has not reported the updates back to the bus")
        // +5 for the updates into the bus, but they won't come back
        tapOnBus.getOnNextEvents should have size 15

        // ------ Abandon the Logical Switch

        When("the peer abandons the logical switch")
        peer.abandon(ls)

        val missed = 1 to 5 map { _ => randomMacLocation(vteps.tunIp1) }
        missed foreach ls.asObserver.onNext

        vtepOvsdb.updatesToVtep.getOnNextEvents should have size 10
        tapOnBus.getOnNextEvents should have size 20 // +5 when we emitted them

    }

}
