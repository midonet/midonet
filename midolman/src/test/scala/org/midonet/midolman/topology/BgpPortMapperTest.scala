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
import com.google.common.collect.Lists

import org.midonet.cluster.data.storage.{CreateOp, NotFoundException, Storage, UpdateOp}
import org.midonet.cluster.models.Topology.{BgpNetwork, BgpPeer, Router, Port => TopologyPort}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.state.PortStateStorage.PortInactive
import org.midonet.cluster.topology.{TopologyBuilder, TopologyMatchers}
import org.midonet.cluster.util.IPAddressUtil._
import org.midonet.cluster.util.{IPAddressUtil, IPSubnetUtil}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.simulation.{Port, RouterPort}
import org.midonet.midolman.topology.DeviceMapper.MapperState
import org.midonet.midolman.topology.devices._
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.packets.{IPSubnet, IPv4Addr}
import org.midonet.quagga.BgpdConfiguration.{BgpRouter, Neighbor, Network}
import org.midonet.util.reactivex.AwaitableObserver

@RunWith(classOf[JUnitRunner])
class BgpPortMapperTest extends MidolmanSpec with TopologyBuilder
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
        new TestObserver[devices.BgpPort] with AwaitableObserver[devices.BgpPort]
    }

    private def bgpPort(port: TopologyPort, router: Router,
                        peers: Set[BgpPeer] = Set.empty,
                        networks: Set[BgpNetwork] = Set.empty): BgpPort = {
        BgpPort(Port(port,
                     PortInactive,
                     Lists.newArrayList(),
                     Lists.newArrayList(),
                     fip64vxlan=config.fip64.vxlanDownlink).asInstanceOf[RouterPort],
                BgpRouter(
                    as = router.getAsNumber,
                    id = IPAddressUtil.toIPv4Addr(port.getPortAddress),
                    neighbors = peers
                        .map(p => (p.getAddress.asIPv4Address,
                        Neighbor(p.getAddress.asIPv4Address,
                                 p.getAsNumber,
                                 Some(vt.config.bgpKeepAlive),
                                 Some(vt.config.bgpHoldTime),
                                 Some(vt.config.bgpConnectRetry)))).toMap,
                    networks = networks
                        .map(n => Network(IPSubnetUtil.fromV4Proto(n.getSubnet)))
                ),
                peers.map(_.getId.asJava))
    }

    implicit def asIPSubnet(str: String): IPSubnet[_] = IPSubnet.fromCidr(str)
    implicit def asIPAddress(str: String): IPv4Addr = IPv4Addr(str)

    feature("Test mapper lifecycle") {
        scenario("Mapper subscribes and unsubscribes") {
            Given("A router and a port")
            val router = createRouter()
            val port = createRouterPort(routerId = Some(router.getId))
            store.multi(Seq(CreateOp(router), CreateOp(port)))

            And("Three BGP observers")
            val obs1 = createObserver()
            val obs2 = createObserver()
            val obs3 = createObserver()

            When("Creating a BGP port mapper for the port")
            val mapper = new BgpPortMapper(port.getId, vt)

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
            Given("A router and a port")
            val router = createRouter()
            val port = createRouterPort(routerId = Some(router.getId))
            store.multi(Seq(CreateOp(router), CreateOp(port)))

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP port mapper for the port")
            val mapper = new BgpPortMapper(port.getId, vt)

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

        scenario("Mapper terminates for port deleted") {
            Given("A router and a port")
            val router = createRouter()
            val port = createRouterPort(routerId = Some(router.getId))
            store.multi(Seq(CreateOp(router), CreateOp(port)))

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP port mapper for the port")
            val mapper = new BgpPortMapper(port.getId, vt)

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

            When("The port is deleted")
            store.delete(classOf[TopologyPort], port.getId)

            Then("The observer should receive a port deleted notification")
            obs.awaitCompletion(timeout)
            obs.getOnErrorEvents.get(0) shouldBe BgpPortDeleted(port.getId)
            sub.isUnsubscribed shouldBe true

            And("The mapper should be completed")
            mapper.mapperState shouldBe MapperState.Error
            mapper.hasObservers shouldBe false
        }

        scenario("Mapper terminates for router deleted") {
            Given("A router and a port")
            val router = createRouter()
            val port = createRouterPort(routerId = Some(router.getId))
            store.multi(Seq(CreateOp(router), CreateOp(port)))

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP port mapper for the port")
            val mapper = new BgpPortMapper(port.getId, vt)

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

            Then("The observer should receive a router deleted notification")
            obs.awaitCompletion(timeout)
            obs.getOnErrorEvents.get(0) shouldBe BgpRouterDeleted(port.getId,
                                                                  router.getId)
            sub.isUnsubscribed shouldBe true

            And("The mapper should be completed")
            mapper.mapperState shouldBe MapperState.Error
            mapper.hasObservers shouldBe false
        }

        scenario("Mapper terminates with error") {
            Given("A non-existent port")
            val portId = UUID.randomUUID

            And("Two BGP observers")
            val obs1 = createObserver()
            val obs2 = createObserver()

            When("Creating a BGP port mapper for the port")
            val mapper = new BgpPortMapper(portId, vt)

            And("An observable on the mapper")
            val observable = Observable.create(mapper)

            Then("The mapper should be unsubscribed")
            mapper.mapperState shouldBe MapperState.Unsubscribed
            mapper.hasObservers shouldBe false

            When("The observer subscribes to the observable")
            val sub1 = observable subscribe obs1

            Then("The observer should receive an error")
            obs1.awaitCompletion(timeout)
            obs1.getOnErrorEvents.get(0) match {
                case BgpPortError(id, e) =>
                    id shouldBe portId
                    e.getClass shouldBe classOf[NotFoundException]
                case _ => fail("Unexpected error")
            }
            sub1.isUnsubscribed shouldBe true

            And("The mapper should be receive an error")
            mapper.mapperState shouldBe MapperState.Error
            mapper.hasObservers shouldBe false

            When("A second observer subscribes")
            val sub2 = observable subscribe obs2

            Then("The observer should receive an error")
            obs2.getOnErrorEvents.get(0) match {
                case BgpPortError(id, e) =>
                    id shouldBe portId
                    e.getClass shouldBe classOf[NotFoundException]
                case _ => fail("Unexpected error")
            }
            sub2.isUnsubscribed shouldBe true
        }
    }

    feature("Test mapper port updates") {
        scenario("Port does not exist") {
            Given("A random port identifier")
            val portId = UUID.randomUUID

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP port mapper for the port")
            val mapper = new BgpPortMapper(portId, vt)

            And("An observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive an error")
            obs.awaitCompletion(timeout)
            obs.getOnErrorEvents.get(0)match {
                case BgpPortError(id, e) =>
                    id shouldBe portId
                    e.getClass shouldBe classOf[NotFoundException]
                case _ => fail("Unexpected error")
            }
        }

        scenario("Port is not a router port") {
            Given("A bridge port")
            val bridge = createBridge()
            val port = createBridgePort(bridgeId = Some(bridge.getId))
            store.multi(Seq(CreateOp(bridge), CreateOp(port)))

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP port mapper for the port")
            val mapper = new BgpPortMapper(port.getId, vt)

            And("An observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive an on completed")
            obs.awaitCompletion(timeout)
            obs.getOnCompletedEvents should not be empty
        }

        scenario("Port updated") {
            Given("A router and a port")
            val router = createRouter(asNumber = Some(1))
            val port1 = createRouterPort(routerId = Some(router.getId))
            store.multi(Seq(CreateOp(router), CreateOp(port1)))

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP port mapper for the port")
            val mapper = new BgpPortMapper(port1.getId, vt)

            And("An observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the BGP port")
            obs.awaitOnNext(1, timeout)
            obs.getOnNextEvents.get(0) shouldBe bgpPort(port1, router)

            When("The port is updated")
            val port2 = port1.setTunnelKey(1L)
            store.update(port2)

            Then("The observer should receive the updated BGP port")
            obs.awaitOnNext(2, timeout)
            obs.getOnNextEvents.get(1) shouldBe bgpPort(port2, router)
        }

        scenario("Port deleted") {
            Given("A router and a port")
            val router = createRouter(asNumber = Some(1))
            val port = createRouterPort(routerId = Some(router.getId))
            store.multi(Seq(CreateOp(router), CreateOp(port)))

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP port mapper for the port")
            val mapper = new BgpPortMapper(port.getId, vt)

            And("An observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the BGP port")
            obs.awaitOnNext(1, timeout)

            When("The port is deleted")
            store.delete(classOf[TopologyPort], port.getId)

            Then("The observer should receive a port deleted error")
            obs.awaitCompletion(timeout)
            obs.getOnErrorEvents.get(0) shouldBe BgpPortDeleted(port.getId)
        }

        scenario("Port changes router") {
            Given("A router and a port")
            val router1 = createRouter(asNumber = Some(1))
            val port1 = createRouterPort(routerId = Some(router1.getId))
            store.multi(Seq(CreateOp(router1), CreateOp(port1)))

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP port mapper for the port")
            val mapper = new BgpPortMapper(port1.getId, vt)

            And("An observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the BGP port")
            obs.awaitOnNext(1, timeout)
            obs.getOnNextEvents.get(0) shouldBe bgpPort(port1, router1)

            When("The port changes for a second router")
            val router2 = createRouter(asNumber = Some(2))
            val port2 = port1.setRouterId(router2.getId)
            store.multi(Seq(CreateOp(router2), UpdateOp(port2)))

            Then("The observer should receive the updated BGP port")
            obs.awaitOnNext(2, timeout)
            obs.getOnNextEvents.get(1) shouldBe bgpPort(port2, router2)
        }
    }

    feature("Test mapper router updates") {
        scenario("Router updated") {
            Given("A router and a port")
            val router1 = createRouter(asNumber = Some(1))
            val port = createRouterPort(routerId = Some(router1.getId))
            store.multi(Seq(CreateOp(router1), CreateOp(port)))

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP port mapper for the port")
            val mapper = new BgpPortMapper(port.getId, vt)

            And("An observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the BGP port")
            obs.awaitOnNext(1, timeout)
            obs.getOnNextEvents.get(0) shouldBe bgpPort(port, router1)

            When("The router is updated")
            val router2 = router1.setAsNumber(2).addPortId(port.getId)
            store.update(router2)

            Then("The observer should receive the updated BGP port")
            obs.awaitOnNext(2, timeout)
            obs.getOnNextEvents.get(1) shouldBe bgpPort(port, router2)
        }

        scenario("Router deleted") {
            Given("A router and a port")
            val router = createRouter(asNumber = Some(1))
            val port = createRouterPort(routerId = Some(router.getId))
            store.multi(Seq(CreateOp(router), CreateOp(port)))

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP port mapper for the port")
            val mapper = new BgpPortMapper(port.getId, vt)

            And("An observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the BGP port")
            obs.awaitOnNext(1, timeout)

            When("The router is deleted")
            store.delete(classOf[Router], router.getId)

            Then("The observer should receive a router deleted error")
            obs.awaitCompletion(timeout)
            obs.getOnErrorEvents.get(0) shouldBe BgpRouterDeleted(port.getId,
                                                                  router.getId)
        }
    }

    feature("Test peers are filtered according to the port subnet") {
        scenario("Peer belongs to the port subnet") {
            Given("A router, a port and a BGP peer")
            val router = createRouter(asNumber = Some(1))
            val port = createRouterPort(routerId = Some(router.getId),
                                        portSubnet = "10.0.0.0/24")
            val peer = createBgpPeer(asNumber = Some(2),
                                     address = Some("10.0.0.1"),
                                     routerId = Some(router.getId))
            store.multi(Seq(CreateOp(router), CreateOp(port), CreateOp(peer)))

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP port mapper for the port")
            val mapper = new BgpPortMapper(port.getId, vt)

            And("An observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the BGP port with the peer")
            obs.awaitOnNext(1, timeout)
            obs.getOnNextEvents.get(0) shouldBe bgpPort(port, router, Set(peer))
        }

        scenario("Peer does not belong to the port subnet") {
            Given("A router, a port and a BGP peer")
            val router = createRouter(asNumber = Some(1))
            val port = createRouterPort(routerId = Some(router.getId),
                                        portSubnet = "10.0.0.0/24")
            val peer = createBgpPeer(asNumber = Some(2),
                                     address = Some("10.0.1.1"),
                                     routerId = Some(router.getId))
            store.multi(Seq(CreateOp(router), CreateOp(port), CreateOp(peer)))

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP port mapper for the port")
            val mapper = new BgpPortMapper(port.getId, vt)

            And("An observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the BGP port without the peer")
            obs.awaitOnNext(1, timeout)
            obs.getOnNextEvents.get(0) shouldBe bgpPort(port, router)
        }

        scenario("Add peer to the port subnet") {
            Given("A router and a port")
            val router = createRouter(asNumber = Some(1))
            val port = createRouterPort(routerId = Some(router.getId),
                                        portSubnet = "10.0.0.0/24")
            store.multi(Seq(CreateOp(router), CreateOp(port)))

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP port mapper for the port")
            val mapper = new BgpPortMapper(port.getId, vt)

            And("An observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the BGP port")
            obs.awaitOnNext(1, timeout)
            obs.getOnNextEvents.get(0) shouldBe bgpPort(port, router)

            When("Adding a BGP peer to the port subnet")
            val peer = createBgpPeer(asNumber = Some(2),
                                     address = Some("10.0.0.1"),
                                     routerId = Some(router.getId))
            store.create(peer)

            Then("The observer should receive the BGP port with the peer")
            obs.awaitOnNext(2, timeout)
            obs.getOnNextEvents.get(1) shouldBe bgpPort(port, router, Set(peer))
        }

        scenario("Add peer to other subnet") {
            Given("A router and a port")
            val router = createRouter(asNumber = Some(1))
            val port = createRouterPort(routerId = Some(router.getId),
                                        portSubnet = "10.0.0.0/24")
            store.multi(Seq(CreateOp(router), CreateOp(port)))

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP port mapper for the port")
            val mapper = new BgpPortMapper(port.getId, vt)

            And("An observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the BGP port")
            obs.awaitOnNext(1, timeout)
            obs.getOnNextEvents.get(0) shouldBe bgpPort(port, router)

            When("Adding a BGP peer to the port subnet")
            val peer = createBgpPeer(asNumber = Some(2),
                                     address = Some("10.0.1.1"),
                                     routerId = Some(router.getId))
            store.create(peer)

            Then("The observer should receive the BGP port without the peer")
            obs.awaitOnNext(2, timeout)
            obs.getOnNextEvents.get(1) shouldBe bgpPort(port, router)
        }

        scenario("Remove peer from the port subnet") {
            Given("A router, a port and a BGP peer")
            val router = createRouter(asNumber = Some(1))
            val port = createRouterPort(routerId = Some(router.getId),
                                        portSubnet = "10.0.0.0/24")
            val peer = createBgpPeer(asNumber = Some(2),
                                     address = Some("10.0.0.1"),
                                     routerId = Some(router.getId))
            store.multi(Seq(CreateOp(router), CreateOp(port), CreateOp(peer)))

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP port mapper for the port")
            val mapper = new BgpPortMapper(port.getId, vt)

            And("An observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the BGP port with the peer")
            obs.awaitOnNext(1, timeout)
            obs.getOnNextEvents.get(0) shouldBe bgpPort(port, router, Set(peer))

            When("Deleting the BGP peer")
            store.delete(classOf[BgpPeer], peer.getId)

            Then("The observer should receive the BGP port without the peer")
            obs.awaitOnNext(2, timeout)
            obs.getOnNextEvents.get(1) shouldBe bgpPort(port, router)
        }

        scenario("Remove peer from other subnet") {
            Given("A router, a port and a BGP peer")
            val router = createRouter(asNumber = Some(1))
            val port = createRouterPort(routerId = Some(router.getId),
                                        portSubnet = "10.0.0.0/24")
            val peer = createBgpPeer(asNumber = Some(2),
                                     address = Some("10.0.1.1"),
                                     routerId = Some(router.getId))
            store.multi(Seq(CreateOp(router), CreateOp(port), CreateOp(peer)))

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP port mapper for the port")
            val mapper = new BgpPortMapper(port.getId, vt)

            And("An observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the BGP port without the peer")
            obs.awaitOnNext(1, timeout)
            obs.getOnNextEvents.get(0) shouldBe bgpPort(port, router)

            When("Deleting the BGP peer")
            store.delete(classOf[BgpPeer], peer.getId)

            Then("The observer should receive the BGP port without the peer")
            obs.awaitOnNext(2, timeout)
            obs.getOnNextEvents.get(1) shouldBe bgpPort(port, router)
        }

        scenario("Port changes subnet") {
            Given("A router, a port and three BGP peers")
            val router = createRouter(asNumber = Some(1))
            val port1 = createRouterPort(routerId = Some(router.getId),
                                         portSubnet = "10.0.0.0/24")
            val peer1 = createBgpPeer(asNumber = Some(2),
                                      address = Some("10.0.0.1"),
                                      routerId = Some(router.getId))
            val peer2 = createBgpPeer(asNumber = Some(3),
                                      address = Some("10.0.1.1"),
                                      routerId = Some(router.getId))
            val peer3 = createBgpPeer(asNumber = Some(4),
                                      address = Some("10.0.1.2"),
                                      routerId = Some(router.getId))
            store.multi(Seq(CreateOp(router), CreateOp(port1), CreateOp(peer1),
                            CreateOp(peer2), CreateOp(peer3)))

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP port mapper for the port")
            val mapper = new BgpPortMapper(port1.getId, vt)

            And("An observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the BGP port with the first peer")
            obs.awaitOnNext(1, timeout)
            obs.getOnNextEvents.get(0) shouldBe bgpPort(port1, router, Set(peer1))

            When("Port changes the subnet to include the last two peers")
            val port2 = port1.setPortSubnet("10.0.1.0/24")
            store.update(port2)

            Then("The observer should receive the BGP port with the last peers")
            obs.awaitOnNext(2, timeout)
            obs.getOnNextEvents.get(1) shouldBe bgpPort(port2, router, Set(peer2,
                                                                           peer3))

            When("Port changes the subnet to include no peers")
            val port3 = port1.setPortSubnet("10.0.2.0/24")
            store.update(port3)

            Then("The observer should receive the BGP port with no peers")
            obs.awaitOnNext(3, timeout)
            obs.getOnNextEvents.get(2) shouldBe bgpPort(port3, router)
        }
    }

    feature("Test subscriber receives initial state") {
        scenario("Subscriber receives the last update") {
            Given("A router and a port")
            val router = createRouter(asNumber = Some(1))
            val port1 = createRouterPort(routerId = Some(router.getId))
            store.multi(Seq(CreateOp(router), CreateOp(port1)))

            And("Three BGP observers")
            val obs1 = createObserver()
            val obs2 = createObserver()
            val obs3 = createObserver()

            When("Creating a BGP port mapper for the port")
            val mapper = new BgpPortMapper(port1.getId, vt)

            And("An observable on the mapper")
            val observable = Observable.create(mapper)

            And("The first observer subscriber")
            observable subscribe obs1

            Then("The observer should receive the BGP port")
            obs1.awaitOnNext(1, timeout)
            obs1.getOnNextEvents.get(0) shouldBe bgpPort(port1, router)

            When("The second observer subscribes")
            observable subscribe obs2

            Then("The observer should receive the same BGP port")
            obs2.awaitOnNext(1, timeout)
            obs2.getOnNextEvents.get(0) shouldBe bgpPort(port1, router)

            When("The port is updated")
            val port2 = port1.setTunnelKey(1L)
            store.update(port2)

            Then("Both observers receive the updated BGP port")
            obs1.awaitOnNext(2, timeout)
            obs1.getOnNextEvents.get(1) shouldBe bgpPort(port2, router)

            obs2.awaitOnNext(2, timeout)
            obs2.getOnNextEvents.get(1) shouldBe bgpPort(port2, router)

            When("The third observer subscribes")
            observable subscribe obs3

            Then("The third observer receives the last update")
            obs3.awaitOnNext(1, timeout)
            obs3.getOnNextEvents.get(0) shouldBe bgpPort(port2, router)
        }

        scenario("Subscriber receives port deleted") {
            Given("A router and a port")
            val router = createRouter(asNumber = Some(1))
            val port = createRouterPort(routerId = Some(router.getId))
            store.multi(Seq(CreateOp(router), CreateOp(port)))

            And("Two BGP observers")
            val obs1 = createObserver()
            val obs2 = createObserver()

            When("Creating a BGP port mapper for the port")
            val mapper = new BgpPortMapper(port.getId, vt)

            And("An observable on the mapper")
            val observable = Observable.create(mapper)

            And("The first observer subscriber")
            observable subscribe obs1

            Then("The observer receives the BGP port")
            obs1.awaitOnNext(1, timeout)
            obs1.getOnNextEvents.get(0) shouldBe bgpPort(port, router)

            When("The port is deleted")
            store.delete(classOf[TopologyPort], port.getId)

            Then("The observer recieves a port deleted error")
            obs1.awaitCompletion(timeout)
            obs1.getOnErrorEvents.get(0) shouldBe BgpPortDeleted(port.getId)

            When("The second observer subscribes")
            observable subscribe obs2

            Then("The observer receives a port deleted error")
            obs2.awaitCompletion(timeout)
            obs2.getOnErrorEvents.get(0) shouldBe BgpPortDeleted(port.getId)
        }

        scenario("Subscriber receives router deleted") {
            Given("A router and a port")
            val router = createRouter(asNumber = Some(1))
            val port = createRouterPort(routerId = Some(router.getId))
            store.multi(Seq(CreateOp(router), CreateOp(port)))

            And("Two BGP observers")
            val obs1 = createObserver()
            val obs2 = createObserver()

            When("Creating a BGP port mapper for the port")
            val mapper = new BgpPortMapper(port.getId, vt)

            And("An observable on the mapper")
            val observable = Observable.create(mapper)

            And("The first observer subscriber")
            observable subscribe obs1

            Then("The observer receives the BGP port")
            obs1.awaitOnNext(1, timeout)
            obs1.getOnNextEvents.get(0) shouldBe bgpPort(port, router)

            When("The router is deleted")
            store.delete(classOf[Router], router.getId)

            Then("The observer recieves a router deleted error")
            obs1.awaitCompletion(timeout)
            obs1.getOnErrorEvents.get(0) shouldBe BgpRouterDeleted(port.getId,
                                                                   router.getId)

            When("The second observer subscribes")
            observable subscribe obs2

            Then("The observer receives a router deleted error")
            obs2.awaitCompletion(timeout)
            obs2.getOnErrorEvents.get(0) shouldBe BgpRouterDeleted(port.getId,
                                                                   router.getId)
        }
    }

}
