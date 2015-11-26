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

import java.util.UUID.randomUUID

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import rx.Observable
import rx.observers.TestObserver

import org.midonet.cluster.data.storage._
import org.midonet.cluster.models.Topology.{ServiceContainer, Port, Host}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.topology.{TopologyMatchers, TopologyBuilder}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.topology.ContainerMapper.{Changed, Deleted, Created, Notification}
import org.midonet.midolman.topology.DeviceMapper.MapperState
import org.midonet.midolman.topology.containers.ContainerPort
import org.midonet.midolman.util.MidolmanSpec

@RunWith(classOf[JUnitRunner])
class ContainerMapperTest extends MidolmanSpec with TopologyBuilder
                          with TopologyMatchers {

    import TopologyBuilder._

    private var store: Storage = _
    private var backdoor: InMemoryStorage = _
    private var vt: VirtualTopology = _

    protected override def beforeTest(): Unit = {
        vt = injector.getInstance(classOf[VirtualTopology])
        store = injector.getInstance(classOf[MidonetBackend]).store
        backdoor = injector.getInstance(classOf[MidonetBackend]).store
            .asInstanceOf[InMemoryStorage]
    }

    private def createdFor(host: Host, port: Port, container: ServiceContainer)
    : Notification = {
        Created(ContainerPort(port.getId, host.getId,
                              port.getInterfaceName,
                              container.getId,
                              container.getConfigurationId))
    }

    private def changedFor(host: Host, port: Port, container: ServiceContainer)
    : Notification = {
        Changed(ContainerPort(port.getId, host.getId,
                              port.getInterfaceName,
                              container.getId,
                              container.getConfigurationId))
    }

    private def deletedFor(host: Host, port: Port, container: ServiceContainer)
    : Notification = {
        Deleted(ContainerPort(port.getId, host.getId,
                              port.getInterfaceName,
                              container.getId,
                              container.getConfigurationId))
    }

    feature("Test mapper lifecyle") {
        scenario("Mapper subscribes and unsubscribes") {
            Given("A port bound to a host with a container")
            val host = createHost()
            val bridge = createBridge()
            val container = createServiceContainer(configurationId = Some(randomUUID))
            val port = createBridgePort(bridgeId = Some(bridge.getId),
                                        hostId = Some(host.getId),
                                        interfaceName = Some("veth"),
                                        containerId = Some(container.getId))
            store.multi(Seq(CreateOp(host), CreateOp(bridge),
                            CreateOp(container), CreateOp(port)))

            And("Three container observers")
            val obs1 = new TestObserver[Notification]
            val obs2 = new TestObserver[Notification]
            val obs3 = new TestObserver[Notification]

            When("Create a container mapper for the host")
            val mapper = new ContainerMapper(host.getId, vt)

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
            obs1.getOnNextEvents should have size 1

            When("The second observer subscribes to the observable")
            val sub2 = observable subscribe obs2

            Then("The mapper should be subscribed")
            mapper.mapperState shouldBe MapperState.Subscribed
            mapper.hasObservers shouldBe true

            And("The observer should receive a notification")
            obs2.getOnNextEvents should have size 1

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
            obs3.getOnErrorEvents should have size 1
            obs3.getOnErrorEvents.get(0) shouldBe DeviceMapper.MapperClosedException
        }

        scenario("Mapper re-subscribes") {
            Given("A port bound to a host with a container")
            val host = createHost()
            val bridge = createBridge()
            val container = createServiceContainer(configurationId = Some(randomUUID))
            val port = createBridgePort(bridgeId = Some(bridge.getId),
                                        hostId = Some(host.getId),
                                        interfaceName = Some("veth"),
                                        containerId = Some(container.getId))
            store.multi(Seq(CreateOp(host), CreateOp(bridge),
                            CreateOp(container), CreateOp(port)))

            And("A container observer")
            val obs = new TestObserver[Notification]

            When("Create a container mapper for the host")
            val mapper = new ContainerMapper(host.getId, vt)

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
            obs.getOnNextEvents should have size 1

            When("The observer unsubscribes")
            sub1.unsubscribe()

            Then("The mapper should be closed")
            mapper.mapperState shouldBe MapperState.Closed
            mapper.hasObservers shouldBe false

            When("The observer re-subscribes to the observable")
            observable subscribe obs

            Then("The observer should receive an error")
            obs.getOnErrorEvents should have size 1
            obs.getOnErrorEvents.get(0) shouldBe DeviceMapper.MapperClosedException
        }

        scenario("Mapper terminates when the host is deleted") {
            Given("A port bound to a host with a container")
            val host = createHost()
            val bridge = createBridge()
            val container = createServiceContainer(configurationId = Some(randomUUID))
            val port = createBridgePort(bridgeId = Some(bridge.getId),
                                        hostId = Some(host.getId),
                                        interfaceName = Some("veth"),
                                        containerId = Some(container.getId))
            store.multi(Seq(CreateOp(host), CreateOp(bridge),
                            CreateOp(container), CreateOp(port)))

            And("A container observer")
            val obs = new TestObserver[Notification]

            When("Create a container mapper for the host")
            val mapper = new ContainerMapper(host.getId, vt)

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
            obs.getOnNextEvents should have size 1

            When("The host is deleted")
            store.delete(classOf[Host], host.getId)

            Then("The observer should receive a port deleted notification")
            obs.getOnCompletedEvents should have size 1
            sub.isUnsubscribed shouldBe true

            And("The mapper should be completed")
            mapper.mapperState shouldBe MapperState.Completed
            mapper.hasObservers shouldBe false
        }

        scenario("Mapper terminates with error") {
            Given("A non-existent host")
            val hostId = randomUUID

            And("Two container observers")
            val obs1 = new TestObserver[Notification]
            val obs2 = new TestObserver[Notification]

            When("Create a container mapper for the host")
            val mapper = new ContainerMapper(hostId, vt)

            And("An observable on the mapper")
            val observable = Observable.create(mapper)

            Then("The mapper should be unsubscribed")
            mapper.mapperState shouldBe MapperState.Unsubscribed
            mapper.hasObservers shouldBe false

            When("The observer subscribes to the observable")
            val sub1 = observable subscribe obs1

            Then("The observer should receive an error")
            obs1.getOnErrorEvents should have size 1
            obs1.getOnErrorEvents.get(0) match {
                case e: NotFoundException =>
                    e.id shouldBe hostId
                    e.clazz shouldBe classOf[Host]
                case _ => fail("Unexpected error")
            }
            sub1.isUnsubscribed shouldBe true

            And("The mapper should be receive an error")
            mapper.mapperState shouldBe MapperState.Error
            mapper.hasObservers shouldBe false

            When("A second observer subscribes")
            val sub2 = observable subscribe obs2

            Then("The observer should receive an error")
            obs2.getOnErrorEvents should have size 1
            obs2.getOnErrorEvents.get(0) match {
                case e: NotFoundException =>
                    e.id shouldBe hostId
                    e.clazz shouldBe classOf[Host]
                case _ => fail("Unexpected error")
            }
            sub2.isUnsubscribed shouldBe true
        }

        scenario("Mapper emits completed") {
            Given("A host")
            val host = createHost()
            val bridge = createBridge()
            store.multi(Seq(CreateOp(host), CreateOp(bridge)))

            And("Two container observers")
            val obs1 = new TestObserver[Notification]
            val obs2 = new TestObserver[Notification]

            When("Create a container mapper for the host")
            val mapper = new ContainerMapper(host.getId, vt)

            And("An observable on the mapper")
            val observable = Observable.create(mapper)

            When("The the first observer subscribes")
            val sub1 = observable subscribe obs1

            And("The host is deleted")
            store.delete(classOf[Host], host.getId)

            Then("The observer receives completed")
            obs1.getOnCompletedEvents should have size 1
            sub1.isUnsubscribed shouldBe true

            And("The mapper should be completed")
            mapper.mapperState shouldBe MapperState.Completed

            When("The second observer subscribes")
            val sub2 = observable subscribe obs2

            Then("The observer receives completed")
            obs2.getOnCompletedEvents should have size 1
            sub2.isUnsubscribed shouldBe true
        }
    }

    feature("Test mapper host updates") {
        scenario("Host with no bindings") {
            Given("A host with no bindings")
            val host1 = createHost()
            store.create(host1)

            And("A container observer")
            val obs = new TestObserver[Notification]

            When("Create a container mapper for the host")
            val mapper = new ContainerMapper(host1.getId, vt)

            And("An observable on the mapper")
            val observable = Observable.create(mapper)

            When("The observer subscribes to the observable")
            val sub = observable subscribe obs

            Then("The observer should not receive a notification")
            obs.getOnNextEvents shouldBe empty

            When("The host is updated")
            val host2 = host1.setName("some-name")
            store.update(host2)

            Then("The observer should not receive a notification")
            obs.getOnNextEvents shouldBe empty

            When("The host is deleted")
            store.delete(classOf[Host], host1.getId)

            Then("The observer should receive a completed notification")
            obs.getOnCompletedEvents should have size 1
            sub.isUnsubscribed shouldBe true
        }
    }

    feature("Test mapper port bindings updates") {
        scenario("Binding without container") {
            Given("A port bound to a host without container")
            val host = createHost()
            val bridge = createBridge()
            val port1 = createBridgePort(bridgeId = Some(bridge.getId),
                                         hostId = Some(host.getId),
                                         interfaceName = Some("veth1"))
            store.multi(Seq(CreateOp(host), CreateOp(bridge), CreateOp(port1)))

            And("A container observer")
            val obs = new TestObserver[Notification]

            When("Create a container mapper for the host")
            val mapper = new ContainerMapper(host.getId, vt)

            And("An observable on the mapper")
            val observable = Observable.create(mapper)

            When("The observer subscribes to the observable")
            val sub = observable subscribe obs

            Then("The observer should not receive a notification")
            obs.getOnNextEvents shouldBe empty

            When("The port binding is updated")
            val port2 = port1.setInterfaceName("veth2")
            store.update(port2)

            Then("The observer should not receive a notification")
            obs.getOnNextEvents shouldBe empty

            When("The port binding is deleted")
            store.delete(classOf[Port], port1.getId)

            Then("The observer should not receive a notification")
            obs.getOnNextEvents shouldBe empty

            When("The host is deleted")
            store.delete(classOf[Host], host.getId)

            Then("The observer should receive a completed notification")
            obs.getOnCompletedEvents should have size 1
            sub.isUnsubscribed shouldBe true
        }

        scenario("Existing binding with a container") {
            Given("A port bound to a host with a container")
            val host = createHost()
            val bridge = createBridge()
            val container = createServiceContainer(configurationId = Some(randomUUID))
            val port = createBridgePort(bridgeId = Some(bridge.getId),
                                        hostId = Some(host.getId),
                                        interfaceName = Some("veth"),
                                        containerId = Some(container.getId))
            store.multi(Seq(CreateOp(host), CreateOp(bridge),
                            CreateOp(container), CreateOp(port)))

            And("A container observer")
            val obs = new TestObserver[Notification]

            When("Create a container mapper for the host")
            val mapper = new ContainerMapper(host.getId, vt)

            And("An observable on the mapper")
            val observable = Observable.create(mapper)

            When("The observer subscribes to the observable")
            observable subscribe obs

            Then("The observer should receive a Created notification")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBe createdFor(host, port, container)
        }

        scenario("Adding a binding with a container") {
            Given("A host")
            val host = createHost()
            val bridge = createBridge()
            store.multi(Seq(CreateOp(host), CreateOp(bridge)))

            And("A container observer")
            val obs = new TestObserver[Notification]

            When("Create a container mapper for the host")
            val mapper = new ContainerMapper(host.getId, vt)

            And("An observable on the mapper")
            val observable = Observable.create(mapper)

            When("The observer subscribes to the observable")
            val sub = observable subscribe obs

            Then("The observer should not receive a notification")
            obs.getOnNextEvents shouldBe empty

            When("Adding a binding with a container")
            val container = createServiceContainer(configurationId = Some(randomUUID))
            val port = createBridgePort(bridgeId = Some(bridge.getId),
                                        hostId = Some(host.getId),
                                        interfaceName = Some("veth"),
                                        containerId = Some(container.getId))
            store.multi(Seq(CreateOp(container), CreateOp(port)))

            Then("The observer should receive a Created notification")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBe createdFor(host, port, container)
        }

        scenario("Adding a container to an existing binding") {
            Given("A port bound to a host without container")
            val host = createHost()
            val bridge = createBridge()
            val port1 = createBridgePort(bridgeId = Some(bridge.getId),
                                         hostId = Some(host.getId),
                                         interfaceName = Some("veth"))
            store.multi(Seq(CreateOp(host), CreateOp(bridge), CreateOp(port1)))

            And("A container observer")
            val obs = new TestObserver[Notification]

            When("Create a container mapper for the host")
            val mapper = new ContainerMapper(host.getId, vt)

            And("An observable on the mapper")
            val observable = Observable.create(mapper)

            When("The observer subscribes to the observable")
            observable subscribe obs

            Then("The observer should not receive a notification")
            obs.getOnNextEvents shouldBe empty

            When("Adding a container to the binding")
            val container = createServiceContainer(configurationId = Some(randomUUID))
            val port2 = port1.setContainerId(container.getId)
            store.multi(Seq(CreateOp(container), UpdateOp(port2)))

            Then("The observer should receive a Created notification")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBe createdFor(host, port2, container)
        }

        scenario("Removing a binding without a container") {
            Given("A port bound to a host without container")
            val host = createHost()
            val bridge = createBridge()
            val port = createBridgePort(bridgeId = Some(bridge.getId),
                                        hostId = Some(host.getId),
                                        interfaceName = Some("veth"))
            store.multi(Seq(CreateOp(host), CreateOp(bridge), CreateOp(port)))

            And("A container observer")
            val obs = new TestObserver[Notification]

            When("Create a container mapper for the host")
            val mapper = new ContainerMapper(host.getId, vt)

            And("An observable on the mapper")
            val observable = Observable.create(mapper)

            When("The observer subscribes to the observable")
            observable subscribe obs

            Then("The observer should not receive a notification")
            obs.getOnNextEvents shouldBe empty

            When("Removing the binding")
            store.delete(classOf[Port], port.getId)

            Then("The observer should not receive a notification")
            obs.getOnNextEvents shouldBe empty
        }

        scenario("Removing a binding with a container") {
            Given("A port bound to a host with a container")
            val host = createHost()
            val bridge = createBridge()
            val container = createServiceContainer(configurationId = Some(randomUUID))
            val port = createBridgePort(bridgeId = Some(bridge.getId),
                                        hostId = Some(host.getId),
                                        interfaceName = Some("veth"),
                                        containerId = Some(container.getId))
            store.multi(Seq(CreateOp(host), CreateOp(bridge),
                            CreateOp(container), CreateOp(port)))

            And("A container observer")
            val obs = new TestObserver[Notification]

            When("Create a container mapper for the host")
            val mapper = new ContainerMapper(host.getId, vt)

            And("An observable on the mapper")
            val observable = Observable.create(mapper)

            When("The observer subscribes to the observable")
            observable subscribe obs

            Then("The observer should receive a Created notification")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBe createdFor(host, port, container)

            When("Removing the binding")
            store.delete(classOf[Port], port.getId)

            Then("The observer should receive a Deleted notification")
            obs.getOnNextEvents should have size 2
            obs.getOnNextEvents.get(1) shouldBe deletedFor(host, port, container)
        }

        scenario("Removing a container from a binding") {
            Given("A port bound to a host with a container")
            val host = createHost()
            val bridge = createBridge()
            val container = createServiceContainer(configurationId = Some(randomUUID))
            val port = createBridgePort(bridgeId = Some(bridge.getId),
                                        hostId = Some(host.getId),
                                        interfaceName = Some("veth"),
                                        containerId = Some(container.getId))
            store.multi(Seq(CreateOp(host), CreateOp(bridge),
                            CreateOp(container), CreateOp(port)))

            And("A container observer")
            val obs = new TestObserver[Notification]

            When("Create a container mapper for the host")
            val mapper = new ContainerMapper(host.getId, vt)

            And("An observable on the mapper")
            val observable = Observable.create(mapper)

            When("The observer subscribes to the observable")
            observable subscribe obs

            Then("The observer should receive a Created notification")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBe createdFor(host, port, container)

            When("Removing the container from binding")
            store.delete(classOf[ServiceContainer], container.getId)

            Then("The observer should receive a Deleted notification")
            obs.getOnNextEvents should have size 2
            obs.getOnNextEvents.get(1) shouldBe deletedFor(host, port, container)
        }
    }

    feature("Test mapper container updates") {
        scenario("Updating the container from a binding") {
            Given("A port bound to a host with a container")
            val host = createHost()
            val bridge = createBridge()
            val container1 = createServiceContainer(configurationId = Some(randomUUID))
            val port1 = createBridgePort(bridgeId = Some(bridge.getId),
                                         hostId = Some(host.getId),
                                         interfaceName = Some("veth"),
                                         containerId = Some(container1.getId))
            store.multi(Seq(CreateOp(host), CreateOp(bridge),
                            CreateOp(container1), CreateOp(port1)))

            And("A container observer")
            val obs = new TestObserver[Notification]

            When("Create a container mapper for the host")
            val mapper = new ContainerMapper(host.getId, vt)

            And("An observable on the mapper")
            val observable = Observable.create(mapper)

            When("The observer subscribes to the observable")
            observable subscribe obs

            Then("The observer should receive a Created notification")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBe createdFor(host, port1, container1)

            When("Updating the binding container")
            val container2 = createServiceContainer(configurationId = Some(randomUUID))
            val port2 = port1.setContainerId(container2.getId)
            store.multi(Seq(CreateOp(container2), UpdateOp(port2)))

            Then("The observer should receive a Deleted and Created notification")
            obs.getOnNextEvents should have size 3
            obs.getOnNextEvents.get(1) shouldBe deletedFor(host, port1, container1)
            obs.getOnNextEvents.get(2) shouldBe createdFor(host, port2, container2)
        }

        scenario("Updating the container configuration") {
            Given("A port bound to a host with a container")
            val host = createHost()
            val bridge = createBridge()
            val container1 = createServiceContainer(configurationId = Some(randomUUID))
            val port = createBridgePort(bridgeId = Some(bridge.getId),
                                        hostId = Some(host.getId),
                                        interfaceName = Some("veth"),
                                        containerId = Some(container1.getId))
            store.multi(Seq(CreateOp(host), CreateOp(bridge),
                            CreateOp(container1), CreateOp(port)))

            And("A container observer")
            val obs = new TestObserver[Notification]

            When("Create a container mapper for the host")
            val mapper = new ContainerMapper(host.getId, vt)

            And("An observable on the mapper")
            val observable = Observable.create(mapper)

            When("The observer subscribes to the observable")
            observable subscribe obs

            Then("The observer should receive a Created notification")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBe createdFor(host, port, container1)

            When("Updating the container configuration")
            val container2 = container1.setConfigurationId(randomUUID)
                                       .setPortId(port.getId)
            store.update(container2)

            Then("The observer should receive a Changed notification")
            obs.getOnNextEvents should have size 2
            obs.getOnNextEvents.get(1) shouldBe changedFor(host, port, container2)
        }
    }

    feature("Test mapper notifies initial state to new subscribers") {
        scenario("Initial state for bindings without container") {
            Given("Two ports bound to a host without container")
            val host = createHost()
            val bridge = createBridge()
            val port1 = createBridgePort(bridgeId = Some(bridge.getId),
                                         hostId = Some(host.getId),
                                         interfaceName = Some("veth1"))
            val port2 = createBridgePort(bridgeId = Some(bridge.getId),
                                         hostId = Some(host.getId),
                                         interfaceName = Some("veth2"))
            store.multi(Seq(CreateOp(host), CreateOp(bridge), CreateOp(port1),
                            CreateOp(port2)))

            And("Two container observers")
            val obs1 = new TestObserver[Notification]
            val obs2 = new TestObserver[Notification]

            When("Create a container mapper for the host")
            val mapper = new ContainerMapper(host.getId, vt)

            And("An observable on the mapper")
            val observable = Observable.create(mapper)

            When("The first observer subscribes to the observable")
            observable subscribe obs1

            Then("The first observer does not receive any notification")
            obs1.getOnNextEvents shouldBe empty

            When("The second observer subscribes to the observable")
            observable subscribe obs2

            Then("The second observer does not receive any notification")
            obs2.getOnNextEvents shouldBe empty
        }

        scenario("Initial state for one binding with container") {
            Given("A port bound to a host with a container")
            val host = createHost()
            val bridge = createBridge()
            val container = createServiceContainer(configurationId = Some(randomUUID))
            val port = createBridgePort(bridgeId = Some(bridge.getId),
                                        hostId = Some(host.getId),
                                        interfaceName = Some("veth"),
                                        containerId = Some(container.getId))
            store.multi(Seq(CreateOp(host), CreateOp(bridge),
                            CreateOp(container), CreateOp(port)))

            And("Two container observers")
            val obs1 = new TestObserver[Notification]
            val obs2 = new TestObserver[Notification]

            When("Create a container mapper for the host")
            val mapper = new ContainerMapper(host.getId, vt)

            And("An observable on the mapper")
            val observable = Observable.create(mapper)

            When("The first observer subscribes to the observable")
            observable subscribe obs1

            Then("The first observer receives the container")
            obs1.getOnNextEvents should have size 1
            obs1.getOnNextEvents.get(0) shouldBe createdFor(host, port, container)

            When("The second observer subscribes to the observable")
            observable subscribe obs2

            Then("The second observer receives the container")
            obs2.getOnNextEvents should have size 1
            obs2.getOnNextEvents.get(0) shouldBe createdFor(host, port, container)
        }

        scenario("Initial state for multiple bindings with containers") {
            Given("Two ports bound to a host with a container")
            val host = createHost()
            val bridge = createBridge()
            val container1 = createServiceContainer(configurationId = Some(randomUUID))
            val container2 = createServiceContainer(configurationId = Some(randomUUID))
            val port1 = createBridgePort(bridgeId = Some(bridge.getId),
                                         hostId = Some(host.getId),
                                         interfaceName = Some("veth1"),
                                         containerId = Some(container1.getId))
            val port2 = createBridgePort(bridgeId = Some(bridge.getId),
                                         hostId = Some(host.getId),
                                         interfaceName = Some("veth2"),
                                         containerId = Some(container2.getId))
            store.multi(Seq(CreateOp(host), CreateOp(bridge),
                            CreateOp(container1), CreateOp(container2),
                            CreateOp(port1), CreateOp(port2)))

            And("Two container observers")
            val obs1 = new TestObserver[Notification]
            val obs2 = new TestObserver[Notification]

            When("Create a container mapper for the host")
            val mapper = new ContainerMapper(host.getId, vt)

            And("An observable on the mapper")
            val observable = Observable.create(mapper)

            When("The first observer subscribes to the observable")
            observable subscribe obs1

            Then("The first observer receives both containers")
            obs1.getOnNextEvents should have size 2
            obs1.getOnNextEvents should contain allOf(createdFor(host, port1, container1),
                                                      createdFor(host, port2, container2))

            When("The second observer subscribes to the observable")
            observable subscribe obs2

            Then("The second observer receives both containers")
            obs2.getOnNextEvents should have size 2
            obs2.getOnNextEvents should contain allOf(createdFor(host, port1, container1),
                                                      createdFor(host, port2, container2))
        }

        scenario("Initial state for bindings with and without container") {
            Given("Two ports bound to a host, one with a container")
            val host = createHost()
            val bridge = createBridge()
            val container = createServiceContainer(configurationId = Some(randomUUID))
            val port1 = createBridgePort(bridgeId = Some(bridge.getId),
                                         hostId = Some(host.getId),
                                         interfaceName = Some("veth1"),
                                         containerId = Some(container.getId))
            val port2 = createBridgePort(bridgeId = Some(bridge.getId),
                                         hostId = Some(host.getId),
                                         interfaceName = Some("veth2"))
            store.multi(Seq(CreateOp(host), CreateOp(bridge),
                            CreateOp(container), CreateOp(port1),
                            CreateOp(port2)))

            And("Two container observers")
            val obs1 = new TestObserver[Notification]
            val obs2 = new TestObserver[Notification]

            When("Create a container mapper for the host")
            val mapper = new ContainerMapper(host.getId, vt)

            And("An observable on the mapper")
            val observable = Observable.create(mapper)

            When("The first observer subscribes to the observable")
            observable subscribe obs1

            Then("The first observer receives the container")
            obs1.getOnNextEvents should have size 1
            obs1.getOnNextEvents.get(0) shouldBe createdFor(host, port1, container)

            When("The second observer subscribes to the observable")
            observable subscribe obs2

            Then("The second observer receives the container")
            obs2.getOnNextEvents should have size 1
            obs2.getOnNextEvents.get(0) shouldBe createdFor(host, port1, container)
        }

        scenario("Mapper notifies the differential state") {
            Given("A first port bound to a host with a container")
            val host = createHost()
            val bridge = createBridge()
            val container1 = createServiceContainer(configurationId = Some(randomUUID))
            val port1 = createBridgePort(bridgeId = Some(bridge.getId),
                                         hostId = Some(host.getId),
                                         interfaceName = Some("veth1"),
                                         containerId = Some(container1.getId))
            store.multi(Seq(CreateOp(host), CreateOp(bridge),
                            CreateOp(container1), CreateOp(port1)))

            And("Two container observers")
            val obs1 = new TestObserver[Notification]
            val obs2 = new TestObserver[Notification]

            When("Create a container mapper for the host")
            val mapper = new ContainerMapper(host.getId, vt)

            And("An observable on the mapper")
            val observable = Observable.create(mapper)

            When("The first observer subscribes to the observable")
            observable subscribe obs1

            Then("The first observer receives the first container")
            obs1.getOnNextEvents should have size 1
            obs1.getOnNextEvents.get(0) shouldBe createdFor(host, port1, container1)

            When("Adding a second port bound with a container")
            val container2 = createServiceContainer(configurationId = Some(randomUUID))
            val port2 = createBridgePort(bridgeId = Some(bridge.getId),
                                         hostId = Some(host.getId),
                                         interfaceName = Some("veth2"),
                                         containerId = Some(container2.getId))
            store.multi(Seq(CreateOp(container2), CreateOp(port2)))

            Then("The first observer receives the second container")
            obs1.getOnNextEvents should have size 2
            obs1.getOnNextEvents.get(1) shouldBe createdFor(host, port2, container2)

            When("The second observer subscribes to the observable")
            observable subscribe obs2

            Then("The second observer receives both containers")
            obs2.getOnNextEvents should have size 2
            obs2.getOnNextEvents should contain allOf(createdFor(host, port1, container1),
                                                      createdFor(host, port2, container2))
        }
    }

    feature("Test mapper notifies cleanup state") {
        scenario("Cleanup on demand") {
            Given("Two ports bound to a host with containers")
            val host = createHost()
            val bridge = createBridge()
            val container1 = createServiceContainer(configurationId = Some(randomUUID))
            val container2 = createServiceContainer(configurationId = Some(randomUUID))
            val port1 = createBridgePort(bridgeId = Some(bridge.getId),
                                         hostId = Some(host.getId),
                                         interfaceName = Some("veth1"),
                                         containerId = Some(container1.getId))
            val port2 = createBridgePort(bridgeId = Some(bridge.getId),
                                         hostId = Some(host.getId),
                                         interfaceName = Some("veth2"),
                                         containerId = Some(container2.getId))
            store.multi(Seq(CreateOp(host), CreateOp(bridge),
                            CreateOp(container1), CreateOp(container2),
                            CreateOp(port1), CreateOp(port2)))

            And("A container observer")
            val obs = new TestObserver[Notification]

            When("Create a container mapper for the host")
            val mapper = new ContainerMapper(host.getId, vt)

            And("An observable on the mapper")
            val observable = Observable.create(mapper)

            When("The observer subscribes to the observable")
            val sub = observable subscribe obs

            Then("The observer should receive both containers")
            obs.getOnNextEvents should have size 2

            When("Requesting a complete")
            mapper.complete()

            Then("The observer should receive a Deleted for both containers")
            obs.getOnNextEvents should have size 4
            obs.getOnNextEvents should contain allOf(createdFor(host, port1, container1),
                                                     createdFor(host, port2, container2),
                                                     deletedFor(host, port1, container1),
                                                     deletedFor(host, port2, container2))

            And("The subscription should be unsubscribed")
            sub.isUnsubscribed shouldBe true

            And("The mapper state should be completed")
            mapper.mapperState shouldBe MapperState.Closed
        }

        scenario("Cleanup upon host deletion") {
            Given("Two ports bound to a host with containers")
            val host = createHost()
            val bridge = createBridge()
            val container1 = createServiceContainer(configurationId = Some(randomUUID))
            val container2 = createServiceContainer(configurationId = Some(randomUUID))
            val port1 = createBridgePort(bridgeId = Some(bridge.getId),
                                         hostId = Some(host.getId),
                                         interfaceName = Some("veth1"),
                                         containerId = Some(container1.getId))
            val port2 = createBridgePort(bridgeId = Some(bridge.getId),
                                         hostId = Some(host.getId),
                                         interfaceName = Some("veth2"),
                                         containerId = Some(container2.getId))
            store.multi(Seq(CreateOp(host), CreateOp(bridge),
                            CreateOp(container1), CreateOp(container2),
                            CreateOp(port1), CreateOp(port2)))

            And("A container observer")
            val obs = new TestObserver[Notification]

            When("Create a container mapper for the host")
            val mapper = new ContainerMapper(host.getId, vt)

            And("An observable on the mapper")
            val observable = Observable.create(mapper)

            When("The observer subscribes to the observable")
            val sub = observable subscribe obs

            Then("The observer should receive both containers")
            obs.getOnNextEvents should have size 2

            When("The host is deleted")
            store.delete(classOf[Host], host.getId)

            Then("The observer should receive a Deleted for both containers")
            obs.getOnNextEvents should have size 4
            obs.getOnNextEvents should contain allOf(createdFor(host, port1, container1),
                createdFor(host, port2, container2),
                deletedFor(host, port1, container1),
                deletedFor(host, port2, container2))

            And("The subscription should be unsubscribed")
            sub.isUnsubscribed shouldBe true

            And("The mapper state should be completed")
            mapper.mapperState shouldBe MapperState.Completed
        }

        scenario("Cleanup upon binding error") {
            Given("Two ports bound to a host with containers")
            val host = createHost()
            val bridge = createBridge()
            val container1 = createServiceContainer(configurationId = Some(randomUUID))
            val container2 = createServiceContainer(configurationId = Some(randomUUID))
            val port1 = createBridgePort(bridgeId = Some(bridge.getId),
                                         hostId = Some(host.getId),
                                         interfaceName = Some("veth1"),
                                         containerId = Some(container1.getId))
            val port2 = createBridgePort(bridgeId = Some(bridge.getId),
                                         hostId = Some(host.getId),
                                         interfaceName = Some("veth2"),
                                         containerId = Some(container2.getId))
            store.multi(Seq(CreateOp(host), CreateOp(bridge),
                            CreateOp(container1), CreateOp(container2),
                            CreateOp(port1), CreateOp(port2)))

            And("A container observer")
            val obs = new TestObserver[Notification]

            When("Create a container mapper for the host")
            val mapper = new ContainerMapper(host.getId, vt)

            And("An observable on the mapper")
            val observable = Observable.create(mapper)

            When("The observer subscribes to the observable")
            val sub = observable subscribe obs

            Then("The observer should receive both containers")
            obs.getOnNextEvents should have size 2

            When("Inject an error in the port observable")
            backdoor.observableError(classOf[Port], port1.getId, new Throwable())

            Then("The observer should receive a Deleted for both containers")
            obs.getOnNextEvents should have size 3
            obs.getOnNextEvents.get(2) shouldBe deletedFor(host, port1, container1)

            And("The subscription should not be unsubscribed")
            sub.isUnsubscribed shouldBe false

            And("The mapper state should be subscribed")
            mapper.mapperState shouldBe MapperState.Subscribed
        }

        scenario("Cleanup upon host error") {
            Given("Two ports bound to a host with containers")
            val host = createHost()
            val bridge = createBridge()
            val container1 = createServiceContainer(configurationId = Some(randomUUID))
            val container2 = createServiceContainer(configurationId = Some(randomUUID))
            val port1 = createBridgePort(bridgeId = Some(bridge.getId),
                                         hostId = Some(host.getId),
                                         interfaceName = Some("veth1"),
                                         containerId = Some(container1.getId))
            val port2 = createBridgePort(bridgeId = Some(bridge.getId),
                                         hostId = Some(host.getId),
                                         interfaceName = Some("veth2"),
                                         containerId = Some(container2.getId))
            store.multi(Seq(CreateOp(host), CreateOp(bridge),
                            CreateOp(container1), CreateOp(container2),
                            CreateOp(port1), CreateOp(port2)))

            And("A container observer")
            val obs = new TestObserver[Notification]

            When("Create a container mapper for the host")
            val mapper = new ContainerMapper(host.getId, vt)

            And("An observable on the mapper")
            val observable = Observable.create(mapper)

            When("The observer subscribes to the observable")
            val sub = observable subscribe obs

            Then("The observer should receive both containers")
            obs.getOnNextEvents should have size 2

            When("Inject an error in the host observable")
            backdoor.observableError(classOf[Host], host.getId, new Throwable())

            Then("The observer should receive a Deleted for both containers")
            obs.getOnNextEvents should have size 4
            obs.getOnNextEvents should contain allOf(createdFor(host, port1, container1),
                createdFor(host, port2, container2),
                deletedFor(host, port1, container1),
                deletedFor(host, port2, container2))

            And("The subscription should be unsubscribed")
            sub.isUnsubscribed shouldBe true

            And("The mapper state should be error")
            mapper.mapperState shouldBe MapperState.Error
        }
    }

    feature("Test mapper is robust to errors") {
        scenario("Mapper is robust to container stream errors") {
            Given("A port bound to a host with a container")
            val host = createHost()
            val bridge = createBridge()
            val container1 = createServiceContainer(configurationId = Some(randomUUID))
            val port1 = createBridgePort(bridgeId = Some(bridge.getId),
                                         hostId = Some(host.getId),
                                         interfaceName = Some("veth1"),
                                         containerId = Some(container1.getId))
            store.multi(Seq(CreateOp(host), CreateOp(bridge),
                            CreateOp(container1), CreateOp(port1)))

            And("A container observer")
            val obs = new TestObserver[Notification]

            When("Create a container mapper for the host")
            val mapper = new ContainerMapper(host.getId, vt)

            And("An observable on the mapper")
            val observable = Observable.create(mapper)

            When("The observer subscribes to the observable")
            observable subscribe obs

            Then("The observer should receive the first container created")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBe createdFor(host, port1, container1)

            When("The container observable emits en error")
            backdoor.observableError(classOf[ServiceContainer], container1.getId,
                                     new Throwable())

            Then("The observer should receive the container deleted")
            obs.getOnNextEvents should have size 2
            obs.getOnNextEvents.get(1) shouldBe deletedFor(host, port1, container1)

            When("Adding a new port with a container")
            val container2 = createServiceContainer(configurationId = Some(randomUUID))
            val port2 = createBridgePort(bridgeId = Some(bridge.getId),
                                         hostId = Some(host.getId),
                                         interfaceName = Some("veth2"),
                                         containerId = Some(container2.getId))
            store.multi(Seq(CreateOp(container2), CreateOp(port2)))

            Then("The observer should receive the second container created")
            obs.getOnNextEvents should have size 3
            obs.getOnNextEvents.get(2) shouldBe createdFor(host, port2, container2)
        }

        scenario("Mapper is robust to port stream errors") {
            Given("A port bound to a host with a container")
            val host = createHost()
            val bridge = createBridge()
            val container1 = createServiceContainer(configurationId = Some(randomUUID))
            val port1 = createBridgePort(bridgeId = Some(bridge.getId),
                                         hostId = Some(host.getId),
                                         interfaceName = Some("veth1"),
                                         containerId = Some(container1.getId))
            store.multi(Seq(CreateOp(host), CreateOp(bridge),
                            CreateOp(container1), CreateOp(port1)))

            And("A container observer")
            val obs = new TestObserver[Notification]

            When("Create a container mapper for the host")
            val mapper = new ContainerMapper(host.getId, vt)

            And("An observable on the mapper")
            val observable = Observable.create(mapper)

            When("The observer subscribes to the observable")
            observable subscribe obs

            Then("The observer should receive the first container created")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBe createdFor(host, port1, container1)

            When("The container observable emits en error")
            backdoor.observableError(classOf[Port], port1.getId, new Throwable())

            Then("The observer should receive the container deleted")
            obs.getOnNextEvents should have size 2
            obs.getOnNextEvents.get(1) shouldBe deletedFor(host, port1, container1)

            When("Adding a new port with a container")
            val container2 = createServiceContainer(configurationId = Some(randomUUID))
            val port2 = createBridgePort(bridgeId = Some(bridge.getId),
                                         hostId = Some(host.getId),
                                         interfaceName = Some("veth2"),
                                         containerId = Some(container2.getId))
            store.multi(Seq(CreateOp(container2), CreateOp(port2)))

            Then("The observer should receive the second container created")
            obs.getOnNextEvents should have size 3
            obs.getOnNextEvents.get(2) shouldBe createdFor(host, port2, container2)
        }
    }

}
