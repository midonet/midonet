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

package org.midonet.midolman.containers

import java.util.UUID
import java.util.UUID._

import scala.concurrent.Future

import com.google.protobuf.TextFormat

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import rx.Observable
import rx.subjects.PublishSubject

import org.midonet.cluster.data.storage._
import org.midonet.cluster.models.State.ContainerStatus
import org.midonet.cluster.models.State.ContainerStatus.Code
import org.midonet.cluster.models.Topology.{Host, Port, ServiceContainer, ServiceContainerGroup}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.MidonetBackend.StatusKey
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.containers.Container
import org.midonet.midolman.containers.ContainerServiceTest.TestContainer
import org.midonet.midolman.topology.VirtualTopology
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.util.reactivex._

object ContainerServiceTest {

    val TestNamespace = "test-ns"

    abstract class TestContainer(errorOnCreate: Boolean = false,
                                 errorOnUpdate: Boolean = false,
                                 errorOnDelete: Boolean = false,
                                 throwsOnCreate: Boolean = false,
                                 throwsOnUpdate: Boolean = false,
                                 throwsOnDelete: Boolean = false)
        extends ContainerHandler {

        val stream = PublishSubject.create[ContainerHealth]
        var created: Int = 0
        var updated: Int = 0
        var deleted: Int = 0

        override def create(port: ContainerPort): Future[String] = {
            created += 1
            if (throwsOnCreate) throw new Throwable()
            if (errorOnCreate) Future.failed(new Throwable())
            else Future.successful(TestNamespace)
        }

        override def updated(port: ContainerPort): Future[Unit] = {
            updated += 1
            if (throwsOnUpdate) throw new Throwable()
            if (errorOnUpdate) Future.failed(new Throwable())
            else Future.successful(())
        }

        override def delete(): Future[Unit] = {
            deleted += 1
            if (throwsOnDelete) throw new Throwable()
            if (errorOnDelete) Future.failed(new Throwable())
            else Future.successful(())
        }

        override def health: Observable[ContainerHealth] = stream
    }

    @Container(name = "test-good", version = 1)
    class GoodContainer extends TestContainer()

    @Container(name = "test-error-create", version = 1)
    class ErrorCreateContainer extends TestContainer(errorOnCreate = true)

    @Container(name = "test-error-update", version = 1)
    class ErrorUpdateContainer extends TestContainer(errorOnUpdate = true)

    @Container(name = "test-error-delete", version = 1)
    class ErrorDeleteContainer extends TestContainer(errorOnDelete = true)

    @Container(name = "test-throws-create", version = 1)
    class ThrowsCreateContainer extends TestContainer(throwsOnCreate = true)

    @Container(name = "test-throws-update", version = 1)
    class ThrowsUpdateContainer extends TestContainer(throwsOnUpdate = true)

    @Container(name = "test-throws-delete", version = 1)
    class ThrowsDeleteContainer extends TestContainer(throwsOnDelete = true)

}

@RunWith(classOf[JUnitRunner])
class ContainerServiceTest extends MidolmanSpec with TopologyBuilder {

    import TopologyBuilder._

    private var store: Storage = _
    private var stateStore: StateStorage = _
    private var vt: VirtualTopology = _

    protected override def beforeTest(): Unit = {
        vt = injector.getInstance(classOf[VirtualTopology])
        store = injector.getInstance(classOf[MidonetBackend]).store
        stateStore = injector.getInstance(classOf[MidonetBackend]).stateStore
    }

    def containerPort(host: Host, port: Port, container: ServiceContainer,
                      group: ServiceContainerGroup): ContainerPort = {
        ContainerPort(port.getId, host.getId, port.getInterfaceName,
                      container.getId, container.getServiceType, group.getId,
                      container.getConfigurationId)
    }

    def getStatus(containerId: UUID): Option[ContainerStatus] = {
        stateStore.getKey(classOf[ServiceContainer], containerId, StatusKey)
                  .await() match {
            case key: SingleValueKey =>
                key.value.map { s =>
                    val builder = ContainerStatus.newBuilder()
                    TextFormat.merge(s, builder)
                    builder.build()
                }
        }

    }

