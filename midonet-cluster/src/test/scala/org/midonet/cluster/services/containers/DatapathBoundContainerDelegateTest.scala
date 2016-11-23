/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.cluster.services.containers

import java.util.UUID

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.state.ConnectionState
import org.junit.runner.RunWith
import org.scalatest.{BeforeAndAfter, FeatureSpec, GivenWhenThen, Matchers}
import org.scalatest.junit.JUnitRunner

import rx.Observable

import org.midonet.cluster.data.storage._
import org.midonet.cluster.models.State.ContainerStatus
import org.midonet.cluster.models.Topology.{Host, Port, ServiceContainer}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.discovery.{FakeDiscovery, MidonetDiscovery}
import org.midonet.cluster.services.state.client.StateTableClient
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.util.concurrent.toFutureOps
import org.midonet.util.eventloop.Reactor

@RunWith(classOf[JUnitRunner])
class DatapathBoundContainerDelegateTest extends FeatureSpec with Matchers
                                         with GivenWhenThen with BeforeAndAfter
                                         with TopologyBuilder {

    private var storage: InMemoryStorage = _
    private var backend: MidonetBackend = _

    @inline
    private def interfaceName(container: ServiceContainer): String = {
        interfaceName(container.getPortId.asJava)
    }

    @inline
    private def interfaceName(portId: UUID): String = {
        s"TEST-${portId.toString.substring(0,8)}"
    }

    class TestDPConDel(midonet: MidonetBackend)
        extends DatapathBoundContainerDelegate(midonet) {
        override val name = "TEST"
    }

    before {
        storage = new InMemoryStorage
        backend = new MidonetBackend {
            override def stateStore: StateStorage = storage
            override def store: Storage = storage
            override def connectionState: Observable[ConnectionState] = ???
            override def failFastConnectionState: Observable[ConnectionState] = ???
            override def curator: CuratorFramework = ???
            override def failFastCurator: CuratorFramework = ???
            override def stateTableStore: StateTableStorage = ???
            override def stateTableClient: StateTableClient = ???
            override def reactor: Reactor = ???
            override def doStop(): Unit = ???
            override def doStart(): Unit = ???
            override val discovery: MidonetDiscovery = discovery
        }
        MidonetBackend.setupBindings(storage, storage)
    }

    feature("Container delegate handles scheduling operations") {
        scenario("A container with a port not bound") {
            Given("A host and a container with a port")
            val host = createHost()
            val port = createRouterPort()
            val container = createServiceContainer(portId = Some(port.getId))
            storage.multi(Seq(CreateOp(host), CreateOp(port), CreateOp(container)))

            And("A container delegate")
            val delegate = new TestDPConDel(backend)

            When("Scheduling the container to the host")
            delegate.onScheduled(container, host.getId)

            Then("The port should be bound to an interface at the host")
            val boundPort = storage.get(classOf[Port], port.getId).await()
            boundPort.getHostId shouldBe host.getId
            boundPort.getInterfaceName shouldBe interfaceName(container)
        }

        scenario("A container with a port already bound") {
            Given("A host and a container with a port")
            val host1 = createHost()
            val host2 = createHost()
            val port = createRouterPort(hostId = Some(host1.getId),
                                        interfaceName = Some("name"))
            val container = createServiceContainer(portId = Some(port.getId))
            storage.multi(Seq(CreateOp(host1), CreateOp(host2), CreateOp(port),
                            CreateOp(container)))

            And("A container delegate")
            val delegate = new TestDPConDel(backend)

            When("Scheduling the container to the host")
            delegate.onScheduled(container, host2.getId)

            Then("The port should be bound to an interface at the host")
            val boundPort = storage.get(classOf[Port], port.getId).await()
            boundPort.getHostId shouldBe host2.getId
            boundPort.getInterfaceName shouldBe "name"
        }

        scenario("A container without a port") {
            Given("A host and a container with a port")
            val host = createHost()
            val container = createServiceContainer()
            storage.multi(Seq(CreateOp(host), CreateOp(container)))

            And("A container delegate")
            val delegate = new TestDPConDel(backend)

            Then("Scheduling the container to the host should fail")
            intercept[IllegalArgumentException] {
                delegate.onScheduled(container, host.getId)
            }
        }

        scenario("The port does not exist") {
            Given("A host and a container with a port")
            val hostId = UUID.randomUUID()
            val portId = UUID.randomUUID()
            val container = createServiceContainer(portId = Some(portId))

            And("A container delegate")
            val delegate = new TestDPConDel(backend)

            Then("Scheduling the container to the host should fail")
            val e = intercept[NotFoundException] {
                delegate.onScheduled(container, hostId)
            }
            e.clazz shouldBe classOf[Port]
            e.id shouldBe portId
        }

        scenario("The host does not exist") {
            Given("A host and a container with a port")
            val hostId = UUID.randomUUID()
            val port = createRouterPort()
            val container = createServiceContainer(portId = Some(port.getId))
            storage.multi(Seq(CreateOp(port), CreateOp(container)))

            And("A container delegate")
            val delegate = new TestDPConDel(backend)

            Then("Scheduling the container to the host should fail")
            val e = intercept[NotFoundException] {
                delegate.onScheduled(container, hostId)
            }
            e.clazz shouldBe classOf[Host]
            e.id shouldBe hostId.asProto
        }
    }

    feature("Container delegate handles unscheduling operations") {
        scenario("A container with a port already bound") {
            Given("A host and a container with a port")
            val host = createHost()
            val port = createRouterPort(hostId = Some(host.getId),
                                        interfaceName = Some("name"))
            val container = createServiceContainer(portId = Some(port.getId))
            storage.multi(Seq(CreateOp(host), CreateOp(port), CreateOp(container)))

            And("A container delegate")
            val delegate = new TestDPConDel(backend)

            When("Unscheduling the container from the host")
            delegate.onUnscheduled(container, host.getId)

            Then("The port should not be bound to the interface at the host")
            val boundPort = storage.get(classOf[Port], port.getId).await()
            boundPort.hasHostId shouldBe false
            boundPort.hasInterfaceName shouldBe true

        }

        scenario("A container with a port not bound") {
            Given("A host and a container with a port")
            val host = createHost()
            val port = createRouterPort()
            val container = createServiceContainer(portId = Some(port.getId))
            storage.multi(Seq(CreateOp(host), CreateOp(port), CreateOp(container)))

            And("A container delegate")
            val delegate = new TestDPConDel(backend)

            Then("Unscheduling the container from the host should ignore")
            delegate.onUnscheduled(container, host.getId)

            Then("The port should not be bound to the interface at the host")
            val boundPort = storage.get(classOf[Port], port.getId).await()
            boundPort.hasHostId shouldBe false
            boundPort.hasInterfaceName shouldBe false
        }

        scenario("A container without a port") {
            Given("A host and a container with a port")
            val host = createHost()
            val container = createServiceContainer()
            storage.multi(Seq(CreateOp(host), CreateOp(container)))

            And("A container delegate")
            val delegate = new TestDPConDel(backend)

            Then("Unscheduling the container from the host should fail")
            intercept[IllegalArgumentException] {
                delegate.onUnscheduled(container, host.getId)
            }
        }

        scenario("The port does not exist") {
            Given("A host and a container with a port")
            val hostId = UUID.randomUUID()
            val portId = UUID.randomUUID()
            val container = createServiceContainer(portId = Some(portId))

            And("A container delegate")
            val delegate = new TestDPConDel(backend)

            Then("Unscheduling the container from the host should not fail")
            delegate.onUnscheduled(container, hostId)
        }

        scenario("The host does not exist") {
            Given("A host and a container with a port")
            val hostId = UUID.randomUUID()
            val port = createRouterPort()
            val container = createServiceContainer(portId = Some(port.getId))
            storage.multi(Seq(CreateOp(port), CreateOp(container)))

            And("A container delegate")
            val delegate = new TestDPConDel(backend)

            When("Unscheduling the container from the host should ignore")
            delegate.onUnscheduled(container, hostId)
        }
    }

    feature("Delegate handles status operations") {
        scenario("Up status") {
            Given("A container")
            val container = createServiceContainer()

            And("A container delegate")
            val delegate = new TestDPConDel(backend)

            Then("The delegate handles up")
            delegate.onUp(container, ContainerStatus.getDefaultInstance)
        }

        scenario("Down status") {
            Given("A container")
            val container = createServiceContainer()

            And("A container delegate")
            val delegate = new TestDPConDel(backend)

            Then("The delegate handles up")
            delegate.onDown(container, ContainerStatus.getDefaultInstance)
        }

        scenario("Down null status") {
            Given("A container")
            val container = createServiceContainer()

            And("A container delegate")
            val delegate = new TestDPConDel(backend)

            Then("The delegate handles up")
            delegate.onDown(container, null)
        }
    }
}
