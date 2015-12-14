package org.midonet.cluster.services.containers

import java.util.UUID

import org.junit.runner.RunWith
import org.scalatest.{Matchers, GivenWhenThen, BeforeAndAfter, FeatureSpec}
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.storage.{NotFoundException, CreateOp, InMemoryStorage}
import org.midonet.cluster.models.State.ContainerStatus
import org.midonet.cluster.models.Topology.{Host, Port, ServiceContainer}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.util.concurrent.toFutureOps

@RunWith(classOf[JUnitRunner])
class IpsecContainerDelegateTest extends FeatureSpec with Matchers
                                 with GivenWhenThen with BeforeAndAfter
                                 with TopologyBuilder {

    private var store: InMemoryStorage = _

    @inline
    private def interfaceName(container: ServiceContainer): String = {
        interfaceName(container.getPortId.asJava)
    }

    @inline
    private def interfaceName(portId: UUID): String = {
        s"vpn-${portId.toString.substring(0,8)}"
    }

    before {
        store = new InMemoryStorage
        MidonetBackend.setupBindings(store, store)
    }

    feature("IPSec container delegate handles scheduling operations") {
        scenario("A container with a port not bound") {
            Given("A host and a container with a port")
            val host = createHost()
            val port = createRouterPort()
            val container = createServiceContainer(portId = Some(port.getId))
            store.multi(Seq(CreateOp(host), CreateOp(port), CreateOp(container)))

            And("An IPSec container delegate")
            val delegate = new IpsecContainerDelegate(store)

            When("Scheduling the container to the host")
            delegate.onScheduled(container, host.getId)

            Then("The port should be bound to an interface at the host")
            val boundPort = store.get(classOf[Port], port.getId).await()
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
            store.multi(Seq(CreateOp(host1), CreateOp(host2), CreateOp(port),
                            CreateOp(container)))

            And("An IPSec container delegate")
            val delegate = new IpsecContainerDelegate(store)

            When("Scheduling the container to the host")
            delegate.onScheduled(container, host2.getId)

            Then("The port should be bound to an interface at the host")
            val boundPort = store.get(classOf[Port], port.getId).await()
            boundPort.getHostId shouldBe host2.getId
            boundPort.getInterfaceName shouldBe interfaceName(container)
        }

        scenario("A container without a port") {
            Given("A host and a container with a port")
            val host = createHost()
            val container = createServiceContainer()
            store.multi(Seq(CreateOp(host), CreateOp(container)))

            And("An IPSec container delegate")
            val delegate = new IpsecContainerDelegate(store)

            Then("Scheduling the container to the host should fail")
            intercept[IllegalArgumentException] {
                delegate.onScheduled(container, host.getId)
            }
        }

        scenario("The host does not exist") {
            Given("A host and a container with a port")
            val hostId = UUID.randomUUID()
            val port = createRouterPort()
            val container = createServiceContainer(portId = Some(port.getId))
            store.multi(Seq(CreateOp(port), CreateOp(container)))

            And("An IPSec container delegate")
            val delegate = new IpsecContainerDelegate(store)

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
            store.multi(Seq(CreateOp(host), CreateOp(port), CreateOp(container)))

            And("An IPSec container delegate")
            val delegate = new IpsecContainerDelegate(store)

            When("Unscheduling the container from the host")
            delegate.onUnscheduled(container, host.getId)

            Then("The port should not be bound to the interface at the host")
            val boundPort = store.get(classOf[Port], port.getId).await()
            boundPort.hasHostId shouldBe false
            boundPort.hasInterfaceName shouldBe false

        }

        scenario("A container with a port not bound") {
            Given("A host and a container with a port")
            val host = createHost()
            val port = createRouterPort()
            val container = createServiceContainer(portId = Some(port.getId))
            store.multi(Seq(CreateOp(host), CreateOp(port), CreateOp(container)))

            And("An IPSec container delegate")
            val delegate = new IpsecContainerDelegate(store)

            Then("Unscheduling the container from the host should fail")
            val e = intercept[NotFoundException] {
                delegate.onUnscheduled(container, host.getId)
            }
            e.clazz shouldBe classOf[Host]
            e.id shouldBe host.getId

            Then("The port should not be bound to the interface at the host")
            val boundPort = store.get(classOf[Port], port.getId).await()
            boundPort.hasHostId shouldBe false
            boundPort.hasInterfaceName shouldBe false
        }

        scenario("A container without a port") {
            Given("A host and a container with a port")
            val host = createHost()
            val container = createServiceContainer()
            store.multi(Seq(CreateOp(host), CreateOp(container)))

            And("An IPSec container delegate")
            val delegate = new IpsecContainerDelegate(store)

            Then("Unscheduling the container from the host should fail")
            intercept[IllegalArgumentException] {
                delegate.onUnscheduled(container, host.getId)
            }
        }

        scenario("The host does not exist") {
            Given("A host and a container with a port")
            val hostId = UUID.randomUUID()
            val port = createRouterPort()
            val container = createServiceContainer(portId = Some(port.getId))
            store.multi(Seq(CreateOp(port), CreateOp(container)))

            And("An IPSec container delegate")
            val delegate = new IpsecContainerDelegate(store)

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
            val delegate = new IpsecContainerDelegate(store)

            Then("The delegate handles up")
            delegate.onUp(container, ContainerStatus.getDefaultInstance)
        }

        scenario("Down status") {
            Given("A container")
            val container = createServiceContainer()

            And("An IPSec container delegate")
            val delegate = new IpsecContainerDelegate(store)

            Then("The delegate handles up")
            delegate.onDown(container, ContainerStatus.getDefaultInstance)
        }

        scenario("Down null status") {
            Given("A container")
            val container = createServiceContainer()

            And("An IPSec container delegate")
            val delegate = new IpsecContainerDelegate(store)

            Then("The delegate handles up")
            delegate.onDown(container, null)
        }
    }
}
