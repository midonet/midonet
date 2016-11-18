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

import scala.concurrent.duration._

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import rx.Observable
import rx.observers.TestObserver

import org.midonet.cluster.data.storage.{CreateOp, NotFoundException, Storage}
import org.midonet.cluster.models.Topology.{BgpNetwork, BgpPeer, Router}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.topology.{TopologyBuilder, TopologyMatchers}
import org.midonet.cluster.util.IPAddressUtil._
import org.midonet.cluster.util.IPSubnetUtil
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.topology.DeviceMapper.MapperState
import org.midonet.midolman.topology.devices.{BgpNeighbor, BgpRouter}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.packets.{IPSubnet, IPv4Addr}
import org.midonet.quagga.BgpdConfiguration.{Neighbor, Network}
import org.midonet.util.reactivex.AwaitableObserver

@RunWith(classOf[JUnitRunner])
class BgpRouterMapperTest extends MidolmanSpec with TopologyBuilder
                          with TopologyMatchers {

    import TopologyBuilder._

    private var store: Storage = _
    private var vt: VirtualTopology = _

    private final val timeout = 5 seconds

    protected override def beforeTest(): Unit = {
        vt = injector.getInstance(classOf[VirtualTopology])
        store = injector.getInstance(classOf[MidonetBackend]).store
    }

    private def createObserver() = {
        new TestObserver[BgpRouter] with AwaitableObserver[BgpRouter]
    }

    private def bgpRouter(router: Router,
                          peers: Set[BgpPeer] = Set.empty,
                          networks: Set[BgpNetwork] = Set.empty)
    : BgpRouter = {
        BgpRouter(
            as = router.getAsNumber,
            neighbors = peers.map(p => (
                p.getAddress.asIPv4Address,
                BgpNeighbor(
                    p.getId,
                    Neighbor(p.getAddress.asIPv4Address,
                             p.getAsNumber,
                             Some(vt.config.bgpKeepAlive),
                             Some(vt.config.bgpHoldTime),
                             Some(vt.config.bgpConnectRetry))))).toMap,
            networks = networks
                .map(n => Network(IPSubnetUtil.fromV4Proto(n.getSubnet)))
        )
    }

    implicit def asIPSubnet(str: String): IPSubnet[_] = IPSubnet.fromCidr(str)
    implicit def asIPAddress(str: String): IPv4Addr = IPv4Addr(str)

    feature("Test mapper lifecycle") {
        scenario("Mapper subscribes and unsubscribes") {
            Given("A router")
            val router = createRouter()
            store.create(router)

            And("Three BGP observers")
            val obs1 = createObserver()
            val obs2 = createObserver()
            val obs3 = createObserver()

            When("Creating a BGP router mapper for the router")
            val mapper = new BgpRouterMapper(router.getId, vt)

            And("An observable on the mapper")
            val observable = Observable.create(mapper)

            Then("The mapper should be unsubscribed")
            mapper.mapperState shouldBe MapperState.Unsubscribed
            mapper.hasObservers shouldBe false

            When("The first observer subscribes to the observable")
            val sub1 = observable subscribe obs1

            Then("The mapper should be subscribed")
            mapper.mapperState shouldBe MapperState.Subscribed
            mapper.hasObservers shouldBe true

            And("The observer should receive a notification")
            obs1.awaitOnNext(1, timeout) shouldBe true

            When("The second observer subscribes to the observable")
            val sub2 = observable subscribe obs2

            Then("The mapper should be subscribed")
            mapper.mapperState shouldBe MapperState.Subscribed
            mapper.hasObservers shouldBe true

            And("The observer should receive a notification")
            obs2.awaitOnNext(1, timeout) shouldBe true

            When("The first observable unsubscribes")
            sub1.unsubscribe()

            Then("The mapper should be subscribed")
            mapper.mapperState shouldBe MapperState.Subscribed
            mapper.hasObservers shouldBe true

            When("The second observable unsubscribes")
            sub2.unsubscribe()

            Then("The mapper should be closed")
            mapper.mapperState shouldBe MapperState.Closed
            mapper.hasObservers shouldBe false

            When("A third observer subscribes")
            observable subscribe obs3

            Then("The observer should receive an error")
            obs3.awaitCompletion(timeout)
            obs3.getOnErrorEvents.get(0) shouldBe DeviceMapper.MapperClosedException
        }

        scenario("Mapper re-subscribes") {
            Given("A router")
            val router = createRouter()
            store.create(router)

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP router mapper for the router")
            val mapper = new BgpRouterMapper(router.getId, vt)

            And("An observable on the mapper")
            val observable = Observable.create(mapper)

            Then("The mapper should be unsubscribed")
            mapper.mapperState shouldBe MapperState.Unsubscribed
            mapper.hasObservers shouldBe false

            When("The observer subscribes to the observable")
            val sub1 = observable subscribe obs

            Then("The mapper should be subscribed")
            mapper.mapperState shouldBe MapperState.Subscribed
            mapper.hasObservers shouldBe true

            And("The observer should receive a notification")
            obs.awaitOnNext(1, timeout) shouldBe true

            When("The observer unsubscribes")
            sub1.unsubscribe()

            Then("The mapper should be closed")
            mapper.mapperState shouldBe MapperState.Closed
            mapper.hasObservers shouldBe false

            When("The observer re-subscribes to the observable")
            observable subscribe obs

            Then("The observer should receive an error")
            obs.awaitCompletion(timeout)
            obs.getOnErrorEvents.get(0) shouldBe DeviceMapper.MapperClosedException
        }

        scenario("Mapper terminates with completed") {
            Given("A router")
            val router = createRouter()
            store.create(router)

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP router mapper for the router")
            val mapper = new BgpRouterMapper(router.getId, vt)

            And("An observable on the mapper")
            val observable = Observable.create(mapper)

            Then("The mapper should be unsubscribed")
            mapper.mapperState shouldBe MapperState.Unsubscribed
            mapper.hasObservers shouldBe false

            When("The observer subscribes to the observable")
            val sub = observable subscribe obs

            Then("The mapper should be subscribed")
            mapper.mapperState shouldBe MapperState.Subscribed
            mapper.hasObservers shouldBe true

            And("The observer should receive a notification")
            obs.awaitOnNext(1, timeout) shouldBe true

            When("The router is deleted")
            store.delete(classOf[Router], router.getId)

            Then("The observer should receive a completed notification")
            obs.awaitCompletion(timeout)
            obs.getOnCompletedEvents should not be empty
            sub.isUnsubscribed shouldBe true

            And("The mapper should be completed")
            mapper.mapperState shouldBe MapperState.Completed
            mapper.hasObservers shouldBe false
        }

        scenario("Mapper terminates with error") {
            Given("A non-existent router")
            val routerId = UUID.randomUUID

            And("Two BGP observers")
            val obs1 = createObserver()
            val obs2 = createObserver()

            When("Creating a BGP router mapper for the router")
            val mapper = new BgpRouterMapper(routerId, vt)

            And("An observable on the mapper")
            val observable = Observable.create(mapper)

            Then("The mapper should be unsubscribed")
            mapper.mapperState shouldBe MapperState.Unsubscribed
            mapper.hasObservers shouldBe false

            When("The observer subscribes to the observable")
            val sub1 = observable subscribe obs1

            Then("The observer should receive an error")
            obs1.awaitCompletion(timeout)
            obs1.getOnErrorEvents.get(0).getClass shouldBe classOf[NotFoundException]
            sub1.isUnsubscribed shouldBe true

            And("The mapper should be receive an error")
            mapper.mapperState shouldBe MapperState.Error
            mapper.hasObservers shouldBe false

            When("A second observer subscribes")
            val sub2 = observable subscribe obs2

            Then("The observer should receive an error")
            obs2.getOnErrorEvents.get(0).getClass shouldBe classOf[NotFoundException]
            sub2.isUnsubscribed shouldBe true
        }
    }

    feature("Test mapper router updates") {
        scenario("Router does not exist") {
            Given("A random router identifier")
            val routerId = UUID.randomUUID

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP router mapper for the router identifier")
            val mapper = new BgpRouterMapper(routerId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive an error")
            obs.awaitCompletion(timeout)
            obs.getOnErrorEvents.get(0).getClass shouldBe
                classOf[NotFoundException]
        }

        scenario("Router exists with no AS number") {
            Given("A router")
            val router = createRouter()
            store.create(router)

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP router mapper for the router")
            val mapper = new BgpRouterMapper(router.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the BGP router")
            obs.awaitOnNext(1, timeout)
            obs.getOnNextEvents.get(0) shouldBe bgpRouter(router)
        }

        scenario("Router exists with AS number") {
            Given("A router")
            val router = createRouter(asNumber = Some(1))
            store.create(router)

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP router mapper for the router")
            val mapper = new BgpRouterMapper(router.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the BGP router")
            obs.awaitOnNext(1, timeout)
            obs.getOnNextEvents.get(0) shouldBe bgpRouter(router)
        }

        scenario("Router updated and BGP information changed") {
            Given("A router")
            val router1 = createRouter(asNumber = Some(1))
            store.create(router1)

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP router mapper for the router")
            val mapper = new BgpRouterMapper(router1.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the BGP router")
            obs.awaitOnNext(1, timeout)

            When("The router is updated")
            val router2 = router1.setAsNumber(2)
            store.update(router2)

            Then("The observer should receive another BGP router")
            obs.awaitOnNext(2, timeout)
            obs.getOnNextEvents.get(1) shouldBe bgpRouter(router2)
        }

        scenario("Router updated and BGP information unchanged") {
            Given("A router")
            val router1 = createRouter(asNumber = Some(1))
            store.create(router1)

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP router mapper for the router")
            val mapper = new BgpRouterMapper(router1.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the BGP router")
            obs.awaitOnNext(1, timeout)

            When("The router is updated")
            val router2 = router1.setName("router")
            store.update(router2)

            And("The router updates the AS number to generate another update")
            val router3 = router2.setAsNumber(2)
            store.update(router3)

            Then("The observer should only two BGP routers")
            obs.awaitOnNext(2, timeout)

            And("The last router should have the changed AS number")
            obs.getOnNextEvents.get(1) shouldBe bgpRouter(router3)
        }
    }

    feature("Test peer updates") {
        scenario("For existing BGP peer") {
            Given("A router")
            val router = createRouter(asNumber = Some(1))
            store.create(router)

            And("A BGP peer")
            val bgpPeer = createBgpPeer(routerId = Some(router.getId),
                                        asNumber = Some(1),
                                        address = Some("10.0.0.1"))
            store.create(bgpPeer)

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP router mapper for the router")
            val mapper = new BgpRouterMapper(router.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the BGP router")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents.get(0) shouldBe bgpRouter(router, Set(bgpPeer))
        }

        scenario("For existing BGP peers") {
            Given("A router")
            val router = createRouter(asNumber = Some(1))
            store.create(router)

            And("A BGP peer")
            val bgpPeer1 = createBgpPeer(routerId = Some(router.getId),
                                         asNumber = Some(1),
                                         address = Some("10.0.0.1"))
            val bgpPeer2 = createBgpPeer(routerId = Some(router.getId),
                                         asNumber = Some(1),
                                         address = Some("10.0.0.2"))
            store.multi(Seq(CreateOp(bgpPeer1), CreateOp(bgpPeer2)))

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP router mapper for the router")
            val mapper = new BgpRouterMapper(router.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the BGP router")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents.get(0) shouldBe bgpRouter(router, Set(bgpPeer1,
                                                                      bgpPeer2))
        }

        scenario("When adding a BGP peer") {
            Given("A router")
            val router = createRouter(asNumber = Some(1))
            store.create(router)

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP router mapper for the router")
            val mapper = new BgpRouterMapper(router.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the BGP router update")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents.get(0) shouldBe bgpRouter(router)

            When("Creating a BGP peer for the router")
            val bgpPeer = createBgpPeer(routerId = Some(router.getId),
                                        asNumber = Some(1),
                                        address = Some("10.0.0.1"))
            store.create(bgpPeer)

            Then("The observer should receive the BGP router with peers")
            obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents.get(1) shouldBe bgpRouter(router, Set(bgpPeer))
        }

        scenario("When updating a BGP peer") {
            Given("A router")
            val router = createRouter(asNumber = Some(1))
            store.create(router)

            And("A BGP peer")
            val bgpPeer1 = createBgpPeer(routerId = Some(router.getId),
                                         asNumber = Some(1),
                                         address = Some("10.0.0.1"))
            store.create(bgpPeer1)

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP router mapper for the router")
            val mapper = new BgpRouterMapper(router.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the BGP router")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents.get(0) shouldBe bgpRouter(router, Set(bgpPeer1))

            When("Updating the BGP peer with a different AS")
            val bgpPeer2 = bgpPeer1.setAsNumber(10)
            store.update(bgpPeer2)

            Then("The observer should receive a BGP peer update")
            obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents.get(1) shouldBe bgpRouter(router, Set(bgpPeer2))

            When("Updating the BGP peer with a different address")
            val bgpPeer3 = bgpPeer2.setAddress("10.0.0.2")
            store.update(bgpPeer3)

            Then("The observer should receive a BGP peer update")
            obs.awaitOnNext(3, timeout) shouldBe true
            obs.getOnNextEvents.get(2) shouldBe bgpRouter(router, Set(bgpPeer3))
        }

        scenario("When removing a BGP peer") {
            Given("A router")
            val router = createRouter(asNumber = Some(1))
            store.create(router)

            And("A BGP peer")
            val bgpPeer = createBgpPeer(routerId = Some(router.getId),
                                        asNumber = Some(1),
                                        address = Some("10.0.0.1"))
            store.create(bgpPeer)

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP router mapper for the router")
            val mapper = new BgpRouterMapper(router.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the port and BGP peer")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents.get(0) shouldBe bgpRouter(router, Set(bgpPeer))

            When("Deleting the BGP peer")
            store.delete(classOf[BgpPeer], bgpPeer.getId)

            Then("The observer should receive a BGP removal update")
            obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents.get(1) shouldBe bgpRouter(router)
        }

        scenario("When adding/removing multiple BGP peers with different addresses") {
            Given("A router")
            val router = createRouter(asNumber = Some(1))
            store.create(router)

            And("A BGP peer")
            val bgpPeer1 = createBgpPeer(routerId = Some(router.getId),
                                         asNumber = Some(1),
                                         address = Some("10.0.0.1"))
            store.create(bgpPeer1)

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP router mapper for the router")
            val mapper = new BgpRouterMapper(router.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the port and BGP peer")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents.get(0) shouldBe bgpRouter(router, Set(bgpPeer1))

            When("Adding a second BGP peer")
            val bgpPeer2 = createBgpPeer(routerId = Some(router.getId),
                                         asNumber = Some(2),
                                         address = Some("10.0.0.2"))
            store.create(bgpPeer2)

            Then("The observer should receive the first and second peers")
            obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents.get(1) shouldBe bgpRouter(router,
                                                          Set(bgpPeer1, bgpPeer2))

            When("Removing the first BGP peer")
            store.delete(classOf[BgpPeer], bgpPeer1.getId)

            Then("The observer should receive only the second peer")
            obs.awaitOnNext(3, timeout) shouldBe true
            obs.getOnNextEvents.get(2) shouldBe bgpRouter(router, Set(bgpPeer2))
        }

        scenario("When adding/removing multiple BGP peers with same address") {
            Given("A router")
            val router = createRouter(asNumber = Some(1))
            store.create(router)

            And("A BGP peer")
            val bgpPeer1 = createBgpPeer(routerId = Some(router.getId),
                                         asNumber = Some(1),
                                         address = Some("10.0.0.1"))
            store.create(bgpPeer1)

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP router mapper for the router")
            val mapper = new BgpRouterMapper(router.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the BGP router")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents.get(0) shouldBe bgpRouter(router, Set(bgpPeer1))

            When("Adding a second BGP peer with the same address")
            val bgpPeer2 = createBgpPeer(routerId = Some(router.getId),
                                         asNumber = Some(2),
                                         address = Some("10.0.0.1"))
            store.create(bgpPeer2)

            Then("The observer should receive only the first peer")
            obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents.get(1) shouldBe bgpRouter(router, Set(bgpPeer1))

            When("Removing the first BGP peer")
            store.delete(classOf[BgpPeer], bgpPeer1.getId)

            Then("The observer should receive only the second peer")
            obs.awaitOnNext(3, timeout) shouldBe true
            obs.getOnNextEvents.get(2) shouldBe bgpRouter(router, Set(bgpPeer2))
        }

        scenario("Added BGP peer with missing AS number ignored") {
            Given("A router")
            val router1 = createRouter(asNumber = Some(1))
            store.create(router1)

            And("A BGP peer without AS number")
            val bgpPeer1 = createBgpPeer(routerId = Some(router1.getId),
                                         address = Some("10.0.0.1"))
            store.create(bgpPeer1)

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP router mapper for the router")
            val mapper = new BgpRouterMapper(router1.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the BGP router without the peer")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents.get(0) shouldBe bgpRouter(router1)

            When("Updating the BGP peer with an AS")
            val bgpPeer2 = bgpPeer1.setAsNumber(1)
            store.update(bgpPeer2)

            And("Updating the router to generate another update")
            val router2 = router1.setAsNumber(2)
            store.update(router2)

            Then("The observer should receive only the router update")
            obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents.get(1) shouldBe bgpRouter(router2)
        }

        scenario("Updated BGP peer with missing AS number ignored") {
            Given("A router")
            val router1 = createRouter(asNumber = Some(1))
            store.create(router1)

            And("A BGP peer")
            val bgpPeer1 = createBgpPeer(routerId = Some(router1.getId),
                                         asNumber = Some(1),
                                         address = Some("10.0.0.1"))
            store.create(bgpPeer1)

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP router mapper for the router")
            val mapper = new BgpRouterMapper(router1.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the BGP router")
            obs.awaitOnNext(1, timeout) shouldBe true

            When("Updating the BGP peer without an AS")
            val bgpPeer2 = bgpPeer1.clearAsNumber()
            store.update(bgpPeer2)

            Then("The observer should receive a BGP router without a peer")
            obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents.get(1) shouldBe bgpRouter(router1)

            When("Updating the BGP peer with an AS")
            val bgpPeer3 = bgpPeer2.setAsNumber(1)
            store.update(bgpPeer3)

            And("Updating the router to generate another update")
            val router2 = router1.setAsNumber(2)
            store.update(router2)

            Then("The observer should receive only the router update")
            obs.awaitOnNext(3, timeout) shouldBe true
            obs.getOnNextEvents.get(2) shouldBe bgpRouter(router2)
        }

        scenario("Re-add BGP peer with previously missing AS number") {
            Given("A router")
            val router = createRouter(asNumber = Some(1))
            store.create(router)

            And("A BGP peer without AS number")
            val bgpPeer1 = createBgpPeer(routerId = Some(router.getId),
                                         address = Some("10.0.0.1"))
            store.create(bgpPeer1)

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP router mapper for the router")
            val mapper = new BgpRouterMapper(router.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the BGP router without the peer")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents.get(0) shouldBe bgpRouter(router)

            When("Removing the BGP peer from the router")
            val bgpPeer2 = bgpPeer1.clearRouterId()
            store.update(bgpPeer2)

            And("Re-dding the BGP peer with an AS number")
            val bgpPeer3 = bgpPeer1.setAsNumber(1)
            store.update(bgpPeer3)

            Then("The observer should receive the router with peer")
            obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents.get(1) shouldBe bgpRouter(router, Set(bgpPeer3))
        }

        scenario("Added BGP peer with missing address ignored") {
            Given("A router")
            val router1 = createRouter(asNumber = Some(1))
            store.create(router1)

            And("A BGP peer without AS number")
            val bgpPeer1 = createBgpPeer(routerId = Some(router1.getId),
                                         asNumber = Some(1))
            store.create(bgpPeer1)

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP router mapper for the router")
            val mapper = new BgpRouterMapper(router1.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the BGP router without the peer")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents.get(0) shouldBe bgpRouter(router1)

            When("Updating the BGP peer with an address")
            val bgpPeer2 = bgpPeer1.setAddress("10.0.0.1")
            store.update(bgpPeer2)

            And("Updating the router to generate another update")
            val router2 = router1.setAsNumber(2)
            store.update(router2)

            Then("The observer should receive only the router update")
            obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents.get(1) shouldBe bgpRouter(router2)
        }

        scenario("Updated BGP peer with missing address ignored") {
            Given("A router")
            val router1 = createRouter(asNumber = Some(1))
            store.create(router1)

            And("A BGP peer")
            val bgpPeer1 = createBgpPeer(routerId = Some(router1.getId),
                                         asNumber = Some(1),
                                         address = Some("10.0.0.1"))
            store.create(bgpPeer1)

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP router mapper for the router")
            val mapper = new BgpRouterMapper(router1.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the BGP router")
            obs.awaitOnNext(1, timeout) shouldBe true

            When("Updating the BGP peer without an address")
            val bgpPeer2 = bgpPeer1.clearAddress()
            store.update(bgpPeer2)

            Then("The observer should receive a BGP router without a peer")
            obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents.get(1) shouldBe bgpRouter(router1)

            When("Updating the BGP peer with an address")
            val bgpPeer3 = bgpPeer2.setAddress("10.0.0.1")
            store.update(bgpPeer3)

            And("Updating the router to generate another update")
            val router2 = router1.setAsNumber(2)
            store.update(router2)

            Then("The observer should receive only the router update")
            obs.awaitOnNext(3, timeout) shouldBe true
            obs.getOnNextEvents.get(2) shouldBe bgpRouter(router2)
        }

        scenario("Re-add BGP peer with previously missing address") {
            Given("A router")
            val router = createRouter(asNumber = Some(1))
            store.create(router)

            And("A BGP peer without address")
            val bgpPeer1 = createBgpPeer(routerId = Some(router.getId),
                                         asNumber = Some(1))
            store.create(bgpPeer1)

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP router mapper for the router")
            val mapper = new BgpRouterMapper(router.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the BGP router without the peer")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents.get(0) shouldBe bgpRouter(router)

            When("Removing the BGP peer from the router")
            val bgpPeer2 = bgpPeer1.clearRouterId()
            store.update(bgpPeer2)

            And("Re-dding the BGP peer with an address")
            val bgpPeer3 = bgpPeer1.setAddress("10.0.0.1")
            store.update(bgpPeer3)

            Then("The observer should receive the router with peer")
            obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents.get(1) shouldBe bgpRouter(router, Set(bgpPeer3))
        }
    }

    feature("Test BGP network updates") {
        scenario("For existing BGP network") {
            Given("A router")
            val router = createRouter(asNumber = Some(1))
            store.create(router)

            And("A BGP peer")
            val bgpPeer = createBgpPeer(routerId = Some(router.getId),
                                        asNumber = Some(1),
                                        address = Some("10.0.0.1"))
            store.create(bgpPeer)

            And("A BGP network")
            val bgpNetwork = createBgpNetwork(routerId = Some(router.getId),
                                              subnet = Some(randomIPv4Subnet))
            store.create(bgpNetwork)

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP mapper for the port")
            val mapper = new BgpRouterMapper(router.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the BGP router and the network")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents.get(0) shouldBe bgpRouter(router, Set(bgpPeer),
                                                          Set(bgpNetwork))
        }

        scenario("For existing BGP networks") {
            Given("A router")
            val router = createRouter(asNumber = Some(1))
            store.create(router)

            And("A BGP peer")
            val bgpPeer = createBgpPeer(routerId = Some(router.getId),
                                        asNumber = Some(1),
                                        address = Some("10.0.0.1"))
            store.create(bgpPeer)

            And("A BGP network")
            val bgpNetwork1 = createBgpNetwork(routerId = Some(router.getId),
                                               subnet = Some(randomIPv4Subnet))
            val bgpNetwork2 = createBgpNetwork(routerId = Some(router.getId),
                                               subnet = Some(randomIPv4Subnet))
            store.multi(Seq(CreateOp(bgpNetwork1), CreateOp(bgpNetwork2)))

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP mapper for the port")
            val mapper = new BgpRouterMapper(router.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the BGP router and the network")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents.get(0) shouldBe bgpRouter(router, Set(bgpPeer),
                                                          Set(bgpNetwork1,
                                                              bgpNetwork2))
        }

        scenario("For added BGP network to existing BGP peer") {
            Given("A router")
            val router = createRouter(asNumber = Some(1))
            store.create(router)

            And("A BGP peer")
            val bgpPeer = createBgpPeer(routerId = Some(router.getId),
                                        asNumber = Some(1),
                                        address = Some("10.0.0.1"))
            store.create(bgpPeer)

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP router mapper for the router")
            val mapper = new BgpRouterMapper(router.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the BGP router")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents.get(0) shouldBe bgpRouter(router, Set(bgpPeer))

            When("Adding a BGP network")
            val bgpNetwork = createBgpNetwork(routerId = Some(router.getId),
                                              subnet = Some(randomIPv4Subnet))
            store.create(bgpNetwork)

            Then("The observer should receive the BGP router with the network")
            obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents.get(1) shouldBe bgpRouter(router, Set(bgpPeer),
                                                          Set(bgpNetwork))
        }

        scenario("For BGP network added to added BGP peer") {
            Given("A router")
            val router = createRouter(asNumber = Some(1))
            store.create(router)

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP router mapper for the router")
            val mapper = new BgpRouterMapper(router.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the BGP router")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents.get(0) shouldBe bgpRouter(router)

            When("Adding a BGP peer")
            val bgpPeer = createBgpPeer(routerId = Some(router.getId),
                                        asNumber = Some(1),
                                        address = Some("10.0.0.1"))
            store.create(bgpPeer)

            Then("The observer should receive the BGP peer")
            obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents.get(1) shouldBe bgpRouter(router, Set(bgpPeer))

            When("Adding a BGP network")
            val bgpNetwork = createBgpNetwork(routerId = Some(router.getId),
                                              subnet = Some(randomIPv4Subnet))
            store.create(bgpNetwork)

            Then("The observer should receive the BGP network")
            obs.awaitOnNext(3, timeout) shouldBe true
            obs.getOnNextEvents.get(2) shouldBe bgpRouter(router, Set(bgpPeer),
                                                          Set(bgpNetwork))
        }

        scenario("For updated BGP network") {
            Given("A router")
            val router = createRouter(asNumber = Some(1))
            store.create(router)

            And("A BGP peer")
            val bgpPeer = createBgpPeer(routerId = Some(router.getId),
                                        asNumber = Some(1),
                                        address = Some("10.0.0.1"))
            store.create(bgpPeer)

            And("A BGP network")
            val bgpNetwork1 = createBgpNetwork(routerId = Some(router.getId),
                                               subnet = Some(randomIPv4Subnet))
            store.create(bgpNetwork1)

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP router mapper for the router")
            val mapper = new BgpRouterMapper(router.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the BGP router with the network")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents.get(0) shouldBe bgpRouter(router, Set(bgpPeer),
                                                          Set(bgpNetwork1))

            When("Updating the BGP network")
            val bgpNetwork2 = bgpNetwork1.setSubnet(randomIPv4Subnet)
            store.update(bgpNetwork2)

            Then("The observer should receive the update")
            obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents.get(1) shouldBe bgpRouter(router, Set(bgpPeer),
                                                          Set(bgpNetwork2))
        }

        scenario("For removed BGP network") {
            Given("A router")
            val router = createRouter(asNumber = Some(1))
            store.create(router)

            And("A BGP peer")
            val bgpPeer = createBgpPeer(routerId = Some(router.getId),
                                        asNumber = Some(1),
                                        address = Some("10.0.0.1"))
            store.create(bgpPeer)

            And("A BGP network")
            val bgpNetwork = createBgpNetwork(routerId = Some(router.getId),
                                              subnet = Some(randomIPv4Subnet))
            store.create(bgpNetwork)

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP router mapper for the router")
            val mapper = new BgpRouterMapper(router.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the BGP router with the network")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents.get(0) shouldBe bgpRouter(router, Set(bgpPeer),
                                                          Set(bgpNetwork))

            When("Removing the BGP network")
            store.delete(classOf[BgpNetwork], bgpNetwork.getId)

            Then("The observer should receive the network removal")
            obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents.get(1) shouldBe bgpRouter(router, Set(bgpPeer))
        }

        scenario("Added BGP network with missing subnet ignored") {
            Given("A router")
            val router1 = createRouter(asNumber = Some(1))
            store.create(router1)

            And("A BGP network without a subnet")
            val bgpNetwork1 = createBgpNetwork(routerId = Some(router1.getId))
            store.create(bgpNetwork1)

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP mapper for the port")
            val mapper = new BgpRouterMapper(router1.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive only the BGP router")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents.get(0) shouldBe bgpRouter(router1)

            When("Updating the BGP network with a subnet")
            val bgpNetwork2 = bgpNetwork1.setSubnet(randomIPv4Subnet)
            store.update(bgpNetwork2)

            And("Updating thr router to generate another notification")
            val router2 = router1.setAsNumber(2)
            store.update(router2)

            Then("The observer should receive only the second BGP router")
            obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents.get(1) shouldBe bgpRouter(router2)
        }

        scenario("Updated BGP peer with missing subnet ignored") {
            Given("A router")
            val router1 = createRouter(asNumber = Some(1))
            store.create(router1)

            And("A BGP network")
            val bgpNetwork1 = createBgpNetwork(routerId = Some(router1.getId),
                                               subnet = Some(randomIPv4Subnet))
            store.create(bgpNetwork1)

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP mapper for the port")
            val mapper = new BgpRouterMapper(router1.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the BGP router and the network")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents.get(0) shouldBe bgpRouter(router1, Set(),
                                                          Set(bgpNetwork1))

            When("Updating the BGP network without a subnet")
            val bgpNetwork2 = bgpNetwork1.clearSubnet()
            store.update(bgpNetwork2)

            Then("The observer should receive the BGP router")
            obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents.get(1) shouldBe bgpRouter(router1)

            When("Updating the BGP network with a subnet")
            val bgpNetwork3 = bgpNetwork2.setSubnet(randomIPv4Subnet)
            store.update(bgpNetwork2)

            And("Updating thr router to generate another notification")
            val router2 = router1.setAsNumber(2)
            store.update(router2)

            Then("The observer should receive only the second BGP router")
            obs.awaitOnNext(3, timeout) shouldBe true
            obs.getOnNextEvents.get(2) shouldBe bgpRouter(router2)
        }

        scenario("Re-add BGP peer with previously missing AS number") {
            Given("A router")
            val router = createRouter(asNumber = Some(1))
            store.create(router)

            And("A BGP network without a subnet")
            val bgpNetwork1 = createBgpNetwork(routerId = Some(router.getId))
            store.create(bgpNetwork1)

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP mapper for the port")
            val mapper = new BgpRouterMapper(router.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive only the BGP router")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents.get(0) shouldBe bgpRouter(router)

            When("Removing the BGP network from the router")
            val bgpNetwork2 = bgpNetwork1.clearRouterId()
            store.update(bgpNetwork2)

            And("Re-adding the BGP network with a subnet")
            val bgpNetwork3 = bgpNetwork1.setSubnet(randomIPv4Subnet)
            store.update(bgpNetwork3)

            Then("The observer should receive the BGP router and network")
            obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents.get(1) shouldBe bgpRouter(router, Set(),
                                                          Set(bgpNetwork3))
        }
    }

    feature("Test subscriber receives initial state") {
        scenario("There are no updates from a previous subscription") {
            Given("A router ")
            val router = createRouter(asNumber = Some(1))
            store.create(router)

            And("A BGP peer")
            val bgpPeer = createBgpPeer(routerId = Some(router.getId),
                                        asNumber = Some(1),
                                        address = Some("10.0.0.1"))
            store.create(bgpPeer)

            And("A BGP network")
            val bgpNetwork = createBgpNetwork(routerId = Some(router.getId),
                                              subnet = Some(randomIPv4Subnet))
            store.create(bgpNetwork)

            And("A first BGP observer")
            val obs1 = createObserver()

            When("Creating a BGP router mapper for the router")
            val mapper = new BgpRouterMapper(router.getId, vt)

            And("Subscribing to an observable on the mapper")
            val observable = Observable.create(mapper)
            observable.subscribe(obs1)

            Then("The observer should receive the BGP router")
            obs1.awaitOnNext(1, timeout) shouldBe true
            obs1.getOnNextEvents.get(0) shouldBe bgpRouter(router, Set(bgpPeer),
                                                           Set(bgpNetwork))

            When("A second BGP observer subscribes")
            val obs2 = createObserver()
            observable.subscribe(obs2)

            Then("The observer should receive the same update")
            obs2.awaitOnNext(1, timeout) shouldBe true
            obs2.getOnNextEvents.get(0) shouldBe bgpRouter(router, Set(bgpPeer),
                                                           Set(bgpNetwork))
        }

        scenario("With updates from a previous subscriber") {
            Given("A router")
            val router = createRouter(asNumber = Some(1))
            store.create(router)

            And("A BGP peer")
            val bgpPeer = createBgpPeer(routerId = Some(router.getId),
                                        asNumber = Some(1),
                                        address = Some("10.0.0.1"))
            store.create(bgpPeer)

            And("A BGP network")
            val bgpNetwork1 = createBgpNetwork(routerId = Some(router.getId),
                                               subnet = Some(randomIPv4Subnet))
            store.create(bgpNetwork1)

            And("A first BGP observer")
            val obs1 = createObserver()

            When("Creating a BGP router mapper for the router")
            val mapper = new BgpRouterMapper(router.getId, vt)

            And("Subscribing to an observable on the mapper")
            val observable = Observable.create(mapper)
            observable.subscribe(obs1)

            Then("The observer should receive the BGP router")
            obs1.awaitOnNext(1, timeout) shouldBe true
            obs1.getOnNextEvents.get(0) shouldBe bgpRouter(router, Set(bgpPeer),
                                                           Set(bgpNetwork1))

            When("Adding a new network to the BGP peer")
            val bgpNetwork2 = createBgpNetwork(routerId = Some(router.getId),
                                               subnet = Some(randomIPv4Subnet))
            store.create(bgpNetwork2)

            Then("The observer should receive the updated BGP router")
            obs1.awaitOnNext(2, timeout) shouldBe true
            obs1.getOnNextEvents.get(1) shouldBe bgpRouter(router, Set(bgpPeer),
                                                           Set(bgpNetwork1,
                                                               bgpNetwork2))

            When("Removing the first network")
            store.delete(classOf[BgpNetwork], bgpNetwork1.getId)

            Then("The observer should receive the network removal")
            obs1.awaitOnNext(3, timeout) shouldBe true
            obs1.getOnNextEvents.get(2) shouldBe bgpRouter(router, Set(bgpPeer),
                                                           Set(bgpNetwork2))

            When("A second BGP observer subscribes")
            val obs2 = createObserver()
            observable.subscribe(obs2)

            Then("The observer should receive the last BGP router")
            obs2.awaitOnNext(1, timeout) shouldBe true
            obs2.getOnNextEvents.get(0) shouldBe bgpRouter(router, Set(bgpPeer),
                                                           Set(bgpNetwork2))
        }
    }
}
