package org.midonet.cluster.services.containers

import java.util.UUID
import java.util.concurrent.Executors

import com.typesafe.scalalogging.Logger

import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner
import org.slf4j.LoggerFactory

import org.midonet.cluster.data.storage.{CreateOp, InMemoryStorage, StateStorage, Storage}
import org.midonet.cluster.models.Topology.{ServiceContainer, Port, TunnelZone}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.util.concurrent.toFutureOps

@RunWith(classOf[JUnitRunner])
class IpsecContainerDelegateTest extends FeatureSpec with Matchers
                                         with GivenWhenThen with BeforeAndAfter
                                         with TopologyBuilder {

    private val log = Logger(LoggerFactory.getLogger(getClass))
    private var store: Storage = _
    private var stateStore: StateStorage = _
    private var backdoor: InMemoryStorage = _
    private val executor = Executors.newSingleThreadExecutor()

    @inline
    private def getInterfaceName(container: ServiceContainer): String = {
        val portId = if (container.getPortId.isInitialized)
            container.getPortId.asJava.toString.substring(0,8) else ""
        s"vpn_${portId}_dp"
    }

    @inline
    private def getInterfaceName(portId: UUID): String = {
        s"vpn_${portId.toString.substring(0,8)}_dp"
    }

    before {
        backdoor = new InMemoryStorage
        store = backdoor
        stateStore = backdoor
        MidonetBackend.setupBindings(store, stateStore)
    }

    feature("VPN container delegate handles container creation") {
        scenario("There's no previous binding") {
            Given("A correct host and a port to bind to")
            val tunnelZone = createTunnelZone(tzType = TunnelZone.Type.VXLAN)
            val host = createHost(tunnelZoneIds = Set(tunnelZone.getId))
            val port = createRouterPort()
            val group = createServiceContainerGroup()
            val container = createServiceContainer(groupId = Some(group.getId),
                                                   portId = Some(port.getId))
            store.multi(Seq(CreateOp(tunnelZone),
                            CreateOp(host),
                            CreateOp(port),
                            CreateOp(group),
                            CreateOp(container)))
            When("A container is allocated to a host")
            val ipsecDelegate = new IpsecContainerDelegate(store)
            ipsecDelegate.onCreate(container, host.getId)

            Then("The binding is done correctly")
            store.get(classOf[Port], port.getId)
                .await().getHostId shouldBe host.getId
        }

        scenario("There's a previous binding") {
            Given("A correct host")
            val tunnelZone = createTunnelZone(tzType = TunnelZone.Type.VXLAN)
            val host1 = createHost(tunnelZoneIds = Set(tunnelZone.getId))
            val host2 = createHost(tunnelZoneIds = Set(tunnelZone.getId))

            And("A port already bound to interface in host1")
            val port = createRouterPort(hostId = Option(host1.getId))
            val group = createServiceContainerGroup()
            val container = createServiceContainer(groupId = Some(group.getId),
                                                   portId = Some(port.getId))
            store.multi(Seq(CreateOp(tunnelZone),
                            CreateOp(host1),
                            CreateOp(host2),
                            CreateOp(port),
                            CreateOp(group),
                            CreateOp(container)))

            When("A container is allocated to host2")
            val ipsecDelegate = new IpsecContainerDelegate(store)
            ipsecDelegate.onCreate(container, host2.getId)

            Then("The binding is updated accordingly")
            store.get(classOf[Port], port.getId)
                .await().getHostId shouldBe host2.getId
        }

        scenario("The port does not exist") {
            Given("A correct host")
            val tunnelZone = createTunnelZone(tzType = TunnelZone.Type.VXLAN)
            val host = createHost(tunnelZoneIds = Set(tunnelZone.getId))

            And("A container without a port specified")
            val group = createServiceContainerGroup()
            val container = createServiceContainer(groupId = Some(group.getId))
            store.multi(Seq(CreateOp(tunnelZone),
                            CreateOp(host),
                            CreateOp(group),
                            CreateOp(container)))

            When("A container is allocated to host")
            val ipsecDelegate = new IpsecContainerDelegate(store)
            Then("No exception thrown")
            ipsecDelegate.onCreate(container, host.getId)
        }

        scenario("The interface on the host is already bound to another port") {
            Given("A host, a container and a port without binding")
            val tunnelZone = createTunnelZone(tzType = TunnelZone.Type.VXLAN)
            val host = createHost(tunnelZoneIds = Set(tunnelZone.getId))
            val port = createRouterPort()
            val group = createServiceContainerGroup()
            val container = createServiceContainer(groupId = Some(group.getId),
                                                   portId = Some(port.getId))

            And("A port with a binding on the same interface as the container port")
            val ifaceName = getInterfaceName(container)
            val wrongPort = createRouterPort(hostId = Option(host.getId),
                                        interfaceName = Option(ifaceName))

            store.multi(Seq(CreateOp(tunnelZone),
                            CreateOp(host),
                            CreateOp(port),
                            CreateOp(group),
                            CreateOp(container),
                            CreateOp(wrongPort)))

            When("A container is allocated to a host")
            val ipsecDelegate = new IpsecContainerDelegate(store)
            Then("No exception thrown")
            ipsecDelegate.onCreate(container, host.getId)
            And("The container port is not updated")
            val containerPort = store.get(classOf[Port], port.getId).await()
            containerPort.getHostId.isInitialized shouldBe false
            containerPort.getInterfaceName.isEmpty shouldBe true
        }

        scenario("The host does not belong to any tunnel zone") {
            Given("A host not in a tunnel zone, a container and a port with a binding")
            val host = createHost()
            val port = createRouterPort()
            val group = createServiceContainerGroup()
            val container = createServiceContainer(groupId = Some(group.getId),
                                                   portId = Some(port.getId))
            store.multi(Seq(CreateOp(host),
                            CreateOp(port),
                            CreateOp(group),
                            CreateOp(container)))

            When("A container is allocated to a host")
            val ipsecDelegate = new IpsecContainerDelegate(store)
            Then("No exception thrown")
            ipsecDelegate.onCreate(container, host.getId)
            And("The container port is not updated")
            val containerPort = store.get(classOf[Port], port.getId).await()
            containerPort.getHostId.isInitialized shouldBe false
            containerPort.getInterfaceName.isEmpty shouldBe true
        }
    }

    feature("VPN container delegate can handle container deallocation") {
        scenario("A deallocation is done with a previous binding") {
            Given("A host, a container and a port with a binding")
            val host = createHost()
            val portId = UUID.randomUUID
            val port = createRouterPort(id = portId,
                                        hostId = Some(host.getId),
                                        interfaceName = Some(getInterfaceName(portId)))
            val group = createServiceContainerGroup()
            val container = createServiceContainer(groupId = Some(group.getId),
                                                   portId = Some(port.getId))
            store.multi(Seq(CreateOp(host),
                            CreateOp(port),
                            CreateOp(group),
                            CreateOp(container)))
            And("The port binding exists")
            var containerPort = store.get(classOf[Port], port.getId).await()
            containerPort.getHostId shouldBe host.getId
            containerPort.getInterfaceName shouldBe getInterfaceName(portId)

            When("A container is deallocated from a host")
            val ipsecDelegate = new IpsecContainerDelegate(store)
            ipsecDelegate.onDelete(container, host.getId)

            Then("The port no longer has a binding")
            containerPort = store.get(classOf[Port], port.getId).await()
            containerPort.getHostId.isInitialized shouldBe false
            containerPort.getInterfaceName.isEmpty shouldBe true
        }

        scenario("A deallocation is done without a previous binding") {
            Given("A host, a container and a port with a binding")
            val host = createHost()
            val port = createRouterPort()
            val group = createServiceContainerGroup()
            val container = createServiceContainer(groupId = Some(group.getId),
                                                   portId = Some(port.getId))
            store.multi(Seq(CreateOp(host),
                            CreateOp(port),
                            CreateOp(group),
                            CreateOp(container)))
            And("The port does not have any binding")
            var containerPort = store.get(classOf[Port], port.getId).await()
            containerPort.getHostId.isInitialized shouldBe false
            containerPort.getInterfaceName.isEmpty shouldBe true

            When("A container is deallocated from a host")
            val ipsecDelegate = new IpsecContainerDelegate(store)
            ipsecDelegate.onDelete(container, host.getId)

            Then("No exceptions thrown and binding still does not exist")
            containerPort = store.get(classOf[Port], port.getId).await()
            containerPort.getHostId.isInitialized shouldBe false
            containerPort.getInterfaceName.isEmpty shouldBe true
        }
    }
}