    feature("Test service lifecyle") {
        scenario("Service starts and stops") {
            Given("A container service")
            val service = new ContainerService(vt, hostId, vt.vtExecutor)

            When("Starting the service")
            service.startAsync().awaitRunning()

            Then("The service is running")
            service.isRunning shouldBe true

            When("Stopping the service")
            service.stopAsync().awaitTerminated()

            Then("The service is stopped")
            service.isRunning shouldBe false
        }

        scenario("Service fails to start for non-existent host") {
            Given("A container service")
            val service = new ContainerService(vt, randomUUID, vt.vtExecutor)

            Then("Starting the service should fail")
            intercept[IllegalStateException] {
                service.startAsync().awaitRunning()
            }
        }
    }

    feature("Test service processes container notifications") {
        scenario("Start and stop unknown container") {
            Given("A port bound to a host with a container")
            val host = createHost()
            val bridge = createBridge()
            val group = createServiceContainerGroup()
            val container = createServiceContainer(configurationId = Some(randomUUID),
                                                   serviceType = Some("unknown"),
                                                   groupId = Some(group.getId))
            val port = createBridgePort(bridgeId = Some(bridge.getId),
                                        hostId = Some(host.getId),
                                        interfaceName = Some("veth"),
                                        containerId = Some(container.getId))
            store.multi(Seq(CreateOp(host), CreateOp(bridge), CreateOp(group),
                            CreateOp(container), CreateOp(port)))

            And("A container service")
            val service = new ContainerService(vt, host.getId, vt.vtExecutor)
            service.startAsync().awaitRunning()

            Then("The service should not have the container")
            service.handlerOf(port.getId) shouldBe null

            When("Stopping the service")
            service.stopAsync().awaitTerminated()
        }

        scenario("Start and stop a good container") {
            Given("A port bound to a host with a container")
            val host = createHost()
            val bridge = createBridge()
            val group = createServiceContainerGroup()
            val container1 = createServiceContainer(configurationId = Some(randomUUID),
                                                    serviceType = Some("test-good"),
                                                    groupId = Some(group.getId))
            val port = createBridgePort(bridgeId = Some(bridge.getId),
                                        hostId = Some(host.getId),
                                        interfaceName = Some("veth"),
                                        containerId = Some(container1.getId))
            store.multi(Seq(CreateOp(host), CreateOp(bridge), CreateOp(group),
                            CreateOp(container1), CreateOp(port)))

            And("A container service")
            val service = new ContainerService(vt, host.getId, vt.vtExecutor)
            service.startAsync().awaitRunning()

            Then("The service should start the container")
            val handler = service.handlerOf(port.getId)
            handler should not be null
            handler.cp shouldBe containerPort(host, port, container1, group)
            handler.namespace shouldBe "test-ns"

            And("The handler create method should be called")
            val h = handler.handler.asInstanceOf[TestContainer]
            h.created shouldBe 1

            When("Updating the container configuration")
            val container2 = container1.setPortId(port.getId)
                                       .setConfigurationId(randomUUID)
            store.update(container2)

            Then("The handler update method should be called")
            h.updated shouldBe 1

            When("Stopping the service")
            service.stopAsync().awaitTerminated()

            Then("The handler delete method should be called")
            h.deleted shouldBe 1
        }

        scenario("Start and stop a bad container for create") {
            Given("A port bound to a host with a container")
            val host = createHost()
            val bridge = createBridge()
            val group = createServiceContainerGroup()
            val container = createServiceContainer(configurationId = Some(randomUUID),
                                                   serviceType = Some("test-error-create"),
                                                   groupId = Some(group.getId))
            val port = createBridgePort(bridgeId = Some(bridge.getId),
                                        hostId = Some(host.getId),
                                        interfaceName = Some("veth"),
                                        containerId = Some(container.getId))
            store.multi(Seq(CreateOp(host), CreateOp(bridge), CreateOp(group),
                            CreateOp(container), CreateOp(port)))

            And("A container service")
            val service = new ContainerService(vt, host.getId, vt.vtExecutor)
            service.startAsync().awaitRunning()

            Then("The service should not have the container")
            service.handlerOf(port.getId) shouldBe null

            When("Stopping the service")
            service.stopAsync().awaitTerminated()
        }

        scenario("Start and stop a bad container for update") {
            Given("A port bound to a host with a container")
            val host = createHost()
            val bridge = createBridge()
            val group = createServiceContainerGroup()
            val container1 = createServiceContainer(configurationId = Some(randomUUID),
                                                    serviceType = Some("test-error-update"),
                                                    groupId = Some(group.getId))
            val port = createBridgePort(bridgeId = Some(bridge.getId),
                                        hostId = Some(host.getId),
                                        interfaceName = Some("veth"),
                                        containerId = Some(container1.getId))
            store.multi(Seq(CreateOp(host), CreateOp(bridge), CreateOp(group),
                            CreateOp(container1), CreateOp(port)))

            And("A container service")
            val service = new ContainerService(vt, host.getId, vt.vtExecutor)
            service.startAsync().awaitRunning()

            Then("The service should start the container")
            val handler = service.handlerOf(port.getId)
            handler should not be null
            handler.cp shouldBe containerPort(host, port, container1, group)
            handler.namespace shouldBe "test-ns"

            And("The handler create method should be called")
            val h = handler.handler.asInstanceOf[TestContainer]
            h.created shouldBe 1

            When("Updating the container configuration")
            val container2 = container1.setPortId(port.getId)
                .setConfigurationId(randomUUID)
            store.update(container2)

            Then("The handler update method should be called")
            h.updated shouldBe 1

            When("Stopping the service")
            service.stopAsync().awaitTerminated()

            Then("The handler delete method should be called")
            h.deleted shouldBe 1
        }

        scenario("Start and stop a bad container for delete") {
            Given("A port bound to a host with a container")
            val host = createHost()
            val bridge = createBridge()
            val group = createServiceContainerGroup()
            val container1 = createServiceContainer(configurationId = Some(randomUUID),
                                                    serviceType = Some("test-error-delete"),
                                                    groupId = Some(group.getId))
            val port = createBridgePort(bridgeId = Some(bridge.getId),
                                        hostId = Some(host.getId),
                                        interfaceName = Some("veth"),
                                        containerId = Some(container1.getId))
            store.multi(Seq(CreateOp(host), CreateOp(bridge), CreateOp(group),
                            CreateOp(container1), CreateOp(port)))

            And("A container service")
            val service = new ContainerService(vt, host.getId, vt.vtExecutor)
            service.startAsync().awaitRunning()

            Then("The service should start the container")
            val handler = service.handlerOf(port.getId)
            handler should not be null
            handler.cp shouldBe containerPort(host, port, container1, group)
            handler.namespace shouldBe "test-ns"

            And("The handler create method should be called")
            val h = handler.handler.asInstanceOf[TestContainer]
            h.created shouldBe 1

            When("Updating the container configuration")
            val container2 = container1.setPortId(port.getId)
                .setConfigurationId(randomUUID)
            store.update(container2)

            Then("The handler update method should be called")
            h.updated shouldBe 1

            When("Stopping the service")
            service.stopAsync().awaitTerminated()

            Then("The handler delete method should be called")
            h.deleted shouldBe 1
        }

        scenario("Start a new container") {
            Given("A container service")
            val host = createHost()
            val bridge = createBridge()
            store.multi(Seq(CreateOp(host), CreateOp(bridge)))

            val service = new ContainerService(vt, host.getId, vt.vtExecutor)
            service.startAsync().awaitRunning()

            Then("The service should not have any container")
            service.handlerList shouldBe empty

            When("Creating a port bound to a host with a container")
            val group = createServiceContainerGroup()
            val container1 = createServiceContainer(configurationId = Some(randomUUID),
                                                    serviceType = Some("test-good"),
                                                    groupId = Some(group.getId))
            val port = createBridgePort(bridgeId = Some(bridge.getId),
                                        hostId = Some(host.getId),
                                        interfaceName = Some("veth"),
                                        containerId = Some(container1.getId))
            store.multi(Seq(CreateOp(group), CreateOp(container1),
                            CreateOp(port)))

            Then("The service should start the container")
            val handler = service.handlerOf(port.getId)
            handler should not be null
            handler.cp shouldBe containerPort(host, port, container1, group)
            handler.namespace shouldBe "test-ns"

            And("The handler create method should be called")
            val h = handler.handler.asInstanceOf[TestContainer]
            h.created shouldBe 1

            When("Updating the container configuration")
            val container2 = container1.setPortId(port.getId)
                .setConfigurationId(randomUUID)
            store.update(container2)

            Then("The handler update method should be called")
            h.updated shouldBe 1

            When("Stopping the service")
            service.stopAsync().awaitTerminated()

            Then("The handler delete method should be called")
            h.deleted shouldBe 1
        }

        scenario("Stop the container when deleting the port binding") {
            Given("A port bound to a host with a container")
            val host = createHost()
            val bridge = createBridge()
            val group = createServiceContainerGroup()
            val container = createServiceContainer(configurationId = Some(randomUUID),
                                                   serviceType = Some("test-good"),
                                                   groupId = Some(group.getId))
            val port = createBridgePort(bridgeId = Some(bridge.getId),
                                        hostId = Some(host.getId),
                                        interfaceName = Some("veth"),
                                        containerId = Some(container.getId))
            store.multi(Seq(CreateOp(host), CreateOp(bridge), CreateOp(group),
                            CreateOp(container), CreateOp(port)))

            And("A container service")
            val service = new ContainerService(vt, host.getId, vt.vtExecutor)
            service.startAsync().awaitRunning()

            Then("The service should start the container")
            val handler = service.handlerOf(port.getId)
            handler should not be null
            handler.cp shouldBe containerPort(host, port, container, group)
            handler.namespace shouldBe "test-ns"

            And("The handler create method should be called")
            val h = handler.handler.asInstanceOf[TestContainer]
            h.created shouldBe 1

            When("Deleting the port binding")
            store.delete(classOf[Port], port.getId)

            Then("The service should stop the container")
            h.deleted shouldBe 1
            service.handlerList shouldBe empty

            service.stopAsync().awaitTerminated()
        }

        scenario("Stop the container when moving the binding to a different host") {
            Given("A port bound to a host with a container")
            val host1 = createHost()
            val bridge = createBridge()
            val group = createServiceContainerGroup()
            val container = createServiceContainer(configurationId = Some(randomUUID),
                                                   serviceType = Some("test-error-update"),
                                                   groupId = Some(group.getId))
            val port1 = createBridgePort(bridgeId = Some(bridge.getId),
                                         hostId = Some(host1.getId),
                                         interfaceName = Some("veth1"),
                                         containerId = Some(container.getId))
            store.multi(Seq(CreateOp(host1), CreateOp(bridge), CreateOp(group),
                            CreateOp(container), CreateOp(port1)))

            And("A container service")
            val service = new ContainerService(vt, host1.getId, vt.vtExecutor)
            service.startAsync().awaitRunning()

            Then("The service should start the container")
            val handler = service.handlerOf(port1.getId)
            handler should not be null
            handler.cp shouldBe containerPort(host1, port1, container, group)
            handler.namespace shouldBe "test-ns"

            And("The handler create method should be called")
            val h = handler.handler.asInstanceOf[TestContainer]
            h.created shouldBe 1

            When("Moving the binding to a different host")
            val host2 = createHost()
            val port2 = port1.setHostId(host2.getId)
            store.multi(Seq(CreateOp(host2), UpdateOp(port2)))

            Then("The service should stop the container")
            h.deleted shouldBe 1
            service.handlerList shouldBe empty

            service.stopAsync().awaitTerminated()
        }

        scenario("Stop the container when the service container is deleted") {
            Given("A port bound to a host with a container")
            val host = createHost()
            val bridge = createBridge()
            val group = createServiceContainerGroup()
            val container = createServiceContainer(configurationId = Some(randomUUID),
                                                   serviceType = Some("test-error-update"),
                                                   groupId = Some(group.getId))
            val port = createBridgePort(bridgeId = Some(bridge.getId),
                                        hostId = Some(host.getId),
                                        interfaceName = Some("veth"),
                                        containerId = Some(container.getId))
            store.multi(Seq(CreateOp(host), CreateOp(bridge), CreateOp(group),
                            CreateOp(container), CreateOp(port)))

            And("A container service")
            val service = new ContainerService(vt, host.getId, vt.vtExecutor)
            service.startAsync().awaitRunning()

            Then("The service should start the container")
            val handler = service.handlerOf(port.getId)
            handler should not be null
            handler.cp shouldBe containerPort(host, port, container, group)
            handler.namespace shouldBe "test-ns"

            And("The handler create method should be called")
            val h = handler.handler.asInstanceOf[TestContainer]
            h.created shouldBe 1

            When("Deleting the container")
            store.delete(classOf[ServiceContainer], container.getId)

            Then("The service should stop the container")
            h.deleted shouldBe 1
            service.handlerList shouldBe empty

            service.stopAsync().awaitTerminated()
        }

        scenario("Restart the container when the binding interface changes") {
            Given("A port bound to a host with a container")
            val host = createHost()
            val bridge = createBridge()
            val group = createServiceContainerGroup()
            val container = createServiceContainer(configurationId = Some(randomUUID),
                                                   serviceType = Some("test-error-update"),
                                                   groupId = Some(group.getId))
            val port1 = createBridgePort(bridgeId = Some(bridge.getId),
                                        hostId = Some(host.getId),
                                        interfaceName = Some("veth1"),
                                        containerId = Some(container.getId))
            store.multi(Seq(CreateOp(host), CreateOp(bridge), CreateOp(group),
                            CreateOp(container), CreateOp(port1)))

            And("A container service")
            val service = new ContainerService(vt, host.getId, vt.vtExecutor)
            service.startAsync().awaitRunning()

            Then("The service should start the container")
            val handler1 = service.handlerOf(port1.getId)
            handler1 should not be null
            handler1.cp shouldBe containerPort(host, port1, container, group)
            handler1.namespace shouldBe "test-ns"

            And("The handler create method should be called")
            val h1 = handler1.handler.asInstanceOf[TestContainer]
            h1.created shouldBe 1

            When("Changing the port binding interface")
            val port2 = port1.setInterfaceName("veth2")
            store.update(port2)

            Then("The service should stop the container")
            h1.deleted shouldBe 1

            And("The service should create a new container")
            val handler2 = service.handlerOf(port1.getId)
            handler2 should not be null
            handler2 should not be handler1
            handler2.cp shouldBe containerPort(host, port2, container, group)
            handler2.namespace shouldBe "test-ns"

            And("The second handler create method should be called")
            val h2 = handler1.handler.asInstanceOf[TestContainer]
            h2.created shouldBe 1

            When("Stopping the service")
            service.stopAsync().awaitTerminated()

            Then("The second handler delete method should be called")
            h2.deleted shouldBe 1
        }

        scenario("Service handles multiple containers") {
            Given("A port bound to a host with a container")
            val host = createHost()
            val bridge = createBridge()
            val group = createServiceContainerGroup()
            val container1 = createServiceContainer(configurationId = Some(randomUUID),
                                                    serviceType = Some("test-good"),
                                                    groupId = Some(group.getId))
            val port1 = createBridgePort(bridgeId = Some(bridge.getId),
                                         hostId = Some(host.getId),
                                         interfaceName = Some("veth1"),
                                         containerId = Some(container1.getId))
            store.multi(Seq(CreateOp(host), CreateOp(bridge), CreateOp(group),
                            CreateOp(container1), CreateOp(port1)))

            And("A container service")
            val service = new ContainerService(vt, host.getId, vt.vtExecutor)
            service.startAsync().awaitRunning()

            Then("The service should start the first container")
            val handler1 = service.handlerOf(port1.getId)
            val h1 = handler1.handler.asInstanceOf[TestContainer]
            h1.created shouldBe 1

            When("Adding a second binding with a container")
            val container2 = createServiceContainer(configurationId = Some(randomUUID),
                                                    serviceType = Some("test-good"),
                                                    groupId = Some(group.getId))
            val port2 = createBridgePort(bridgeId = Some(bridge.getId),
                                         hostId = Some(host.getId),
                                         interfaceName = Some("veth2"),
                                         containerId = Some(container2.getId))
            store.multi(Seq(CreateOp(container2), CreateOp(port2)))

            Then("The service should start the second container")
            val handler2 = service.handlerOf(port2.getId)
            handler2 should not be null
            handler2 should not be handler1
            handler2.cp shouldBe containerPort(host, port2, container2, group)
            handler2.namespace shouldBe "test-ns"

            val h2 = handler2.handler.asInstanceOf[TestContainer]
            h2.created shouldBe 1

            And("The service should have two containers")
            service.handlerList should have size 2

            When("Deleting the first port binding")
            store.delete(classOf[Port], port1.getId)

            Then("The first container should be stopped")
            h1.deleted shouldBe 1

            And("The service should have one container")
            service.handlerList should have size 1

            When("Updating the configuration of the second container")
            val container3 = container2.setConfigurationId(randomUUID)
                                       .setPortId(port2.getId)
            store.update(container3)

            Then("The second container should receive an update")
            h2.updated shouldBe 1

            When("Stopping the service")
            service.stopAsync().awaitTerminated()

            Then("The handler delete method for the second container should be called")
            h2.deleted shouldBe 1
        }

        scenario("Service stops all active containers") {
            Given("A port bound to a host with two containers")
            val host = createHost()
            val bridge = createBridge()
            val group = createServiceContainerGroup()
            val container1 = createServiceContainer(configurationId = Some(randomUUID),
                                                    serviceType = Some("test-error-delete"),
                                                    groupId = Some(group.getId))
            val container2 = createServiceContainer(configurationId = Some(randomUUID),
                                                    serviceType = Some("test-error-delete"),
                                                    groupId = Some(group.getId))
            val port1 = createBridgePort(bridgeId = Some(bridge.getId),
                                         hostId = Some(host.getId),
                                         interfaceName = Some("veth1"),
                                         containerId = Some(container1.getId))
            val port2 = createBridgePort(bridgeId = Some(bridge.getId),
                                         hostId = Some(host.getId),
                                         interfaceName = Some("veth1"),
                                         containerId = Some(container2.getId))
            store.multi(Seq(CreateOp(host), CreateOp(bridge), CreateOp(group),
                            CreateOp(container1), CreateOp(port1),
                            CreateOp(container2), CreateOp(port2)))

            And("A container service")
            val service = new ContainerService(vt, host.getId, vt.vtExecutor)
            service.startAsync().awaitRunning()

            Then("The service should start the both containers")
            val handler1 = service.handlerOf(port1.getId)
            val h1 = handler1.handler.asInstanceOf[TestContainer]
            h1.created shouldBe 1

            val handler2 = service.handlerOf(port2.getId)
            val h2 = handler2.handler.asInstanceOf[TestContainer]
            h2.created shouldBe 1

            When("Stopping the service")
            service.stopAsync().awaitTerminated()

            Then("The handler delete method should be called for both containers")
            h1.deleted shouldBe 1
            h2.deleted shouldBe 1
        }

        scenario("Service stops all containers when update stream completes") {
            Given("A port bound to a host with two containers")
            val host = createHost()
            val bridge = createBridge()
            val group = createServiceContainerGroup()
            val container1 = createServiceContainer(configurationId = Some(randomUUID),
                                                    serviceType = Some("test-error-delete"),
                                                    groupId = Some(group.getId))
            val container2 = createServiceContainer(configurationId = Some(randomUUID),
                                                    serviceType = Some("test-error-delete"),
                                                    groupId = Some(group.getId))
            val port1 = createBridgePort(bridgeId = Some(bridge.getId),
                                         hostId = Some(host.getId),
                                         interfaceName = Some("veth1"),
                                         containerId = Some(container1.getId))
            val port2 = createBridgePort(bridgeId = Some(bridge.getId),
                                         hostId = Some(host.getId),
                                         interfaceName = Some("veth1"),
                                         containerId = Some(container2.getId))
            store.multi(Seq(CreateOp(host), CreateOp(bridge), CreateOp(group),
                            CreateOp(container1), CreateOp(port1),
                            CreateOp(container2), CreateOp(port2)))

            And("A container service")
            val service = new ContainerService(vt, host.getId, vt.vtExecutor)
            service.startAsync().awaitRunning()

            Then("The service should start the both containers")
            val handler1 = service.handlerOf(port1.getId)
            val h1 = handler1.handler.asInstanceOf[TestContainer]
            h1.created shouldBe 1

            val handler2 = service.handlerOf(port2.getId)
            val h2 = handler2.handler.asInstanceOf[TestContainer]
            h2.created shouldBe 1

            When("Deleting the host to complete the notification stream")
            store.delete(classOf[Host], host.getId)

            Then("The handler delete method should be called for both containers")
            h1.deleted shouldBe 1
            h2.deleted shouldBe 1

            And("The service should not have any active containers")
            service.handlerList shouldBe empty

            service.stopAsync().awaitTerminated()
        }
    }

