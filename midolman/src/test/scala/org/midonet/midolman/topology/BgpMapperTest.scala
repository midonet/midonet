package org.midonet.midolman.topology

import java.util.UUID

import scala.concurrent.duration._

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import rx.Observable
import rx.observers.TestObserver

import org.midonet.cluster.data.storage.{NotFoundException, Storage}
import org.midonet.cluster.models.Topology.{BgpNetwork, BgpPeer, Port}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.util.IPAddressUtil._
import org.midonet.cluster.util.IPSubnetUtil
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.topology.BgpMapper._
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.packets.{IPSubnet, IPv4Addr}
import org.midonet.quagga.BgpdConfiguration.{Network, Neighbor, BgpRouter}
import org.midonet.util.MidonetEventually
import org.midonet.util.reactivex.{AssertableObserver, AwaitableObserver}

@RunWith(classOf[JUnitRunner])
class BgpMapperTest extends MidolmanSpec with TopologyBuilder
                    with TopologyMatchers with MidonetEventually {

    import TopologyBuilder._

    private var store: Storage = _
    private var vt: VirtualTopology = _
    private var threadId: Long = _

    private final val timeout = 5 seconds

    protected override def beforeTest(): Unit = {
        vt = injector.getInstance(classOf[VirtualTopology])
        store = injector.getInstance(classOf[MidonetBackend]).store
        threadId = Thread.currentThread.getId
    }

    private def createObserver() = {
        val createThreadId = Thread.currentThread().getId
        new TestObserver[BgpNotification]
        with AwaitableObserver[BgpNotification]
        with AssertableObserver[BgpNotification] {
            override def assert() =
                BgpMapperTest.this.assert(
                    vt.vtThreadId == Thread.currentThread.getId ||
                    createThreadId == Thread.currentThread.getId)
        }
    }

    private def createPort(): Port = {
        val router = createRouter()
        val port = createRouterPort(routerId = Some(router.getId))
        store.create(router)
        store.create(port)
        port
    }

    private def createExteriorPort(): Port = {
        val router = createRouter()
        val host = createHost()
        val port = createRouterPort(routerId = Some(router.getId),
                                    hostId = Some(host.getId),
                                    interfaceName = Some("iface"))
        store.create(router)
        store.create(host)
        store.create(port)
        port
    }

    private def createInteriorPort(): Port = {
        val router = createRouter()
        val peerPort = createPort()
        val port = createRouterPort(routerId = Some(router.getId),
                                    peerId = Some(peerPort.getId))
        store.create(router)
        store.create(port)
        port
    }

    private def makePortInterior(port: Port): Port = {
        val peerPort = createPort()
        port.clearHostId().clearInterfaceName().setPeerId(peerPort.getId)
    }

    private def makePortExterior(port: Port): Port = {
        val host = createHost()
        store.create(host)
        port.clearPeerId().setHostId(host.getId).setInterfaceName("iface")
    }

    private def bgpUpdate(port: Port, peers: Set[BgpPeer] = Set.empty,
                          networks: Set[BgpNetwork] = Set.empty)
    : BgpNotification = {
        val router = BgpRouter(
            as = port.getLocalAs,
            neighbors = peers
                .map(p => (p.getPeerAddress.asIPv4Address,
                           Neighbor(p.getPeerAddress.asIPv4Address,
                                    p.getPeerAs,
                                    Some(vt.config.bgpKeepAlive),
                                    Some(vt.config.bgpHoldTime),
                                    Some(vt.config.bgpConnectRetry)))).toMap,
            networks = networks
                .map(n => Network(IPSubnetUtil.fromV4Proto(n.getSubnet)))
        )
        BgpUpdated(router, peers.map(_.getId.asJava))
    }

    implicit def asIPSubnet(str: String): IPSubnet[_] = IPSubnet.fromString(str)
    implicit def asIPAddress(str: String): IPv4Addr = IPv4Addr(str)

    feature("Test mapper lifecycle") {
        scenario("Mapper subscribes and unsubscribes") {
            Given("A router exterior port")
            val port = createExteriorPort()

            And("Three BGP observers")
            val obs1 = createObserver()
            val obs2 = createObserver()
            val obs3 = createObserver()

            When("Creating a BGP mapper for the port")
            val mapper = new BgpMapper(port.getId, vt)

            And("An observable on the mapper")
            val observable = Observable.create(mapper)

            Then("The mapper should be unsubscribed")
            eventually { mapper.mapperState shouldBe State.Unsubscribed }
            eventually { mapper.hasObservers shouldBe false }

            When("The first observer subscribes to the observable")
            val sub1 = observable subscribe obs1

            Then("The mapper should be subscribed")
            eventually { mapper.mapperState shouldBe State.Subscribed }
            eventually { mapper.hasObservers shouldBe true }

            And("The observer should receive a notification")
            obs1.awaitOnNext(1, timeout) shouldBe true

            When("The second observer subscribes to the observable")
            val sub2 = observable subscribe obs2

            Then("The mapper should be subscribed")
            eventually { mapper.mapperState shouldBe State.Subscribed }
            eventually { mapper.hasObservers shouldBe true }

            And("The observer should receive a notification")
            obs2.awaitOnNext(1, timeout) shouldBe true

            When("The first observable unsubscribes")
            sub1.unsubscribe()

            Then("The mapper should be subscribed")
            eventually { mapper.mapperState shouldBe State.Subscribed }
            eventually { mapper.hasObservers shouldBe true }

            When("The second observable unsubscribes")
            sub2.unsubscribe()

            Then("The mapper should be closed")
            eventually { mapper.mapperState shouldBe State.Closed }
            eventually { mapper.hasObservers shouldBe false }

            When("A third observer subscribes")
            observable subscribe obs3

            Then("The observer should receive an error")
            obs3.awaitCompletion(timeout)
            obs3.getOnErrorEvents.get(0) shouldBe BgpMapper.MapperClosedException
        }

        scenario("Mapper re-subscribes") {
            Given("A router exterior port")
            val port = createExteriorPort()

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP mapper for the port")
            val mapper = new BgpMapper(port.getId, vt)

            And("An observable on the mapper")
            val observable = Observable.create(mapper)

            Then("The mapper should be unsubscribed")
            eventually { mapper.mapperState shouldBe State.Unsubscribed }
            eventually { mapper.hasObservers shouldBe false }

            When("The observer subscribes to the observable")
            val sub1 = observable subscribe obs

            Then("The mapper should be subscribed")
            eventually { mapper.mapperState shouldBe State.Subscribed }
            eventually { mapper.hasObservers shouldBe true }

            And("The observer should receive a notification")
            obs.awaitOnNext(1, timeout) shouldBe true

            When("The observer unsubscribes")
            sub1.unsubscribe()

            Then("The mapper should be closed")
            eventually { mapper.mapperState shouldBe State.Closed }
            eventually { mapper.hasObservers shouldBe false }

            When("The observer re-subscribes to the observable")
            observable subscribe obs

            Then("The observer should receive an error")
            obs.awaitCompletion(timeout)
            obs.getOnErrorEvents.get(0) shouldBe BgpMapper.MapperClosedException
        }

        scenario("Mapper terminates with completed") {
            Given("A router exterior port")
            val port = createExteriorPort()

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP mapper for the port")
            val mapper = new BgpMapper(port.getId, vt)

            And("An observable on the mapper")
            val observable = Observable.create(mapper)

            Then("The mapper should be unsubscribed")
            eventually { mapper.mapperState shouldBe State.Unsubscribed }
            eventually { mapper.hasObservers shouldBe false }

            When("The observer subscribes to the observable")
            val sub = observable subscribe obs

            Then("The mapper should be subscribed")
            eventually { mapper.mapperState shouldBe State.Subscribed }
            eventually { mapper.hasObservers shouldBe true }

            And("The observer should receive a notification")
            obs.awaitOnNext(1, timeout) shouldBe true

            When("The port is deleted")
            store.delete(classOf[Port], port.getId)

            Then("The observer should receive a completed notification")
            obs.awaitCompletion(timeout)
            obs.getOnCompletedEvents should not be empty
            sub.isUnsubscribed shouldBe true

            And("The mapper should be completed")
            mapper.mapperState shouldBe State.Completed
            mapper.hasObservers shouldBe false
        }

        scenario("Mapper terminates with error") {
            Given("A non-existent port")
            val portId = UUID.randomUUID

            And("Two BGP observers")
            val obs1 = createObserver()
            val obs2 = createObserver()

            When("Creating a BGP mapper for the port")
            val mapper = new BgpMapper(portId, vt)

            And("An observable on the mapper")
            val observable = Observable.create(mapper)

            Then("The mapper should be unsubscribed")
            eventually { mapper.mapperState shouldBe State.Unsubscribed }
            eventually { mapper.hasObservers shouldBe false }

            When("The observer subscribes to the observable")
            val sub1 = observable subscribe obs1

            Then("The observer should receive an error")
            obs1.awaitCompletion(timeout)
            obs1.getOnErrorEvents.get(0).getClass shouldBe classOf[NotFoundException]
            sub1.isUnsubscribed shouldBe true

            And("The mapper should be receive an error")
            mapper.mapperState shouldBe State.Error
            mapper.hasObservers shouldBe false

            When("A second observer subscribes")
            val sub2 = observable subscribe obs2

            Then("The observer should receive an error")
            obs2.getOnErrorEvents.get(0).getClass shouldBe classOf[NotFoundException]
            sub2.isUnsubscribed shouldBe true
        }
    }

    feature("Test mapper port updates") {
        scenario("Port is not a router port") {
            Given("A bridge exterior port")
            val bridge = createBridge()
            val port = createBridgePort(bridgeId = Some(bridge.getId))
            store.create(bridge)
            store.create(port)

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP mapper for the port")
            val mapper = new BgpMapper(port.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive an error")
            obs.awaitCompletion(timeout)
            obs.getOnErrorEvents.get(0).getClass shouldBe classOf[DeviceMapperException]
        }

        scenario("Observer receives initial port notification") {
            Given("A router exterior port")
            val port = createExteriorPort()

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP mapper for the port")
            val mapper = new BgpMapper(port.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the port")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents.get(0) match {
                case BgpPort(p) => p shouldBeDeviceOf port
                case u => fail(s"Unexpected update $u")
            }
        }

        scenario("Observer receives port notification on update") {
            Given("A router exterior port")
            val port1 = createExteriorPort()

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP mapper for the port")
            val mapper = new BgpMapper(port1.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the port")
            obs.awaitOnNext(1, timeout) shouldBe true

            When("The port is updated")
            val port2 = port1.setPortAddress(IPv4Addr.random)
            store.update(port2)

            Then("The observer should receive the updated port")
            obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents.get(1) match {
                case BgpPort(p) => p shouldBeDeviceOf port2
                case u => fail(s"Unexpected update $u")
            }
        }
    }

    feature("Test BGP peer updates for exterior ports") {
        scenario("For existing BGP peers") {
            Given("A router exterior port")
            val port = createExteriorPort()

            And("A BGP peer")
            val bgpPeer = createBgpPeer(portId = Some(port.getId),
                                        peerAs = Some(1),
                                        peerAddress = Some("10.0.0.1"))
            store.create(bgpPeer)

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP mapper for the port")
            val mapper = new BgpMapper(port.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the port and BGP peer")
            obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents.get(1) shouldBe bgpUpdate(port, Set(bgpPeer))
        }

        scenario("When adding a BGP peer") {
            Given("A router exterior port")
            val port = createExteriorPort()

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP mapper for the port")
            val mapper = new BgpMapper(port.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the port update")
            obs.awaitOnNext(1, timeout) shouldBe true

            When("Creating a BGP peer for the port")
            val bgpPeer = createBgpPeer(portId = Some(port.getId),
                                        peerAs = Some(1),
                                        peerAddress = Some("10.0.0.1"))
            store.create(bgpPeer)

            Then("The observer should receive two updates")
            obs.awaitOnNext(3, timeout) shouldBe true
            obs.getOnNextEvents.get(1) match {
                case BgpPort(p) => p shouldBeDeviceOf port.addBgpPeerId(bgpPeer.getId)
                case u => fail(s"Unexpected update $u")
            }
            obs.getOnNextEvents.get(2) shouldBe bgpUpdate(port, Set(bgpPeer))
        }

        scenario("When updating a BGP peer") {
            Given("A router exterior port")
            val port = createExteriorPort()

            And("A BGP peer")
            val bgpPeer1 = createBgpPeer(portId = Some(port.getId),
                                         peerAs = Some(1),
                                         peerAddress = Some("10.0.0.1"))
            store.create(bgpPeer1)

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP mapper for the port")
            val mapper = new BgpMapper(port.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the port and BGP peer")
            obs.awaitOnNext(2, timeout) shouldBe true

            When("Updating the BGP peer with a different AS")
            val bgpPeer2 = bgpPeer1.setPeerAs(10)
            store.update(bgpPeer2)

            Then("The observer should receive a BGP peer update")
            obs.awaitOnNext(3, timeout) shouldBe true
            obs.getOnNextEvents.get(2) shouldBe bgpUpdate(port, Set(bgpPeer2))

            When("Updating the BGP peer with a different address")
            val bgpPeer3 = bgpPeer2.setPeerAddress("10.0.0.2")
            store.update(bgpPeer3)

            Then("The observer should receive a BGP peer update")
            obs.awaitOnNext(4, timeout) shouldBe true
            obs.getOnNextEvents.get(3) shouldBe bgpUpdate(port, Set(bgpPeer3))
        }

        scenario("When removing a BGP peer") {
            Given("A router exterior port")
            val port = createExteriorPort()

            And("A BGP peer")
            val bgpPeer = createBgpPeer(portId = Some(port.getId),
                                        peerAs = Some(1),
                                        peerAddress = Some("10.0.0.1"))
            store.create(bgpPeer)

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP mapper for the port")
            val mapper = new BgpMapper(port.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the port and BGP peer")
            obs.awaitOnNext(2, timeout) shouldBe true

            When("Deleting the BGP peer")
            store.delete(classOf[BgpPeer], bgpPeer.getId)

            Then("The observer should receive a BGP removal update")
            obs.awaitOnNext(4, timeout) shouldBe true
            obs.getOnNextEvents.get(2) match {
                case BgpPort(p) => p shouldBeDeviceOf port
                case u => fail(s"Unexpected update $u")
            }
            obs.getOnNextEvents.get(3) shouldBe bgpUpdate(port)
        }

        scenario("When adding/removing multiple BGP peers with different addresses") {
            Given("A router exterior port")
            val port = createExteriorPort()

            And("A BGP peer")
            val bgpPeer1 = createBgpPeer(portId = Some(port.getId),
                                         peerAs = Some(1),
                                         peerAddress = Some("10.0.0.1"))
            store.create(bgpPeer1)

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP mapper for the port")
            val mapper = new BgpMapper(port.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the port and BGP peer")
            obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents.get(1) shouldBe bgpUpdate(port, Set(bgpPeer1))

            When("Adding a second BGP peer")
            val bgpPeer2 = createBgpPeer(portId = Some(port.getId),
                                         peerAs = Some(2),
                                         peerAddress = Some("10.0.0.2"))
            store.create(bgpPeer2)

            Then("The observer should receive the first and second peers")
            obs.awaitOnNext(4, timeout) shouldBe true
            obs.getOnNextEvents.get(2) match {
                case BgpPort(p) => p shouldBeDeviceOf port.addBgpPeerId(bgpPeer1.getId)
                                                          .addBgpPeerId(bgpPeer2.getId)
                case u => fail(s"Unexpected update $u")
            }
            obs.getOnNextEvents.get(3) shouldBe bgpUpdate(port, Set(bgpPeer1,
                                                                    bgpPeer2))

            When("Removing the first BGP peer")
            store.delete(classOf[BgpPeer], bgpPeer1.getId)

            Then("The observer should receive only the second peer")
            obs.awaitOnNext(6, timeout) shouldBe true
            obs.getOnNextEvents.get(4) match {
                case BgpPort(p) => p shouldBeDeviceOf port.addBgpPeerId(bgpPeer2.getId)
                case u => fail(s"Unexpected update $u")
            }
            obs.getOnNextEvents.get(5) shouldBe bgpUpdate(port, Set(bgpPeer2))
        }

        scenario("When adding/removing multiple BGP peers with same address") {
            Given("A router exterior port")
            val port = createExteriorPort()

            And("A BGP peer")
            val bgpPeer1 = createBgpPeer(portId = Some(port.getId),
                                         peerAs = Some(1),
                                         peerAddress = Some("10.0.0.1"))
            store.create(bgpPeer1)

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP mapper for the port")
            val mapper = new BgpMapper(port.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the port and BGP peer")
            obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents.get(1) shouldBe bgpUpdate(port, Set(bgpPeer1))

            When("Adding a second BGP peer with the same address")
            val bgpPeer2 = createBgpPeer(portId = Some(port.getId),
                                         peerAs = Some(2),
                                         peerAddress = Some("10.0.0.1"))
            store.create(bgpPeer2)

            Then("The observer should receive only the first peer")
            obs.awaitOnNext(4, timeout) shouldBe true
            obs.getOnNextEvents.get(2) match {
                case BgpPort(p) => p shouldBeDeviceOf port.addBgpPeerId(bgpPeer1.getId)
                                                          .addBgpPeerId(bgpPeer2.getId)
                case u => fail(s"Unexpected update $u")
            }
            obs.getOnNextEvents.get(3) shouldBe bgpUpdate(port, Set(bgpPeer1))

            When("Removing the first BGP peer")
            store.delete(classOf[BgpPeer], bgpPeer1.getId)

            Then("The observer should receive only the second peer")
            obs.awaitOnNext(6, timeout) shouldBe true
            obs.getOnNextEvents.get(4) match {
                case BgpPort(p) => p shouldBeDeviceOf port.addBgpPeerId(bgpPeer2.getId)
                case u => fail(s"Unexpected update $u")
            }
            obs.getOnNextEvents.get(5) shouldBe bgpUpdate(port, Set(bgpPeer2))
        }
    }

    feature("Test BGP peer are not advertised for interior ports") {
        scenario("For existing BGP peers") {
            Given("A router interior port")
            val port = createInteriorPort()

            And("A BGP peer")
            val bgpPeer = createBgpPeer(portId = Some(port.getId),
                                        peerAs = Some(1),
                                        peerAddress = Some("10.0.0.1"))
            store.create(bgpPeer)

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP mapper for the port")
            val mapper = new BgpMapper(port.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            And("Waiting to receive the port update")
            obs.awaitOnNext(1, timeout) shouldBe true

            And("Deleting the port to generate another notification")
            store.delete(classOf[Port], port.getId)

            Then("The observer should receive no BGP updates")
            obs.awaitCompletion(timeout)
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) match {
                case BgpPort(p) => p shouldBeDeviceOf port.addBgpPeerId(bgpPeer.getId)
                case u => fail(s"Unexpected update $u")
            }
            obs.getOnCompletedEvents should not be empty
        }

        scenario("When adding a BGP peer") {
            Given("A router interior port")
            val port = createInteriorPort()

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP mapper for the port")
            val mapper = new BgpMapper(port.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            And("Waiting to receive the port update")
            obs.awaitOnNext(1, timeout) shouldBe true

            When("Creating a BGP peer for the port")
            val bgpPeer1 = createBgpPeer(portId = Some(port.getId),
                                         peerAs = Some(1),
                                         peerAddress = Some("10.0.0.1"))
            store.create(bgpPeer1)

            And("Waiting to receive the port update")
            obs.awaitOnNext(2, timeout) shouldBe true

            And("Creating a second BGP peer to generate another notification")
            val bgpPeer2 = createBgpPeer(portId = Some(port.getId),
                                         peerAs = Some(1),
                                         peerAddress = Some("10.0.0.2"))
            store.create(bgpPeer2)

            Then("The observer should receive only port updates")
            obs.awaitOnNext(3, timeout) shouldBe true
            obs.getOnNextEvents should have size 3
            obs.getOnNextEvents.get(0) match {
                case BgpPort(p) => p shouldBeDeviceOf port
                case u => fail(s"Unexpected update $u")
            }
            obs.getOnNextEvents.get(1) match {
                case BgpPort(p) => p shouldBeDeviceOf port.addBgpPeerId(bgpPeer1.getId)
                case u => fail(s"Unexpected update $u")
            }
            obs.getOnNextEvents.get(2) match {
                case BgpPort(p) => p shouldBeDeviceOf port.addBgpPeerId(bgpPeer1.getId)
                                                          .addBgpPeerId(bgpPeer2.getId)
                case u => fail(s"Unexpected update $u")
            }
        }

        scenario("When updating a BGP peer") {
            Given("A router interior port")
            val port = createInteriorPort()

            And("A BGP peer")
            val bgpPeer1 = createBgpPeer(portId = Some(port.getId),
                                         peerAs = Some(1),
                                         peerAddress = Some("10.0.0.1"))
            store.create(bgpPeer1)

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP mapper for the port")
            val mapper = new BgpMapper(port.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            And("Waiting to receive the port update")
            obs.awaitOnNext(1, timeout) shouldBe true

            When("Updating the BGP peer")
            val bgpPeer2 = bgpPeer1.setPeerAs(10)
            store.update(bgpPeer2)

            And("Creating a second BGP peer to generate another notification")
            val bgpPeer3 = createBgpPeer(portId = Some(port.getId),
                                         peerAs = Some(20),
                                         peerAddress = Some("10.0.0.2"))
            store.create(bgpPeer3)

            Then("The observer should receive a BGP peer update")
            obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents should have size 2
            obs.getOnNextEvents.get(0) match {
                case BgpPort(p) => p shouldBeDeviceOf port.addBgpPeerId(bgpPeer1.getId)
                case u => fail(s"Unexpected update $u")
            }
            obs.getOnNextEvents.get(1) match {
                case BgpPort(p) => p shouldBeDeviceOf port.addBgpPeerId(bgpPeer1.getId)
                                                          .addBgpPeerId(bgpPeer3.getId)
                case u => fail(s"Unexpected update $u")
            }
        }

        scenario("An exterior port with BGP becomes interior") {
            Given("A router exterior port")
            val port1 = createExteriorPort()

            And("A BGP peer")
            val bgpPeer = createBgpPeer(portId = Some(port1.getId),
                                        peerAs = Some(1),
                                        peerAddress = Some("10.0.0.1"))
            store.create(bgpPeer)

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP mapper for the port")
            val mapper = new BgpMapper(port1.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the port and BGP peer")
            obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents.get(1) shouldBe bgpUpdate(port1, Set(bgpPeer))

            When("The port becomes interior")
            val port2 = makePortInterior(port1)
            store.update(port2)

            Then("The observer should receive the port and BGP peer removed")
            obs.awaitOnNext(4, timeout) shouldBe true
            obs.getOnNextEvents.get(2) match {
                case BgpPort(p) => p shouldBeDeviceOf port2
                case u => fail(s"Unexpected update $u")
            }
            obs.getOnNextEvents.get(3) shouldBe bgpUpdate(port2)
        }

        scenario("An exterior port adds BGP and becomes interior") {
            Given("A router exterior port")
            val port1 = createExteriorPort()

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP mapper for the port")
            val mapper = new BgpMapper(port1.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the port update")
            obs.awaitOnNext(1, timeout) shouldBe true

            When("Creating a BGP peer for the port")
            val bgpPeer = createBgpPeer(portId = Some(port1.getId),
                                        peerAs = Some(1),
                                        peerAddress = Some("10.0.0.1"))
            store.create(bgpPeer)

            Then("The observer should receive two updates")
            obs.awaitOnNext(3, timeout) shouldBe true
            obs.getOnNextEvents.get(2) shouldBe bgpUpdate(port1, Set(bgpPeer))

            When("The port becomes interior")
            val port2 = makePortInterior(port1)
            store.update(port2)

            Then("The observer should receive the port and BGP peer removed")
            obs.awaitOnNext(5, timeout) shouldBe true
            obs.getOnNextEvents.get(3) match {
                case BgpPort(p) => p shouldBeDeviceOf port2
                case u => fail(s"Unexpected update $u")
            }
            obs.getOnNextEvents.get(4) shouldBe bgpUpdate(port2)
        }

        scenario("An exterior port becomes interior and adds BGP") {
            Given("A router exterior port")
            val port1 = createExteriorPort()

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP mapper for the port")
            val mapper = new BgpMapper(port1.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the port update")
            obs.awaitOnNext(1, timeout) shouldBe true

            When("The port becomes interior")
            val port2 = makePortInterior(port1)
            store.update(port2)

            Then("The observer should receive the port update")
            obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents.get(1) match {
                case BgpPort(p) => p shouldBeDeviceOf port2
                case u => fail(s"Unexpected update $u")
            }

            When("Creating a BGP peer for the port")
            val bgpPeer1 = createBgpPeer(portId = Some(port1.getId),
                                         peerAs = Some(1),
                                         peerAddress = Some("10.0.0.1"))
            store.create(bgpPeer1)

            Then("The observer should receive the port update")
            obs.awaitOnNext(3, timeout) shouldBe true
            obs.getOnNextEvents.get(2) match {
                case BgpPort(p) => p shouldBeDeviceOf port2.addBgpPeerId(bgpPeer1.getId)
                case u => fail(s"Unexpected update $u")
            }

            And("Creating a second BGP peer to generate another notification")
            val bgpPeer2 = createBgpPeer(portId = Some(port2.getId),
                                         peerAs = Some(20),
                                         peerAddress = Some("10.0.0.2"))
            store.create(bgpPeer2)

            Then("The observer should receive the port removed")
            obs.awaitOnNext(4, timeout) shouldBe true
            obs.getOnNextEvents.get(3) match {
                case BgpPort(p) => p shouldBeDeviceOf port2.addBgpPeerId(bgpPeer1.getId)
                                                           .addBgpPeerId(bgpPeer2.getId)
                case u => fail(s"Unexpected update $u")
            }
        }

        scenario("An interior port with BGP becomes exterior") {
            Given("A router interior port")
            val port1 = createInteriorPort()

            And("A BGP peer")
            val bgpPeer = createBgpPeer(portId = Some(port1.getId),
                                        peerAs = Some(1),
                                        peerAddress = Some("10.0.0.1"))
            store.create(bgpPeer)

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP mapper for the port")
            val mapper = new BgpMapper(port1.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            And("Waiting to receive the port update")
            obs.awaitOnNext(1, timeout) shouldBe true

            When("The port becomes exterior")
            val port2 = makePortExterior(port1).addBgpPeerId(bgpPeer.getId)
            store.update(port2)

            Then("The observer should receive the port and BGP updates")
            obs.awaitOnNext(3, timeout) shouldBe true
            obs.getOnNextEvents.get(1) match {
                case BgpPort(p) => p shouldBeDeviceOf port2
                case u => fail(s"Unexpected update $u")
            }
            obs.getOnNextEvents.get(2) shouldBe bgpUpdate(port2, Set(bgpPeer))
        }

        scenario("An interior port adds BGP and becomes exterior") {
            Given("A router interior port")
            val port1 = createInteriorPort()

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP mapper for the port")
            val mapper = new BgpMapper(port1.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            And("Waiting to receive the port update")
            obs.awaitOnNext(1, timeout) shouldBe true

            When("Creating a BGP peer for the port")
            val bgpPeer = createBgpPeer(portId = Some(port1.getId),
                                        peerAs = Some(1),
                                        peerAddress = Some("10.0.0.1"))
            store.create(bgpPeer)

            And("Waiting to receive the port update")
            obs.awaitOnNext(2, timeout) shouldBe true

            When("The port becomes exterior")
            val port2 = makePortExterior(port1).addBgpPeerId(bgpPeer.getId)
            store.update(port2)

            Then("The observer should receive the port and BGP updates")
            obs.awaitOnNext(4, timeout) shouldBe true
            obs.getOnNextEvents.get(2) match {
                case BgpPort(p) => p shouldBeDeviceOf port2
                case u => fail(s"Unexpected update $u")
            }
            obs.getOnNextEvents.get(3) shouldBe bgpUpdate(port2, Set(bgpPeer))
        }

        scenario("An interior port becomes exterior and adds BGP") {
            Given("A router interior port")
            val port1 = createInteriorPort()

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP mapper for the port")
            val mapper = new BgpMapper(port1.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            And("Waiting to receive the port update")
            obs.awaitOnNext(1, timeout) shouldBe true

            When("The port becomes exterior")
            val port2 = makePortExterior(port1)
            store.update(port2)

            Then("The observer should receive a port updates")
            obs.awaitOnNext(3, timeout) shouldBe true
            obs.getOnNextEvents.get(1) match {
                case BgpPort(p) => p shouldBeDeviceOf port2
                case u => fail(s"Unexpected update $u")
            }
            obs.getOnNextEvents.get(2) shouldBe bgpUpdate(port2)

            When("Creating a BGP peer for the port")
            val bgpPeer = createBgpPeer(portId = Some(port1.getId),
                                        peerAs = Some(1),
                                        peerAddress = Some("10.0.0.1"))
            store.create(bgpPeer)

            Then("The observer should receove the BGP update")
            obs.awaitOnNext(5, timeout) shouldBe true
            obs.getOnNextEvents.get(3) match {
                case BgpPort(p) => p shouldBeDeviceOf port2.addBgpPeerId(bgpPeer.getId)
                case u => fail(s"Unexpected update $u")
            }
            obs.getOnNextEvents.get(4) shouldBe bgpUpdate(port2, Set(bgpPeer))
        }
    }

    feature("Test BGP network updates") {
        scenario("For existing BGP network") {
            Given("A router exterior port")
            val port = createExteriorPort()

            And("A BGP peer")
            val bgpPeer = createBgpPeer(portId = Some(port.getId),
                                        peerAs = Some(1),
                                        peerAddress = Some("10.0.0.1"))
            store.create(bgpPeer)

            And("A BGP network")
            val bgpNetwork = createBgpNetwork(portId = Some(port.getId),
                                              subnet = Some(randomIPv4Subnet))
            store.create(bgpNetwork)

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP mapper for the port")
            val mapper = new BgpMapper(port.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the BGP network")
            obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents.get(1) shouldBe bgpUpdate(port, Set(bgpPeer),
                                                          Set(bgpNetwork))
        }

        scenario("For added BGP network to existing BGP peer") {
            Given("A router exterior port")
            val port = createExteriorPort()

            And("A BGP peer")
            val bgpPeer = createBgpPeer(portId = Some(port.getId),
                                        peerAs = Some(1),
                                        peerAddress = Some("10.0.0.1"))
            store.create(bgpPeer)

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP mapper for the port")
            val mapper = new BgpMapper(port.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the BGP peer")
            obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents.get(0) match {
                case BgpPort(p) => p shouldBeDeviceOf port.addBgpPeerId(bgpPeer.getId)
                case u => fail(s"Unexpected update $u")
            }
            obs.getOnNextEvents.get(1) shouldBe bgpUpdate(port, Set(bgpPeer))

            When("Adding a BGP network")
            val bgpNetwork = createBgpNetwork(portId = Some(port.getId),
                                              subnet = Some(randomIPv4Subnet))
            store.create(bgpNetwork)

            Then("The observer should receive the BGP network")
            obs.awaitOnNext(4, timeout) shouldBe true
            obs.getOnNextEvents.get(2) match {
                case BgpPort(p) => p shouldBeDeviceOf port.addBgpPeerId(bgpPeer.getId)
                                                          .addBgpNetworkId(bgpNetwork.getId)
                case u => fail(s"Unexpected update $u")
            }
            obs.getOnNextEvents.get(3) shouldBe bgpUpdate(port, Set(bgpPeer),
                                                          Set(bgpNetwork))
        }

        scenario("For BGP network added to added BGP peer") {
            Given("A router exterior port")
            val port = createExteriorPort()

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP mapper for the port")
            val mapper = new BgpMapper(port.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the port")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents.get(0) match {
                case BgpPort(p) => p shouldBeDeviceOf port
                case u => fail(s"Unexpected update $u")
            }

            When("Adding a BGP peer")
            val bgpPeer = createBgpPeer(portId = Some(port.getId),
                                        peerAs = Some(1),
                                        peerAddress = Some("10.0.0.1"))
            store.create(bgpPeer)

            Then("The observer should receive the BGP peer")
            obs.awaitOnNext(3, timeout) shouldBe true
            obs.getOnNextEvents.get(1) match {
                case BgpPort(p) => p shouldBeDeviceOf port.addBgpPeerId(bgpPeer.getId)
                case u => fail(s"Unexpected update $u")
            }
            obs.getOnNextEvents.get(2) shouldBe bgpUpdate(port, Set(bgpPeer))

            When("Adding a BGP network")
            val bgpNetwork = createBgpNetwork(portId = Some(port.getId),
                                              subnet = Some(randomIPv4Subnet))
            store.create(bgpNetwork)

            Then("The observer should receive the BGP network")
            obs.awaitOnNext(5, timeout) shouldBe true
            obs.getOnNextEvents.get(3) match {
                case BgpPort(p) => p shouldBeDeviceOf port.addBgpPeerId(bgpPeer.getId)
                    .addBgpNetworkId(bgpNetwork.getId)
                case u => fail(s"Unexpected update $u")
            }
            obs.getOnNextEvents.get(4) shouldBe bgpUpdate(port, Set(bgpPeer),
                                                          Set(bgpNetwork))
        }

        scenario("For updated BGP network") {
            Given("A router exterior port")
            val port = createExteriorPort()

            And("A BGP peer")
            val bgpPeer = createBgpPeer(portId = Some(port.getId),
                                        peerAs = Some(1),
                                        peerAddress = Some("10.0.0.1"))
            store.create(bgpPeer)

            And("A BGP network")
            val bgpNetwork1 = createBgpNetwork(portId = Some(port.getId),
                                               subnet = Some(randomIPv4Subnet))
            store.create(bgpNetwork1)

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP mapper for the port")
            val mapper = new BgpMapper(port.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the BGP network")
            obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents.get(1) shouldBe bgpUpdate(port, Set(bgpPeer),
                                                          Set(bgpNetwork1))

            When("Updating the BGP network")
            val bgpNetwork2 = bgpNetwork1.setSubnet(randomIPv4Subnet)
            store.update(bgpNetwork2)

            Then("The observer should receive the update")
            obs.awaitOnNext(3, timeout) shouldBe true
            obs.getOnNextEvents.get(2) shouldBe bgpUpdate(port, Set(bgpPeer),
                                                          Set(bgpNetwork2))
        }

        scenario("For removed BGP network") {
            Given("A router exterior port")
            val port = createExteriorPort()

            And("A BGP peer")
            val bgpPeer = createBgpPeer(portId = Some(port.getId),
                                        peerAs = Some(1),
                                        peerAddress = Some("10.0.0.1"))
            store.create(bgpPeer)

            And("A BGP network")
            val bgpNetwork = createBgpNetwork(portId = Some(port.getId),
                                              subnet = Some(randomIPv4Subnet))
            store.create(bgpNetwork)

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP mapper for the port")
            val mapper = new BgpMapper(port.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the BGP network")
            obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents.get(0) match {
                case BgpPort(p) => p shouldBeDeviceOf port.addBgpPeerId(bgpPeer.getId)
                                                          .addBgpNetworkId(bgpNetwork.getId)
                case u => fail(s"Unexpected update $u")
            }
            obs.getOnNextEvents.get(1) shouldBe bgpUpdate(port, Set(bgpPeer),
                                                          Set(bgpNetwork))

            When("Removing the BGP network")
            store.delete(classOf[BgpNetwork], bgpNetwork.getId)

            Then("The observer should receive the network removal")
            obs.awaitOnNext(4, timeout) shouldBe true
            obs.getOnNextEvents.get(2) match {
                case BgpPort(p) => p shouldBeDeviceOf port.addBgpPeerId(bgpPeer.getId)
                case u => fail(s"Unexpected update $u")
            }
            obs.getOnNextEvents.get(3) shouldBe bgpUpdate(port, Set(bgpPeer))
        }
    }

    feature("Test subscriber receives initial state") {
        scenario("There are no updates from a previous subscription") {
            Given("A router exterior port")
            val port = createExteriorPort()

            And("A BGP peer")
            val bgpPeer = createBgpPeer(portId = Some(port.getId),
                                        peerAs = Some(1),
                                        peerAddress = Some("10.0.0.1"))
            store.create(bgpPeer)

            And("A BGP network")
            val bgpNetwork = createBgpNetwork(portId = Some(port.getId),
                                              subnet = Some(randomIPv4Subnet))
            store.create(bgpNetwork)

            And("A first BGP observer")
            val obs1 = createObserver()

            When("Creating a BGP mapper for the port")
            val mapper = new BgpMapper(port.getId, vt)

            And("Subscribing to an observable on the mapper")
            val observable = Observable.create(mapper)
            observable.subscribe(obs1)

            Then("The observer should receive the BGP network")
            obs1.awaitOnNext(2, timeout) shouldBe true

            When("A second BGP observer subscribes")
            val obs2 = createObserver()
            observable.subscribe(obs2)

            Then("The observer should receive all previous updates")
            obs2.awaitOnNext(2, timeout) shouldBe true
            obs2.getOnNextEvents.get(0) match {
                case BgpPort(p) => p shouldBeDeviceOf port.addBgpPeerId(bgpPeer.getId)
                                                          .addBgpNetworkId(bgpNetwork.getId)
                case u => fail(s"Unexpected update $u")
            }
            obs2.getOnNextEvents.get(1) shouldBe bgpUpdate(port, Set(bgpPeer),
                                                           Set(bgpNetwork))
        }

        scenario("With updates from a previous subscriber") {
            Given("A router exterior port")
            val port = createExteriorPort()

            And("A BGP peer")
            val bgpPeer = createBgpPeer(portId = Some(port.getId),
                                        peerAs = Some(1),
                                        peerAddress = Some("10.0.0.1"))
            store.create(bgpPeer)

            And("A BGP network")
            val bgpNetwork1 = createBgpNetwork(portId = Some(port.getId),
                                               subnet = Some(randomIPv4Subnet))
            store.create(bgpNetwork1)

            And("A first BGP observer")
            val obs1 = createObserver()

            When("Creating a BGP mapper for the port")
            val mapper = new BgpMapper(port.getId, vt)

            And("Subscribing to an observable on the mapper")
            val observable = Observable.create(mapper)
            observable.subscribe(obs1)

            Then("The observer should receive the BGP network")
            obs1.awaitOnNext(2, timeout) shouldBe true

            When("Adding a new network to the BGP peer")
            val bgpNetwork2 = createBgpNetwork(portId = Some(port.getId),
                                               subnet = Some(randomIPv4Subnet))
            store.create(bgpNetwork2)

            Then("The observer should receive the second network")
            obs1.awaitOnNext(4, timeout) shouldBe true
            obs1.getOnNextEvents.get(2) match {
                case BgpPort(p) => p shouldBeDeviceOf port.addBgpPeerId(bgpPeer.getId)
                                                          .addBgpNetworkId(bgpNetwork1.getId)
                                                          .addBgpNetworkId(bgpNetwork2.getId)
                case u => fail(s"Unexpected update $u")
            }
            obs1.getOnNextEvents.get(3) shouldBe bgpUpdate(port, Set(bgpPeer),
                                                           Set(bgpNetwork1,
                                                               bgpNetwork2))

            When("Removing the first network")
            store.delete(classOf[BgpNetwork], bgpNetwork1.getId)

            Then("The observer should receive the network removal")
            obs1.awaitOnNext(6, timeout) shouldBe true
            obs1.getOnNextEvents.get(4) match {
                case BgpPort(p) => p shouldBeDeviceOf port.addBgpPeerId(bgpPeer.getId)
                                                          .addBgpNetworkId(bgpNetwork2.getId)
                case u => fail(s"Unexpected update $u")
            }
            obs1.getOnNextEvents.get(5) shouldBe bgpUpdate(port, Set(bgpPeer),
                                                           Set(bgpNetwork2))

            When("A second BGP observer subscribes")
            val obs2 = createObserver()
            observable.subscribe(obs2)

            Then("The observer should receive all previous updates")
            obs2.awaitOnNext(2, timeout) shouldBe true
            obs2.getOnNextEvents.get(0) match {
                case BgpPort(p) => p shouldBeDeviceOf port.addBgpPeerId(bgpPeer.getId)
                                                          .addBgpNetworkId(bgpNetwork2.getId)
                case u => fail(s"Unexpected update $u")
            }
            obs2.getOnNextEvents.get(1) shouldBe bgpUpdate(port, Set(bgpPeer),
                                                           Set(bgpNetwork2))
        }
    }
}
