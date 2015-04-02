package org.midonet.midolman.topology

import java.util.UUID

import scala.concurrent.duration._

import org.junit.runner.RunWith
import org.scalatest.concurrent.Eventually
import org.scalatest.junit.JUnitRunner

import rx.Observable
import rx.observers.TestObserver

import org.midonet.cluster.data.storage.{ReferenceConflictException, NotFoundException, Storage}
import org.midonet.cluster.models.Topology.{Bgp, BgpRoute, Port}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.topology.BgpMapper._
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.packets.IPv4Addr
import org.midonet.util.reactivex.{AwaitableObserver, AssertableObserver}

@RunWith(classOf[JUnitRunner])
class BgpMapperTest extends MidolmanSpec with TopologyBuilder
                    with TopologyMatchers with Eventually {

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
        new TestObserver[BgpUpdate]
        with AwaitableObserver[BgpUpdate]
        with AssertableObserver[BgpUpdate] {
            override def assert() =
                BgpMapperTest.this.assert(vt.vtThreadId == Thread.currentThread.getId)
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

    feature("Test mapper lifecycle") {
        scenario("Mapper subscribes and unsubscribes") {
            Given("A router exterior port")
            val port = createExteriorPort()

            And("A two BGP observers")
            val obs1 = createObserver()
            val obs2 = createObserver()

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

            Then("The mapper should be unsubscribed")
            eventually { mapper.mapperState shouldBe State.Unsubscribed }
            eventually { mapper.hasObservers shouldBe false }
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

            Then("The mapper should be unsubscribed")
            eventually { mapper.mapperState shouldBe State.Unsubscribed }
            eventually { mapper.hasObservers shouldBe false }

            When("The observer re-subscribes to the observable")
            val sub2 = observable subscribe obs

            Then("The mapper should be subscribed")
            eventually { mapper.mapperState shouldBe State.Subscribed }
            eventually { mapper.hasObservers shouldBe true }

            And("The observer should receive a second notification")
            obs.awaitOnNext(2, timeout) shouldBe true
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

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP mapper for the port")
            val mapper = new BgpMapper(portId, vt)

            And("An observable on the mapper")
            val observable = Observable.create(mapper)

            Then("The mapper should be unsubscribed")
            eventually { mapper.mapperState shouldBe State.Unsubscribed }
            eventually { mapper.hasObservers shouldBe false }

            When("The observer subscribes to the observable")
            val sub = observable subscribe obs

            Then("The observer should receive an error")
            obs.awaitCompletion(timeout)
            obs.getOnErrorEvents.get(0).getClass shouldBe classOf[NotFoundException]
            sub.isUnsubscribed shouldBe true

            And("The mapper should be receive an error")
            mapper.mapperState shouldBe State.Error
            mapper.hasObservers shouldBe false
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

    feature("Test BGP peering updates for exterior ports") {
        scenario("For existing BGP peerings") {
            Given("A router exterior port")
            val port = createExteriorPort()

            And("A BGP peering")
            val bgp = createBGP(portId = Some(port.getId))
            store.create(bgp)

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP mapper for the port")
            val mapper = new BgpMapper(port.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the port and BGP peering")
            obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents.get(1) match {
                case BgpAdded(b) => b shouldBeDeviceOf bgp
                case u => fail(s"Unexpected update $u")
            }
        }

        scenario("When adding a BGP peering") {
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

            When("Creating a BGP peering for the port")
            val bgp = createBGP(portId = Some(port.getId))
            store.create(bgp)

            Then("The observer should receive two updates")
            obs.awaitOnNext(3, timeout) shouldBe true
            obs.getOnNextEvents.get(1) match {
                case BgpPort(p) => p shouldBeDeviceOf port.setBgpId(bgp.getId)
                case u => fail(s"Unexpected update $u")
            }
            obs.getOnNextEvents.get(2) match {
                case BgpAdded(b) => b shouldBeDeviceOf bgp
                case u => fail(s"Unexpected update $u")
            }
        }

        scenario("When updating a BGP peering") {
            Given("A router exterior port")
            val port = createExteriorPort()

            And("A BGP peering")
            val bgp1 = createBGP(portId = Some(port.getId))
            store.create(bgp1)

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP mapper for the port")
            val mapper = new BgpMapper(port.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the port and BGP peering")
            obs.awaitOnNext(2, timeout) shouldBe true

            When("Updating the BGP peering")
            val bgp2 = bgp1.setLocalAs(10)
            store.update(bgp2)

            Then("The observer should receive a BGP peering update")
            obs.awaitOnNext(3, timeout) shouldBe true
            obs.getOnNextEvents.get(2) match {
                case BgpUpdated(b) => b shouldBeDeviceOf bgp2
                case u => fail(s"Unexpected update $u")
            }
        }

        scenario("When removing a BGP peering") {
            Given("A router exterior port")
            val port = createExteriorPort()

            And("A BGP peering")
            val bgp = createBGP(portId = Some(port.getId))
            store.create(bgp)

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP mapper for the port")
            val mapper = new BgpMapper(port.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the port and BGP peering")
            obs.awaitOnNext(2, timeout) shouldBe true

            When("Deleting the BGP peering")
            store.delete(classOf[Bgp], bgp.getId)

            Then("The observer should receive a BGP removal update")
            obs.awaitOnNext(4, timeout) shouldBe true
            obs.getOnNextEvents.get(2) match {
                case BgpRemoved(id) => id shouldBe bgp.getId.asJava
                case u => fail(s"Unexpected update $u")
            }
            obs.getOnNextEvents.get(3) match {
                case BgpPort(p) => p shouldBeDeviceOf port
                case u => fail(s"Unexpected update $u")
            }
        }

        scenario("When adding/removing multiple BGP peerings") {
            Given("A router exterior port")
            val port = createExteriorPort()

            And("A BGP peering")
            val bgp1 = createBGP(portId = Some(port.getId))
            store.create(bgp1)

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP mapper for the port")
            val mapper = new BgpMapper(port.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the port and BGP peering")
            obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents.get(1) match {
                case BgpAdded(b) => b shouldBeDeviceOf bgp1
                case u => fail(s"Unexpected update $u")
            }

            And("Adding a second BGP peering should fail")
            val bgp2 = createBGP(portId = Some(port.getId))
            intercept[ReferenceConflictException] {
                store.create(bgp2)
            }
        }
    }

    feature("Test BGP peering are not advertised for interior ports") {
        scenario("For existing BGP peerings") {
            Given("A router interior port")
            val port = createInteriorPort()

            And("A BGP peering")
            val bgp = createBGP(portId = Some(port.getId))
            store.create(bgp)

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
                case BgpPort(p) => p shouldBeDeviceOf port.setBgpId(bgp.getId)
                case u => fail(s"Unexpected update $u")
            }
            obs.getOnCompletedEvents should not be empty
        }

        scenario("When adding a BGP peering") {
            Given("A router interior port")
            val port = createInteriorPort()

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP mapper for the port")
            val mapper = new BgpMapper(port.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            When("Creating a BGP peering for the port")
            val bgp = createBGP(portId = Some(port.getId))
            store.create(bgp)

            And("Waiting to receive the port update")
            obs.awaitOnNext(1, timeout) shouldBe true

            And("Deleting the port to generate another notification")
            store.delete(classOf[Port], port.getId)

            Then("The observer should receive no BGP updates")
            obs.awaitCompletion(timeout)
            obs.getOnNextEvents should have size 2
            obs.getOnNextEvents.get(0) match {
                case BgpPort(p) => p shouldBeDeviceOf port
                case u => fail(s"Unexpected update $u")
            }
            obs.getOnNextEvents.get(1) match {
                case BgpPort(p) => p shouldBeDeviceOf port.setBgpId(bgp.getId)
                case u => fail(s"Unexpected update $u")
            }
            obs.getOnCompletedEvents should not be empty
        }

        scenario("When updating a BGP peering") {
            Given("A router interior port")
            val port = createInteriorPort()

            And("A BGP peering")
            val bgp1 = createBGP(portId = Some(port.getId))
            store.create(bgp1)

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP mapper for the port")
            val mapper = new BgpMapper(port.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            And("Waiting to receive the port update")
            obs.awaitOnNext(1, timeout) shouldBe true

            When("Updating the BGP peering")
            val bgp2 = bgp1.setLocalAs(10)
            store.update(bgp2)

            And("Deleting the port to generate another notification")
            store.delete(classOf[Port], port.getId)

            Then("The observer should receive a BGP peering update")
            obs.awaitCompletion(timeout)
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) match {
                case BgpPort(p) => p shouldBeDeviceOf port.setBgpId(bgp1.getId)
                case u => fail(s"Unexpected update $u")
            }
            obs.getOnCompletedEvents should not be empty
        }

        scenario("An exterior port with BGP becomes interior") {
            Given("A router exterior port")
            val port1 = createExteriorPort()

            And("A BGP peering")
            val bgp = createBGP(portId = Some(port1.getId))
            store.create(bgp)

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP mapper for the port")
            val mapper = new BgpMapper(port1.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the port and BGP peering")
            obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents.get(1) match {
                case BgpAdded(b) => b shouldBeDeviceOf bgp
                case u => fail(s"Unexpected update $u")
            }

            When("The port becomes interior")
            val port2 = makePortInterior(port1)
            store.update(port2)

            Then("The observer should receive the port and BGP peering removed")
            obs.awaitOnNext(4, timeout) shouldBe true
            obs.getOnNextEvents.get(2) match {
                case BgpPort(p) => p shouldBeDeviceOf port2
                case u => fail(s"Unexpected update $u")
            }
            obs.getOnNextEvents.get(3) match {
                case BgpRemoved(b) => b shouldBe bgp.getId.asJava
                case u => fail(s"Unexpected update $u")
            }
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

            When("Creating a BGP peering for the port")
            val bgp = createBGP(portId = Some(port1.getId))
            store.create(bgp)

            Then("The observer should receive two updates")
            obs.awaitOnNext(3, timeout) shouldBe true
            obs.getOnNextEvents.get(2) match {
                case BgpAdded(b) => b shouldBeDeviceOf bgp
                case u => fail(s"Unexpected update $u")
            }

            When("The port becomes interior")
            val port2 = makePortInterior(port1)
            store.update(port2)

            Then("The observer should receive the port and BGP peering removed")
            obs.awaitOnNext(5, timeout) shouldBe true
            obs.getOnNextEvents.get(3) match {
                case BgpPort(p) => p shouldBeDeviceOf port2
                case u => fail(s"Unexpected update $u")
            }
            obs.getOnNextEvents.get(4) match {
                case BgpRemoved(b) => b shouldBe bgp.getId.asJava
                case u => fail(s"Unexpected update $u")
            }
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

            When("Creating a BGP peering for the port")
            val bgp = createBGP(portId = Some(port1.getId))
            store.create(bgp)

            Then("The observer should receive the port update")
            obs.awaitOnNext(3, timeout) shouldBe true
            obs.getOnNextEvents.get(2) match {
                case BgpPort(p) => p shouldBeDeviceOf port2.setBgpId(bgp.getId)
                case u => fail(s"Unexpected update $u")
            }

            When("Deleting the port to generate another notification")
            store.delete(classOf[Port], port2.getId)

            Then("The observer should receive the port removed")
            obs.awaitCompletion(timeout)
            obs.getOnNextEvents should have size 3
            obs.getOnCompletedEvents should not be empty
        }

        scenario("An interior port with BGP becomes exterior") {
            Given("A router interior port")
            val port1 = createInteriorPort()

            And("A BGP peering")
            val bgp = createBGP(portId = Some(port1.getId))
            store.create(bgp)

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP mapper for the port")
            val mapper = new BgpMapper(port1.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            And("Waiting to receive the port update")
            obs.awaitOnNext(1, timeout) shouldBe true

            When("The port becomes exterior")
            val port2 = makePortExterior(port1).setBgpId(bgp.getId)
            store.update(port2)

            Then("The observer should receive the port and BGP updates")
            obs.awaitOnNext(3, timeout) shouldBe true
            obs.getOnNextEvents.get(1) match {
                case BgpPort(p) => p shouldBeDeviceOf port2
                case u => fail(s"Unexpected update $u")
            }
            obs.getOnNextEvents.get(2) match {
                case BgpAdded(b) => b shouldBeDeviceOf bgp
                case u => fail(s"Unexpected updated $u")
            }
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

            When("Creating a BGP peering for the port")
            val bgp = createBGP(portId = Some(port1.getId))
            store.create(bgp)

            And("Waiting to receive the port update")
            obs.awaitOnNext(2, timeout) shouldBe true

            When("The port becomes exterior")
            val port2 = makePortExterior(port1).setBgpId(bgp.getId)
            store.update(port2)

            Then("The observer should receive the port and BGP updates")
            obs.awaitOnNext(4, timeout) shouldBe true
            obs.getOnNextEvents.get(2) match {
                case BgpPort(p) => p shouldBeDeviceOf port2
                case u => fail(s"Unexpected update $u")
            }
            obs.getOnNextEvents.get(3) match {
                case BgpAdded(b) => b shouldBeDeviceOf bgp
                case u => fail(s"Unexpected updated $u")
            }
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
            obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents.get(1) match {
                case BgpPort(p) => p shouldBeDeviceOf port2
                case u => fail(s"Unexpected update $u")
            }

            When("Creating a BGP peering for the port")
            val bgp = createBGP(portId = Some(port1.getId))
            store.create(bgp)

            Then("The observer should receove the BGP update")
            obs.awaitOnNext(4, timeout) shouldBe true
            obs.getOnNextEvents.get(2) match {
                case BgpPort(p) => p shouldBeDeviceOf port2.setBgpId(bgp.getId)
                case u => fail(s"Unexpected update $u")
            }
            obs.getOnNextEvents.get(3) match {
                case BgpAdded(b) => b shouldBeDeviceOf bgp
                case u => fail(s"Unexpected updated $u")
            }
        }
    }

    feature("Test BGP route updates") {
        scenario("For existing BGP route") {
            Given("A router exterior port")
            val port = createExteriorPort()

            And("A BGP peering")
            val bgp = createBGP(portId = Some(port.getId))
            store.create(bgp)

            And("A BGP route")
            val route = createBGPRoute(subnet = Some(randomIPv4Subnet),
                                       bgpId = Some(bgp.getId))
            store.create(route)

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP mapper for the port")
            val mapper = new BgpMapper(port.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the BGP route")
            obs.awaitOnNext(3, timeout) shouldBe true
            obs.getOnNextEvents.get(2) match {
                case BgpRouteAdded(r) => r shouldBeDeviceOf route
                case u => fail(s"Unexpected update $u")
            }
        }

        scenario("For added BGP route to existing BGP peering") {
            Given("A router exterior port")
            val port = createExteriorPort()

            And("A BGP peering")
            val bgp = createBGP(portId = Some(port.getId))
            store.create(bgp)

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP mapper for the port")
            val mapper = new BgpMapper(port.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the BGP peering")
            obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents.get(1) match {
                case BgpAdded(b) => b shouldBeDeviceOf bgp
                case u => fail(s"Unexpected update $u")
            }

            When("Adding a BGP route")
            val route = createBGPRoute(subnet = Some(randomIPv4Subnet),
                                       bgpId = Some(bgp.getId))
            store.create(route)

            Then("The observer should receive the BGP route")
            obs.awaitOnNext(4, timeout) shouldBe true
            obs.getOnNextEvents.get(2) match {
                case BgpUpdated(b) => b shouldBeDeviceOf bgp.addBgpRouteId(route.getId)
                case u => fail(s"Unexpected update $u")
            }
            obs.getOnNextEvents.get(3) match {
                case BgpRouteAdded(r) => r shouldBeDeviceOf route
                case u => fail(s"Unexpected update $u")
            }
        }

        scenario("For BGP route added to added BGP peering") {
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

            When("Adding a BGP peering")
            val bgp = createBGP(portId = Some(port.getId))
            store.create(bgp)

            Then("The observer should receive the BGP peering")
            obs.awaitOnNext(3, timeout) shouldBe true
            obs.getOnNextEvents.get(1) match {
                case BgpPort(p) => p shouldBeDeviceOf port.setBgpId(bgp.getId)
                case u => fail(s"Unexpected update $u")
            }
            obs.getOnNextEvents.get(2) match {
                case BgpAdded(b) => b shouldBeDeviceOf bgp
                case u => fail(s"Unexpected update $u")
            }

            When("Adding a BGP route")
            val route = createBGPRoute(subnet = Some(randomIPv4Subnet),
                                       bgpId = Some(bgp.getId))
            store.create(route)

            Then("The observer should receive the BGP route")
            obs.awaitOnNext(5, timeout) shouldBe true
            obs.getOnNextEvents.get(3) match {
                case BgpUpdated(b) => b shouldBeDeviceOf bgp.addBgpRouteId(route.getId)
                case u => fail(s"Unexpected update $u")
            }
            obs.getOnNextEvents.get(4) match {
                case BgpRouteAdded(r) => r shouldBeDeviceOf route
                case u => fail(s"Unexpected update $u")
            }
        }

        scenario("For updated BGP route") {
            Given("A router exterior port")
            val port = createExteriorPort()

            And("A BGP peering")
            val bgp = createBGP(portId = Some(port.getId))
            store.create(bgp)

            And("A BGP route")
            val route1 = createBGPRoute(subnet = Some(randomIPv4Subnet),
                                        bgpId = Some(bgp.getId))
            store.create(route1)

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP mapper for the port")
            val mapper = new BgpMapper(port.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the BGP route")
            obs.awaitOnNext(3, timeout) shouldBe true

            When("Updating the BGP route")
            val route2 = route1.setSubnet(randomIPv4Subnet)
            store.update(route2)

            Then("The observer should receive the update")
            obs.awaitOnNext(5, timeout) shouldBe true
            obs.getOnNextEvents.get(3) match {
                case BgpRouteRemoved(r) => r shouldBeDeviceOf route1
                case u => fail(s"Unexpected update $u")
            }
            obs.getOnNextEvents.get(4) match {
                case BgpRouteAdded(r) => r shouldBeDeviceOf route2
                case u => fail(s"Unexpected update $u")
            }
        }

        scenario("For removed BGP route") {
            Given("A router exterior port")
            val port = createExteriorPort()

            And("A BGP peering")
            val bgp = createBGP(portId = Some(port.getId))
            store.create(bgp)

            And("A BGP route")
            val route = createBGPRoute(subnet = Some(randomIPv4Subnet),
                                       bgpId = Some(bgp.getId))
            store.create(route)

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP mapper for the port")
            val mapper = new BgpMapper(port.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the BGP route")
            obs.awaitOnNext(3, timeout) shouldBe true

            When("Removing the BGP route")
            store.delete(classOf[BgpRoute], route.getId)

            Then("The observer should receive the route removal")
            obs.awaitOnNext(5, timeout) shouldBe true
            obs.getOnNextEvents.get(3) match {
                case BgpRouteRemoved(r) => r shouldBeDeviceOf route
                case u => fail(s"Unexpected update $u")
            }
            obs.getOnNextEvents.get(4) match {
                case BgpUpdated(b) => b shouldBeDeviceOf bgp
                case u => fail(s"Unexpected update $u")
            }
        }

        scenario("Route removed when BGP peering is removed") {
            Given("A router exterior port")
            val port = createExteriorPort()

            And("A BGP peering")
            val bgp = createBGP(portId = Some(port.getId))
            store.create(bgp)

            And("A BGP route")
            val route = createBGPRoute(subnet = Some(randomIPv4Subnet),
                                       bgpId = Some(bgp.getId))
            store.create(route)

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP mapper for the port")
            val mapper = new BgpMapper(port.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the BGP route")
            obs.awaitOnNext(3, timeout) shouldBe true

            When("The BGP peering is removed")
            store.delete(classOf[Bgp], bgp.getId)

            Then("The observer should receive the BGP peering and route removal")
            obs.awaitOnNext(6, timeout) shouldBe true
            obs.getOnNextEvents.get(3) match {
                case BgpRouteRemoved(r) => r shouldBeDeviceOf route
                case u => fail(s"Unexpected update $u")
            }
            obs.getOnNextEvents.get(4) match {
                case BgpRemoved(id) => id shouldBe bgp.getId.asJava
                case u => fail(s"Unexpected update $u")
            }
            obs.getOnNextEvents.get(5) match {
                case BgpPort(p) => p shouldBeDeviceOf port
                case u => fail(s"Unexpected update $u")
            }
        }
    }

    feature("Test subscriber receives initial state") {
        scenario("There are no updates from a previous subscription") {
            Given("A router exterior port")
            val port = createExteriorPort()

            And("A BGP peering")
            val bgp = createBGP(portId = Some(port.getId))
            store.create(bgp)

            And("A BGP route")
            val route = createBGPRoute(subnet = Some(randomIPv4Subnet),
                                       bgpId = Some(bgp.getId))
            store.create(route)

            And("A first BGP observer")
            val obs1 = createObserver()

            When("Creating a BGP mapper for the port")
            val mapper = new BgpMapper(port.getId, vt)

            And("Subscribing to an observable on the mapper")
            val observable = Observable.create(mapper)
            observable.subscribe(obs1)

            Then("The observer should receive the BGP route")
            obs1.awaitOnNext(3, timeout) shouldBe true

            When("A second BGP observer subscribes")
            val obs2 = createObserver()
            observable.subscribe(obs2)

            Then("The observer should receive all previous updates")
            obs2.awaitOnNext(3, timeout) shouldBe true
            obs2.getOnNextEvents.get(0) match {
                case BgpPort(p) => p shouldBeDeviceOf port.setBgpId(bgp.getId)
                case u => fail(s"Unexpected update $u")
            }
            obs2.getOnNextEvents.get(1) match {
                case BgpAdded(b) => b shouldBeDeviceOf bgp.addBgpRouteId(route.getId)
                case u => fail(s"Unexpected update $u")
            }
            obs2.getOnNextEvents.get(2) match {
                case BgpRouteAdded(r) => r shouldBeDeviceOf route
                case u => fail(s"Unexpected update $u")
            }
        }

        scenario("With updates from a previous subscriber") {
            Given("A router exterior port")
            val port = createExteriorPort()

            And("A BGP peering")
            val bgp = createBGP(portId = Some(port.getId))
            store.create(bgp)

            And("A BGP route")
            val route1 = createBGPRoute(subnet = Some(randomIPv4Subnet),
                                        bgpId = Some(bgp.getId))
            store.create(route1)

            And("A first BGP observer")
            val obs1 = createObserver()

            When("Creating a BGP mapper for the port")
            val mapper = new BgpMapper(port.getId, vt)

            And("Subscribing to an observable on the mapper")
            val observable = Observable.create(mapper)
            observable.subscribe(obs1)

            Then("The observer should receive the BGP route")
            obs1.awaitOnNext(3, timeout) shouldBe true

            When("Adding a new route to the BGP peering")
            val route2 = createBGPRoute(subnet = Some(randomIPv4Subnet),
                                        bgpId = Some(bgp.getId))
            store.create(route2)

            Then("The observer should receive the second route")
            obs1.awaitOnNext(5, timeout) shouldBe true
            obs1.getOnNextEvents.get(4) match {
                case BgpRouteAdded(r) => r shouldBeDeviceOf route2
                case u => fail(s"Unexpected update $u")
            }

            When("Removing the first route")
            store.delete(classOf[BgpRoute], route1.getId)

            Then("The observer should receive the route removal")
            obs1.awaitOnNext(7, timeout) shouldBe true
            obs1.getOnNextEvents.get(5) match {
                case BgpRouteRemoved(r) => r shouldBeDeviceOf route1
                case u => fail(s"Unexpected update $u")
            }

            When("A second BGP observer subscribes")
            val obs2 = createObserver()
            observable.subscribe(obs2)

            Then("The observer should receive all previous updates")
            obs2.awaitOnNext(3, timeout) shouldBe true
            obs2.getOnNextEvents.get(0) match {
                case BgpPort(p) => p shouldBeDeviceOf port.setBgpId(bgp.getId)
                case u => fail(s"Unexpected update $u")
            }
            obs2.getOnNextEvents.get(1) match {
                case BgpAdded(b) => b shouldBeDeviceOf bgp.addBgpRouteId(route2.getId)
                case u => fail(s"Unexpected update $u")
            }
            obs2.getOnNextEvents.get(2) match {
                case BgpRouteAdded(r) => r shouldBeDeviceOf route2
                case u => fail(s"Unexpected update $u")
            }
        }
    }
}
