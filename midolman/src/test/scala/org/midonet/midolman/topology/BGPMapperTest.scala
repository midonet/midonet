package org.midonet.midolman.topology

import java.util.UUID

import scala.concurrent.duration._

import org.junit.runner.RunWith
import org.scalatest.concurrent.Eventually
import org.scalatest.junit.JUnitRunner

import rx.Observable
import rx.observers.TestObserver

import org.midonet.cluster.data.storage.{NotFoundException, Storage}
import org.midonet.cluster.models.Topology.{Bgp, Port}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.topology.BGPMapper._
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.packets.IPv4Addr
import org.midonet.util.reactivex.{AwaitableObserver, AssertableObserver}

@RunWith(classOf[JUnitRunner])
class BGPMapperTest extends MidolmanSpec with TopologyBuilder
                    with TopologyMatchers with Eventually {

    import TopologyBuilder._

    private var store: Storage = _
    private var vt: VirtualTopology = _
    private var threadId: Long = _

    private final val timeout = 5 seconds
    private final val macTtl = 1 second
    private final val macExpiration = 3 seconds

    protected override def beforeTest(): Unit = {
        vt = injector.getInstance(classOf[VirtualTopology])
        store = injector.getInstance(classOf[MidonetBackend]).store
        threadId = Thread.currentThread.getId


    }

    private def createObserver() = {
        new TestObserver[BGPUpdate]
        with AwaitableObserver[BGPUpdate]
        with AssertableObserver[BGPUpdate] {
            override def assert() =
                BGPMapperTest.this.assert(vt.threadId == Thread.currentThread.getId)
        }
    }

    private def createPort(): Port = {
        val router = createRouter()
        val port = createRouterPort(routerId = Some(router.getId),
                                    hostId = Some(UUID.randomUUID),
                                    interfaceName = Some("iface"))
        store.create(router)
        store.create(port)
        port
    }

    feature("Test mapper lifecycle") {
        scenario("Mapper subscribes and unsubscribes") {
            Given("A router port")
            val port = createPort()

            And("A two BGP observers")
            val obs1 = createObserver()
            val obs2 = createObserver()

            When("Creating a BGP mapper for the port")
            val mapper = new BGPMapper(port.getId, vt)

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
            Given("A router port")
            val port = createPort()

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP mapper for the port")
            val mapper = new BGPMapper(port.getId, vt)

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
            Given("A router port")
            val port = createPort()

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP mapper for the port")
            val mapper = new BGPMapper(port.getId, vt)

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
            Given("A non-existant port")
            val portId = UUID.randomUUID

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP mapper for the port")
            val mapper = new BGPMapper(portId, vt)

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
            Given("A bridge port")
            val bridge = createBridge()
            val port = createBridgePort(bridgeId = Some(bridge.getId))
            store.create(bridge)
            store.create(port)

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP mapper for the port")
            val mapper = new BGPMapper(port.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive an error")
            obs.awaitCompletion(timeout)
            obs.getOnErrorEvents.get(0).getClass shouldBe classOf[DeviceMapperException]
        }

        scenario("Observer receives initial port notification") {
            Given("A router port")
            val port = createPort()

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP mapper for the port")
            val mapper = new BGPMapper(port.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the port")
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents.get(0) match {
                case BGPPort(p) => p shouldBeDeviceOf port
                case u => fail(s"Unexpected update $u")
            }
        }

        scenario("Observer receives port notification on update") {
            Given("A router port")
            val port1 = createPort()

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP mapper for the port")
            val mapper = new BGPMapper(port1.getId, vt)

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
                case BGPPort(p) => p shouldBeDeviceOf port2
                case u => fail(s"Unexpected update $u")
            }
        }
    }

    feature("Test BGP peering updates for exterior ports") {
        scenario("For existing BGP peerings") {
            Given("A router port")
            val port = createPort()

            And("A BGP peering")
            val bgp = createBGP(portId = Some(port.getId))
            store.create(bgp)

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP mapper for the port")
            val mapper = new BGPMapper(port.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the port and BGP peering")
            obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents.get(1) match {
                case BGPAdded(b) => b shouldBeDeviceOf bgp
                case u => fail(s"Unexpected update $u")
            }
        }

        scenario("When adding a BGP peering") {
            Given("A router port")
            val port = createPort()

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP mapper for the port")
            val mapper = new BGPMapper(port.getId, vt)

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
                case BGPPort(p) => p shouldBeDeviceOf port.addPortBgpId(bgp.getId)
                case u => fail(s"Unexpected update $u")
            }
            obs.getOnNextEvents.get(2) match {
                case BGPAdded(b) => b shouldBeDeviceOf bgp
                case u => fail(s"Unexpected update $u")
            }
        }

        scenario("When updating a BGP peering") {
            Given("A router port")
            val port = createPort()

            And("A BGP peering")
            val bgp1 = createBGP(portId = Some(port.getId))
            store.create(bgp1)

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP mapper for the port")
            val mapper = new BGPMapper(port.getId, vt)

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
                case BGPUpdated(b) => b shouldBeDeviceOf bgp2
                case u => fail(s"Unexpected update $u")
            }
        }

        scenario("When removing a BGP peering") {
            Given("A route port")
            val port = createPort()

            And("A BGP peering")
            val bgp = createBGP(portId = Some(port.getId))
            store.create(bgp)

            And("A BGP observer")
            val obs = createObserver()

            When("Creating a BGP mapper for the port")
            val mapper = new BGPMapper(port.getId, vt)

            And("Subscribing to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should receive the port and BGP peering")
            obs.awaitOnNext(2, timeout) shouldBe true

            When("Deleting the BGP peering")
            store.delete(classOf[Bgp], bgp.getId)

            Then("The observer should receive a BGP removal update")
            obs.awaitOnNext(4, timeout) shouldBe true
            obs.getOnNextEvents.get(2) match {
                case BGPRemoved(id) => id shouldBe bgp.getId.asJava
                case u => fail(s"Unexpected update $u")
            }
            obs.getOnNextEvents.get(3) match {
                case BGPPort(p) => p shouldBeDeviceOf port
                case u => fail(s"Unexpected update $u")
            }
        }
    }

    //feature("Test BGP peering are not advertised for interior ports") {
    //}

    //feature("Test BGP route updates") {
    //}

    //feature("Test subscriber receives initial state") {
    //}
}
