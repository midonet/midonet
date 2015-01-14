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

import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.TimeoutException
import java.util.{Random, UUID}

import scala.concurrent.duration._

import com.google.inject.{Guice, Injector}
import org.junit.Assert.assertNotNull
import org.junit.runner.RunWith
import org.scalatest.concurrent.Eventually._
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FlatSpec, GivenWhenThen, Matchers}
import rx.observers.TestObserver

import org.midonet.cluster.ClusterTestUtils._
import org.midonet.cluster.DataClient
import org.midonet.cluster.data.vtep.model.{LogicalSwitch, MacLocation, VtepMAC}
import org.midonet.cluster.services.vxgw.TunnelZoneState.{FloodingProxyEvent, FloodingProxyOp}
import org.midonet.cluster.util.ObservableTestUtils._
import org.midonet.cluster.util.TestZkTools
import org.midonet.midolman.host.state.HostZkManager
import org.midonet.midolman.state.Directory
import org.midonet.packets.{IPv4Addr, MAC}
import org.midonet.util.reactivex.AwaitableObserver

@RunWith(classOf[JUnitRunner])
class VtepControllerTest extends FlatSpec with Matchers
                                          with BeforeAndAfter
                                          with GivenWhenThen
                                          with VxlanGatewayTest {

    override var dataClient: DataClient = _
    override var hostManager: HostZkManager = _

    val timeout = 5 seconds

    var injector: Injector = _

    val nwId = UUID.randomUUID()
    val lsName = LogicalSwitch.networkIdToLsName(nwId)

    var someIp = IPv4Addr("22.0.0.0")

    val zkConnWatcher = TestZkTools.instantZkConnWatcher

    var tzState: TunnelZoneStatePublisher = _
    var hostState: HostStatePublisher = _

    var vteps: TwoVtepsOn = _
    var hosts: HostsOnVtepTunnelZone = _

    def randomIp = { someIp = someIp.next ; someIp }
    def randomMacLocation() = MacLocation(VtepMAC.fromMac(MAC.random()),
                                          randomIp, lsName, randomIp)
    def randomMacLocation(tunIp: IPv4Addr) =
        MacLocation(VtepMAC.fromMac(MAC.random()), randomIp, lsName, tunIp)

    before {
        injector = Guice.createInjector(modules())
        val directory = injector.getInstance(classOf[Directory])
        setupZkTestDirectory(directory)

        dataClient = injector.getInstance(classOf[DataClient])
        assertNotNull(dataClient)

        hostManager = injector.getInstance(classOf[HostZkManager])
        assertNotNull(hostManager)

        hostState = new HostStatePublisher(dataClient, zkConnWatcher)
        tzState = new TunnelZoneStatePublisher(dataClient, zkConnWatcher,
                                               hostState, new Random)

        hosts = new HostsOnVtepTunnelZone()
        vteps = new TwoVtepsOn(hosts.tzId)
    }

    after {
        tzState.dispose()
        hostState.dispose()
        vteps.delete()
        hosts.delete()
    }

    "A VTEP peer" should "be able to participate in a logical switch" in {

        // These are some entries currently in the VTEP's Mac_Local table
        val initialVtepSnapshot =  1 to 5 map {
            _ => randomMacLocation(vteps.tunIp1)
        }
        // These are entries currently on other participants, will be preseeded
        val preseed = 1 to 5 map { _ => randomMacLocation() }
        val vtepOvsdb = new MockVtepConfig(vteps.ip1, vteps.vtepPort,
                                           vteps.tunIp1, initialVtepSnapshot)

        // A tap in the Logical Switch bus
        val tapOnBus = observer[MacLocation](0, 0, 0)

        val ls = new VxlanGateway(nwId)
        ls.vni = 111
        ls.observable.subscribe(tapOnBus)

        // The VTEP PEER under test
        Given("a VTEP peer")
        val peer = new VtepController(vtepOvsdb, dataClient, zkConnWatcher,
                                      tzState)

        When("the VTEP is told to join the Logical Switch with a seed")

        peer.join(ls, preseed)

        Then("the VTEP memberships includes the Logical Switch")
        peer.memberships should contain only ls

        And("the VTEP's listener receives the seed plus the UNKNOWN-DST entry")
        eventually {
            vtepOvsdb.updatesToVtep.getOnNextEvents should have size 6
        }
        //
        vtepOvsdb.updatesToVtep.getOnNextEvents should contain theSameElementsInOrderAs
            preseed :+ MacLocation(VtepMAC.UNKNOWN_DST, null, lsName, hosts.ip)

        And("it has emitted its own snapshot")
        eventually {
            tapOnBus.getOnNextEvents should have size 5
        }
        tapOnBus.getOnNextEvents should contain theSameElementsInOrderAs
            initialVtepSnapshot

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
        vtepOvsdb.updatesToVtep.getOnNextEvents should have size 6

        // ------ Updates flow from peers -> VTEP

        When("new MacLocation updates come from other peers")
        val fromOthers = 1 to 5 map { _ => randomMacLocation() }
        vtepOvsdb.updatesToVtep.reset(fromOthers.size, 0, 0)
        fromOthers foreach ls.observer.onNext // emit on the log. switch bus

        Then("the peer should send them to the VTEP")
        assert(vtepOvsdb.updatesToVtep.n.await(1, SECONDS))
        vtepOvsdb.updatesToVtep.getOnNextEvents should have size 11

        And("the VTEP has not reported the updates back to the bus")
        // +5 for the updates into the bus, but they won't come back
        tapOnBus.getOnNextEvents should have size 15

        // ------ Abandon the Logical Switch

        When("the peer abandons the logical switch")
        peer.abandon(ls)

        val missed = 1 to 5 map { _ => randomMacLocation(vteps.tunIp1) }
        missed foreach ls.observer.onNext

        vtepOvsdb.updatesToVtep.getOnNextEvents should have size 11
        tapOnBus.getOnNextEvents should have size 20 // +5 when we emitted them

    }

    "A VTEP controller" should "manage flooding proxies properly" in {
        val vtepOvsdb = new MockVtepConfig(vteps.ip1, vteps.vtepPort,
                             vteps.tunIp1, List.empty)
        val tzStateObserver = new TestObserver[FloodingProxyEvent] with
                                  AwaitableObserver[FloodingProxyEvent]

        tzState.get(hosts.tzId)
               .getFloodingProxyObservable
               .subscribe(tzStateObserver)

        val host1 = hosts.host.getId
        tzStateObserver.awaitOnNext(1, timeout) shouldBe true
        tzStateObserver.getOnNextEvents.get(0).hostConfig.id shouldBe host1
        tzStateObserver.getOnNextEvents.get(0).operation shouldBe FloodingProxyOp.SET
        tzState.get(hosts.tzId).getFloodingProxy.id shouldBe host1

        // A tap in the Logical Switch bus
        val tapOnBus = observer[MacLocation](0, 0, 0)
        val ls = new VxlanGateway(nwId)
        ls.vni = 111
        ls.observable.subscribe(tapOnBus)

        // The VTEP PEER under test
        Given("a VTEP peer")
        val peer = new VtepController(vtepOvsdb, dataClient, zkConnWatcher,
                                      tzState)
        When("the VTEP is told to join the Logical Switch")
        peer.join(ls, List.empty)

        Then("The flooding proxy is detected and written to the VTEP")
        eventually {
            vtepOvsdb.macRemoteUpdater.getOnNextEvents.get(0)
                                      .vxlanTunnelEndpoint shouldBe hosts.ip
        }

        When("Another host comes in, the flooding proxy is re-evaluated")
        val host2 = hosts.addHost() // will have default flooding proxy weight

        Then("The event might be emitted and change the flooding proxy")
        var next = 1
        val (primary, failback) = try {
            tzStateObserver.awaitOnNext(next + 1, timeout)
            tzStateObserver.getOnNextEvents
                           .get(next).operation shouldBe FloodingProxyOp.SET
            tzStateObserver.getOnNextEvents
                           .get(next).hostConfig.id shouldBe host2
            vtepOvsdb.macRemoteUpdater.getOnNextEvents.get(next)
                                      .vxlanTunnelEndpoint shouldBe host2
            next = next + 1
            (host2, host1)
        } catch { case t: TimeoutException =>
            // ok, this is not bad, it just means that the flooding proxy did
            // not change, so there are no events emitted
            (host1, host2)
        }

        When("Veto the current flooding proxy (set weight to -1)")
        dataClient.hostsSetFloodingProxyWeight(primary, -1)

        Then("The event is emitted, and the VTEP reassigns the flooding proxy")
        tzStateObserver.awaitOnNext(next + 1, timeout) shouldBe true
        tzStateObserver.getOnNextEvents.get(next).operation shouldBe FloodingProxyOp.SET
        tzStateObserver.getOnNextEvents.get(next).hostConfig.id shouldBe failback
        vtepOvsdb.macRemoteUpdater
                 .getOnNextEvents.get(next)
                 .vxlanTunnelEndpoint shouldBe hosts.all(failback)._2

        next = next + 1

        When("The veto is removed from the former flooding proxy")
        dataClient.hostsSetFloodingProxyWeight(primary, 10000)

        Then("The flooding proxy may change")
        val (primary2, failback2) = try {
            tzStateObserver.awaitOnNext(next + 1, timeout)
            tzStateObserver.getOnNextEvents.get(next).operation shouldBe FloodingProxyOp.SET
            tzStateObserver.getOnNextEvents.get(next).hostConfig.id shouldBe primary
            vtepOvsdb.macRemoteUpdater.getOnNextEvents.get(next)
                     .vxlanTunnelEndpoint shouldBe hosts.all(primary)._2
            next = next + 1
            (primary, failback)
        } catch { case t: TimeoutException =>
            // ok, since election is not fully deterministic this might happen
            (failback, primary)
        }

        When("The current flooding proxy goes down")
        hostManager.makeNotAlive(primary2)

        Then("The flooding proxy is updated to the failback host")
        tzStateObserver.awaitOnNext(next + 1, timeout)
        tzStateObserver.getOnNextEvents.get(next).operation shouldBe FloodingProxyOp.SET
        tzStateObserver.getOnNextEvents.get(next).hostConfig.id shouldBe failback2
        vtepOvsdb.macRemoteUpdater.getOnNextEvents.get(next)
            .vxlanTunnelEndpoint shouldBe hosts.all(failback2)._2

        next = next + 1

        When("The other host goes down")
        hostManager.makeNotAlive(failback2)
        tzStateObserver.awaitOnNext(next + 1, timeout)
        tzStateObserver.getOnNextEvents.get(next).operation shouldBe FloodingProxyOp.CLEAR

    }

}