    feature("Test service updates container status") {
        scenario("Status updated for good container") {
            Given("A port bound to a host with a container")
            val host = createHost()
            val bridge = createBridge()
            val group = createServiceContainerGroup()
            val container = createServiceContainer(configurationId = Some(randomUUID),
                                                   serviceType = Some("test-good"),
                                                   groupId = Some(group.getId))
            val port = createBridgePort(bridgeId = Some(bridge.getId),
                                        hostId = Some(host.getId),
                                        interfaceName = Some("veth"),
                                        containerId = Some(container.getId))
            store.multi(Seq(CreateOp(host), CreateOp(bridge), CreateOp(group),
                            CreateOp(container), CreateOp(port)))

            And("A container service")
            val service = new ContainerService(vt, host.getId, vt.vtExecutor)
            service.startAsync().awaitRunning()

            Then("The container status should be STARTING")
            getStatus(container.getId).get.getStatusCode shouldBe Code.STARTING

            When("Stopping the service")
            service.stopAsync().awaitTerminated()

            Then("The container status should not be set")
            getStatus(container.getId) shouldBe None
        }

        scenario("Status is cleared on completed notification") {
            Given("A port bound to a host with a container")
            val host = createHost()
            val bridge = createBridge()
            val group = createServiceContainerGroup()
            val container = createServiceContainer(configurationId = Some(randomUUID),
                                                   serviceType = Some("test-good"),
                                                   groupId = Some(group.getId))
            val port = createBridgePort(bridgeId = Some(bridge.getId),
                                        hostId = Some(host.getId),
                                        interfaceName = Some("veth"),
                                        containerId = Some(container.getId))
            store.multi(Seq(CreateOp(host), CreateOp(bridge), CreateOp(group),
                            CreateOp(container), CreateOp(port)))

            And("A container service")
            val service = new ContainerService(vt, host.getId, vt.vtExecutor)
            service.startAsync().awaitRunning()

            Then("The container status should be STARTING")
            getStatus(container.getId).get.getStatusCode shouldBe Code.STARTING

            When("The container completes the status observable")
            val handler = service.handlerOf(port.getId)
            val h = handler.handler.asInstanceOf[TestContainer]
            h.stream.onCompleted()

            Then("The container status should not be set")
            getStatus(container.getId) shouldBe None

            service.stopAsync().awaitTerminated()
        }

        scenario("Status set to ERROR on error notification") {
            Given("A port bound to a host with a container")
            val host = createHost()
            val bridge = createBridge()
            val group = createServiceContainerGroup()
            val container = createServiceContainer(configurationId = Some(randomUUID),
                                                   serviceType = Some("test-good"),
                                                   groupId = Some(group.getId))
            val port = createBridgePort(bridgeId = Some(bridge.getId),
                                        hostId = Some(host.getId),
                                        interfaceName = Some("veth"),
                                        containerId = Some(container.getId))
            store.multi(Seq(CreateOp(host), CreateOp(bridge), CreateOp(group),
                            CreateOp(container), CreateOp(port)))

            And("A container service")
            val service = new ContainerService(vt, host.getId, vt.vtExecutor)
            service.startAsync().awaitRunning()

            Then("The container status should be STARTING")
            getStatus(container.getId).get.getStatusCode shouldBe Code.STARTING

            When("The container completes the status observable")
            val handler = service.handlerOf(port.getId)
            val h = handler.handler.asInstanceOf[TestContainer]
            h.stream onError new Exception("some message")

            Then("The container status should not be set")
            val status = getStatus(container.getId).get
            status.getStatusCode shouldBe Code.ERROR
            status.getStatusMessage shouldBe "some message"

            service.stopAsync().awaitTerminated()
        }

        scenario("Status set for health notifications") {
            Given("A port bound to a host with a container")
            val host = createHost()
            val bridge = createBridge()
            val group = createServiceContainerGroup()
            val container = createServiceContainer(configurationId = Some(randomUUID),
                                                   serviceType = Some("test-good"),
                                                   groupId = Some(group.getId))
            val port = createBridgePort(bridgeId = Some(bridge.getId),
                                        hostId = Some(host.getId),
                                        interfaceName = Some("veth"),
                                        containerId = Some(container.getId))
            store.multi(Seq(CreateOp(host), CreateOp(bridge), CreateOp(group),
                            CreateOp(container), CreateOp(port)))

            And("A container service")
            val service = new ContainerService(vt, host.getId, vt.vtExecutor)
            service.startAsync().awaitRunning()

            Then("The container status should be STARTING")
            getStatus(container.getId).get.getStatusCode shouldBe Code.STARTING

            When("The container sets a health status")
            val handler = service.handlerOf(port.getId)
            val h = handler.handler.asInstanceOf[TestContainer]
            h.stream onNext ContainerHealth(Code.RUNNING, "all good")

            Then("The container status should not be set")
            val status1 = getStatus(container.getId).get
            status1.getStatusCode shouldBe Code.RUNNING
            status1.getStatusMessage shouldBe "all good"

            When("The container sets a health status")
            h.stream onNext ContainerHealth(Code.ERROR, "epic fail")

            Then("The container status should not be set")
            val status2 = getStatus(container.getId).get
            status2.getStatusCode shouldBe Code.ERROR
            status2.getStatusMessage shouldBe "epic fail"

            When("The container sets a health status")
            h.stream onNext ContainerHealth(Code.RUNNING, "all good again")

            Then("The container status should not be set")
            val status3 = getStatus(container.getId).get
            status3.getStatusCode shouldBe Code.RUNNING
            status3.getStatusMessage shouldBe "all good again"

            service.stopAsync().awaitTerminated()
        }
    }

