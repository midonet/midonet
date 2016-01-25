package org.midonet.cluster.services.containers

import java.util.UUID

import org.apache.curator.framework.CuratorFramework
import org.junit.runner.RunWith
import org.scalatest.{Matchers, GivenWhenThen, BeforeAndAfter, FeatureSpec}
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.backend.zookeeper.{ZkConnectionAwareWatcher, ZkConnection}
import org.midonet.cluster.data.storage._
import org.midonet.cluster.models.State.ContainerStatus
import org.midonet.cluster.models.Topology.{Host, Port, ServiceContainer}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.util.concurrent.toFutureOps
import org.midonet.util.eventloop.Reactor

@RunWith(classOf[JUnitRunner])
class IPSecContainerDelegateTest extends FeatureSpec with Matchers
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
        s"vpn-${portId.toString.substring(0,8)}"
    }

    before {
        storage = new InMemoryStorage
        backend = new MidonetBackend {
            override def stateStore: StateStorage = storage
            override def store: Storage = storage
            override def connectionWatcher: ZkConnectionAwareWatcher = ???
            override def connection: ZkConnection = ???
            override def curator: CuratorFramework = ???
            override def failFastCurator: CuratorFramework = ???
            override def stateTableStore: StateTableStorage = ???
            override def reactor: Reactor = ???
            override def doStop(): Unit = ???
            override def doStart(): Unit = ???
        }
        MidonetBackend.setupBindings(storage, storage)
    }

    feature("IPSec container delegate handles scheduling operations") {
        scenario("A container with a port not bound") {
            Given("A host and a container with a port")
            val host = createHost()
            val port = createRouterPort()
            val container = createServiceContainer(portId = Some(port.getId))
            storage.multi(Seq(CreateOp(host), CreateOp(port), CreateOp(container)))

            And("An IPSec container delegate")
            val delegate = new IPSecContainerDelegate(backend)

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

            And("An IPSec container delegate")
            val delegate = new IPSecContainerDelegate(backend)

            When("Scheduling the container to the host")
            delegate.onScheduled(container, host2.getId)

            Then("The port should be bound to an interface at the host")
            val boundPort = storage.get(classOf[Port], port.getId).await()
            boundPort.getHostId shouldBe host2.getId
            boundPort.getInterfaceName shouldBe interfaceName(container)
        }

        scenario("A container without a port") {
            Given("A host and a container with a port")
            val host = createHost()
            val container = createServiceContainer()
            storage.multi(Seq(CreateOp(host), CreateOp(container)))

            And("An IPSec container delegate")
            val delegate = new IPSecContainerDelegate(backend)

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

            And("An IPSec container delegate")
            val delegate = new IPSecContainerDelegate(backend)

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

            And("An IPSec container delegate")
            val delegate = new IPSecContainerDelegate(backend)

            Then("Scheduling the container to the host should fail")
            val e = intercept[NotFoundException] {
                delegate.onScheduled(container, hostId)
            }
            e.clazz shouldBe classOf[Host]
            e.id shouldBe hostId.asProto
        }
    }

    feature("IPSec container delegate handles unscheduling operations") {
        scenario("A container with a port already bound") {
            Given("A host and a container with a port")
            val host = createHost()
            val port = createRouterPort(hostId = Some(host.getId),
                                        interfaceName = Some("name"))
            val container = createServiceContainer(portId = Some(port.getId))
            storage.multi(Seq(CreateOp(host), CreateOp(port), CreateOp(container)))

            And("An IPSec container delegate")
            val delegate = new IPSecContainerDelegate(backend)

            When("Unscheduling the container from the host")
            delegate.onUnscheduled(container, host.getId)

            Then("The port should not be bound to the interface at the host")
            val boundPort = storage.get(classOf[Port], port.getId).await()
            boundPort.hasHostId shouldBe false
            boundPort.hasInterfaceName shouldBe false

        }

        scenario("A container with a port not bound") {
            Given("A host and a container with a port")
            val host = createHost()
            val port = createRouterPort()
            val container = createServiceContainer(portId = Some(port.getId))
            storage.multi(Seq(CreateOp(host), CreateOp(port), CreateOp(container)))

            And("An IPSec container delegate")
            val delegate = new IPSecContainerDelegate(backend)

            Then("Unscheduling the container from the host should fail")
            val e = intercept[NotFoundException] {
                delegate.onUnscheduled(container, host.getId)
            }
            e.clazz shouldBe classOf[Host]
            e.id shouldBe host.getId

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

            And("An IPSec container delegate")
            val delegate = new IPSecContainerDelegate(backend)

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

            And("An IPSec container delegate")
            val delegate = new IPSecContainerDelegate(backend)

            Then("Unscheduling the container from the host should not fail")
            delegate.onUnscheduled(container, hostId)
        }

        scenario("The host does not exist") {
            Given("A host and a container with a port")
            val hostId = UUID.randomUUID()
            val port = createRouterPort()
            val container = createServiceContainer(portId = Some(port.getId))
            storage.multi(Seq(CreateOp(port), CreateOp(container)))

            And("An IPSec container delegate")
            val delegate = new IPSecContainerDelegate(backend)

            When("Unscheduling the container from the host should fail")
            val e = intercept[NotFoundException] {
                delegate.onUnscheduled(container, hostId)
            }
            e.clazz shouldBe classOf[Host]
            e.id shouldBe hostId.asProto
        }
    }

    feature("Delegate handles status operations") {
        scenario("Up status") {
            Given("A container")
            val container = createServiceContainer()

            And("An IPSec container delegate")
            val delegate = new IPSecContainerDelegate(backend)

            Then("The delegate handles up")
            delegate.onUp(container, ContainerStatus.getDefaultInstance)
        }

        scenario("Down status") {
            Given("A container")
            val container = createServiceContainer()

            And("An IPSec container delegate")
            val delegate = new IPSecContainerDelegate(backend)

            Then("The delegate handles up")
            delegate.onDown(container, ContainerStatus.getDefaultInstance)
        }

        scenario("Down null status") {
            Given("A container")
            val container = createServiceContainer()

            And("An IPSec container delegate")
            val delegate = new IPSecContainerDelegate(backend)

            Then("The delegate handles up")
            delegate.onDown(container, null)
        }
    }
}