    feature("Service handles exceptions thrown by the container handlers") {
        scenario("Exception thrown on create") {
            Given("A port bound to a host with a container")
            val host = createHost()
            val bridge = createBridge()
            val group = createServiceContainerGroup()
            val container = createServiceContainer(configurationId = Some(randomUUID),
                                                   serviceType = Some("test-throws-create"),
                                                   groupId = Some(group.getId))
            val port = createBridgePort(bridgeId = Some(bridge.getId),
                                        hostId = Some(host.getId),
                                        interfaceName = Some("veth"),
                                        containerId = Some(container.getId))
            store.multi(Seq(CreateOp(host), CreateOp(bridge), CreateOp(group),
                            CreateOp(container), CreateOp(port)))

            And("A container service")
            val service = new ContainerService(vt, host.getId, vt.vtExecutor)
            service.startAsync().awaitRunning()

            Then("The service should not have the container")
            service.handlerOf(port.getId) shouldBe null

            When("Stopping the service")
            service.stopAsync().awaitTerminated()
        }

        scenario("Exception thrown on update") {
            Given("A port bound to a host with a container")
            val host = createHost()
            val bridge = createBridge()
            val group = createServiceContainerGroup()
            val container1 = createServiceContainer(configurationId = Some(randomUUID),
                                                    serviceType = Some("test-throws-update"),
                                                    groupId = Some(group.getId))
            val port = createBridgePort(bridgeId = Some(bridge.getId),
                                        hostId = Some(host.getId),
                                        interfaceName = Some("veth"),
                                        containerId = Some(container1.getId))
            store.multi(Seq(CreateOp(host), CreateOp(bridge), CreateOp(group),
                            CreateOp(container1), CreateOp(port)))

            And("A container service")
            val service = new ContainerService(vt, host.getId, vt.vtExecutor)
            service.startAsync().awaitRunning()

            Then("The service should start the container")
            val handler = service.handlerOf(port.getId)
            handler should not be null
            handler.cp shouldBe containerPort(host, port, container1, group)
            handler.namespace shouldBe "test-ns"

            And("The handler create method should be called")
            val h = handler.handler.asInstanceOf[TestContainer]
            h.created shouldBe 1

            When("Updating the container configuration")
            val container2 = container1.setPortId(port.getId)
                .setConfigurationId(randomUUID)
            store.update(container2)

            Then("The handler update method should be called")
            h.updated shouldBe 1

            When("Stopping the service")
            service.stopAsync().awaitTerminated()

            Then("The handler delete method should be called")
            h.deleted shouldBe 1
        }

        scenario("Exception thrown on delete") {
            Given("A port bound to a host with a container")
            val host = createHost()
            val bridge = createBridge()
            val group = createServiceContainerGroup()
            val container1 = createServiceContainer(configurationId = Some(randomUUID),
                                                    serviceType = Some("test-throws-delete"),
                                                    groupId = Some(group.getId))
            val port = createBridgePort(bridgeId = Some(bridge.getId),
                                        hostId = Some(host.getId),
                                        interfaceName = Some("veth"),
                                        containerId = Some(container1.getId))
            store.multi(Seq(CreateOp(host), CreateOp(bridge), CreateOp(group),
                            CreateOp(container1), CreateOp(port)))

            And("A container service")
            val service = new ContainerService(vt, host.getId, vt.vtExecutor)
            service.startAsync().awaitRunning()

            Then("The service should start the container")
            val handler = service.handlerOf(port.getId)
            handler should not be null
            handler.cp shouldBe containerPort(host, port, container1, group)
            handler.namespace shouldBe "test-ns"

            And("The handler create method should be called")
            val h = handler.handler.asInstanceOf[TestContainer]
            h.created shouldBe 1

            When("Updating the container configuration")
            val container2 = container1.setPortId(port.getId)
                .setConfigurationId(randomUUID)
            store.update(container2)

            Then("The handler update method should be called")
            h.updated shouldBe 1

            When("Stopping the service")
            service.stopAsync().awaitTerminated()

            Then("The handler delete method should be called")
            h.deleted shouldBe 1
        }
    }

}
