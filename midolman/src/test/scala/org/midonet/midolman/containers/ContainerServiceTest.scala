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

package org.midonet.midolman.containers

import java.nio.file.{FileSystems, Files}
import java.util.UUID
import java.util.UUID._
import java.util.concurrent.{Callable, ExecutorService, ScheduledExecutorService}
import java.util.concurrent.atomic.AtomicInteger

import scala.collection.JavaConverters._
import scala.concurrent.Future

import com.google.common.util.concurrent.SettableFuture
import com.google.protobuf.TextFormat

import org.apache.commons.io.FileUtils
import org.apache.curator.framework.state.ConnectionState.RECONNECTED
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.reflections.Reflections
import org.scalatest.junit.JUnitRunner

import rx.Observable
import rx.subjects.PublishSubject

import org.midonet.cluster.data.storage._
import org.midonet.cluster.models.State.ContainerStatus.Code
import org.midonet.cluster.models.State.{ContainerServiceStatus, ContainerStatus => BackendStatus}
import org.midonet.cluster.models.Topology.{Host, Port, ServiceContainer, ServiceContainerGroup}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.MidonetBackend.{ContainerKey, StatusKey}
import org.midonet.cluster.storage.MidonetTestBackend
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.containers._
import org.midonet.containers.models.Containers.Log
import org.midonet.midolman.containers.ContainerServiceTest.{GoodContainer, TestContainer, TestNamespace}
import org.midonet.midolman.topology.VirtualTopology
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.util.MidonetEventually
import org.midonet.util.concurrent.SameThreadButAfterExecutorService
import org.midonet.util.reactivex._

object ContainerServiceTest {

    val TestConfig = "test-config"
    val TestNamespace = "test-ns"

    abstract class TestContainer(errorOnCreate: Boolean = false,
                                 errorOnUpdate: Boolean = false,
                                 errorOnDelete: Boolean = false,
                                 errorOnCleanup: Boolean = false,
                                 throwsOnCreate: Boolean = false,
                                 throwsOnUpdate: Boolean = false,
                                 throwsOnDelete: Boolean = false,
                                 throwsOnCleanup: Boolean = false)
        extends ContainerHandler {

        val stream = PublishSubject.create[ContainerStatus]
        var created: Int = 0
        var updated: Int = 0
        var deleted: Int = 0

        override def create(port: ContainerPort): Future[Option[String]] = {
            created += 1
            if (throwsOnCreate) throw new Throwable()
            if (errorOnCreate) Future.failed(new Throwable())
            else Future.successful(Some(TestNamespace))
        }

        override def updated(port: ContainerPort): Future[Option[String]] = {
            updated += 1
            if (throwsOnUpdate) throw new Throwable()
            if (errorOnUpdate) Future.failed(new Throwable())
            else Future.successful(Some(TestNamespace))
        }

        override def delete(): Future[Unit] = {
            deleted += 1
            if (throwsOnDelete) throw new Throwable()
            if (errorOnDelete) Future.failed(new Throwable())
            else Future.successful(Some(TestNamespace))
        }

        override def cleanup(config: String): Future[Unit] = {
            if (throwsOnCleanup) throw new Throwable()
            if (errorOnCleanup) Future.failed(new Throwable())
            else Future.successful(())
        }

        override def status: Observable[ContainerStatus] = stream
    }

    object GoodContainer {
        val cleaned = new AtomicInteger()
    }

    @Container(name = "test-good", version = 1)
    class GoodContainer extends TestContainer() {
        override def cleanup(config: String): Future[Unit] = {
            GoodContainer.cleaned.incrementAndGet()
            super.cleanup(config)
        }
    }

    @Container(name = "test-error-create", version = 1)
    class ErrorCreateContainer extends TestContainer(errorOnCreate = true)

    @Container(name = "test-error-update", version = 1)
    class ErrorUpdateContainer extends TestContainer(errorOnUpdate = true)

    @Container(name = "test-error-delete", version = 1)
    class ErrorDeleteContainer extends TestContainer(errorOnDelete = true)

    @Container(name = "test-error-cleanup", version = 1)
    class ErrorCleanupContainer extends TestContainer(errorOnCleanup = true)

    @Container(name = "test-throws-create", version = 1)
    class ThrowsCreateContainer extends TestContainer(throwsOnCreate = true)

    @Container(name = "test-throws-update", version = 1)
    class ThrowsUpdateContainer extends TestContainer(throwsOnUpdate = true)

    @Container(name = "test-throws-delete", version = 1)
    class ThrowsDeleteContainer extends TestContainer(throwsOnDelete = true)

    @Container(name = "test-throws-cleanup", version = 1)
    class ThrowsCleanupContainer extends TestContainer(throwsOnCleanup = true)

}

@RunWith(classOf[JUnitRunner])
class ContainerServiceTest extends MidolmanSpec with TopologyBuilder
                                   with MidonetEventually {

    import TopologyBuilder._

    private var backend: MidonetBackend = _
    private var store: Storage = _
    private var stateStore: StateStorage = _
    private var vt: VirtualTopology = _
    private val reflections: Set[Class[_]] =
        new Reflections("org.midonet.midolman.containers")
            .getTypesAnnotatedWith(classOf[Container]).asScala.toSet
    private var serviceExecutor: ExecutorService = _
    private val ioExecutor = Mockito.mock(classOf[ScheduledExecutorService])
    private var executors: ContainerExecutors = _

    private val logDir = s"${FileUtils.getTempDirectory}/${UUID.randomUUID}"

    protected override def beforeTest(): Unit = {
        System.setProperty("midolman.log.dir", logDir)
        Files.createDirectories(FileSystems.getDefault.getPath(logDir))
        vt = injector.getInstance(classOf[VirtualTopology])
        backend = injector.getInstance(classOf[MidonetBackend])
        store = backend.store
        stateStore = backend.stateStore
        serviceExecutor = new SameThreadButAfterExecutorService
        executors = new ContainerExecutors(vt.config.containers) {
            override def newExecutor(index: Int): ExecutorService = {
                new SameThreadButAfterExecutorService
            }
        }
    }

    private def containerPort(host: Host, port: Port,
                              container: ServiceContainer,
                              group: ServiceContainerGroup)
    : ContainerPort = {
        ContainerPort(port.getId, host.getId, port.getInterfaceName,
                      container.getId, container.getServiceType, group.getId,
                      container.getConfigurationId)
    }

    private def getServiceStatus(hostId: UUID): Option[ContainerServiceStatus] = {
        stateStore.getKey(classOf[Host], hostId, ContainerKey)
                  .await() match {
            case key: SingleValueKey =>
                key.value.map { s =>
                    val builder = ContainerServiceStatus.newBuilder()
                    TextFormat.merge(s, builder)
                    builder.build()
                }
        }
    }

    private def getContainerStatus(containerId: UUID): Option[BackendStatus] = {
        stateStore.getKey(classOf[ServiceContainer], containerId, StatusKey)
                  .await() match {
            case key: SingleValueKey =>
                key.value.map { s =>
                    val builder = BackendStatus.newBuilder()
                    TextFormat.merge(s, builder)
                    builder.build()
                }
        }
    }

    private def checkContainerKey(hostId: UUID, status: String): Unit = {
        val statusFailure = "The container key should be a single value key " +
                            s"containing the container status: $status"
        val nullStatusFailure = "The container key should be absent"

        val key = stateStore.getKey(classOf[Host], hostId, ContainerKey).await()

        key match {
            case SingleValueKey(ContainerKey, Some(s), _) =>
                if (status == null) {
                    fail(nullStatusFailure)
                } else if (status != s) {
                    fail(statusFailure)
                }
            case SingleValueKey(ContainerKey, None, _) =>
                if (status != null) {
                    fail(statusFailure)
                }
            case x => fail(s"Incorrect container key: $x")
        }
    }

    feature("Test service lifecycle") {
        scenario("Service starts and stops") {
            Given("A container service")
            val service = new ContainerService(vt, hostId, serviceExecutor,
                                               executors, ioExecutor, reflections)

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
            val service = new ContainerService(vt, randomUUID, serviceExecutor,
                                               executors, ioExecutor, reflections)

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
            val service = new ContainerService(vt, host.getId, serviceExecutor,
                                               executors, ioExecutor, reflections)
            service.startAsync().awaitRunning()

            Then("The service should not have the container")
            service.handlerOf(port.getId) shouldBe None

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
            val service = new ContainerService(vt, host.getId, serviceExecutor,
                                               executors, ioExecutor, reflections)
            service.startAsync().awaitRunning()

            Then("The service should start the container")
            val handler = service.handlerOf(port.getId)
            handler should not be None
            handler.get.cp shouldBe containerPort(host, port, container1, group)

            And("The handler create method should be called")
            val h = handler.get.handler.asInstanceOf[TestContainer]
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
            val service = new ContainerService(vt, host.getId, serviceExecutor,
                                               executors, ioExecutor, reflections)
            service.startAsync().awaitRunning()

            Then("The service should not have the container")
            service.handlerOf(port.getId) shouldBe None

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
            val service = new ContainerService(vt, host.getId, serviceExecutor,
                                               executors, ioExecutor, reflections)
            service.startAsync().awaitRunning()

            Then("The service should start the container")
            val handler = service.handlerOf(port.getId)
            handler should not be None
            handler.get.cp shouldBe containerPort(host, port, container1, group)

            And("The handler create method should be called")
            val h = handler.get.handler.asInstanceOf[TestContainer]
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
            val service = new ContainerService(vt, host.getId, serviceExecutor,
                                               executors, ioExecutor, reflections)
            service.startAsync().awaitRunning()

            Then("The service should start the container")
            val handler = service.handlerOf(port.getId)
            handler should not be None
            handler.get.cp shouldBe containerPort(host, port, container1, group)

            And("The handler create method should be called")
            val h = handler.get.handler.asInstanceOf[TestContainer]
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

            val service = new ContainerService(vt, host.getId, serviceExecutor,
                                               executors, ioExecutor, reflections)
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
            handler should not be None
            handler.get.cp shouldBe containerPort(host, port, container1, group)

            And("The handler create method should be called")
            val h = handler.get.handler.asInstanceOf[TestContainer]
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
            val service = new ContainerService(vt, host.getId, serviceExecutor,
                                               executors, ioExecutor, reflections)
            service.startAsync().awaitRunning()

            Then("The service should start the container")
            val handler = service.handlerOf(port.getId)
            handler should not be None
            handler.get.cp shouldBe containerPort(host, port, container, group)

            And("The handler create method should be called")
            val h = handler.get.handler.asInstanceOf[TestContainer]
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
            val service = new ContainerService(vt, host1.getId, serviceExecutor,
                                               executors, ioExecutor, reflections)
            service.startAsync().awaitRunning()

            Then("The service should start the container")
            val handler = service.handlerOf(port1.getId)
            handler should not be None
            handler.get.cp shouldBe containerPort(host1, port1, container, group)

            And("The handler create method should be called")
            val h = handler.get.handler.asInstanceOf[TestContainer]
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
            val service = new ContainerService(vt, host.getId, serviceExecutor,
                                               executors, ioExecutor, reflections)
            service.startAsync().awaitRunning()

            Then("The service should start the container")
            val handler = service.handlerOf(port.getId)
            handler should not be None
            handler.get.cp shouldBe containerPort(host, port, container, group)

            And("The handler create method should be called")
            val h = handler.get.handler.asInstanceOf[TestContainer]
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
            val service = new ContainerService(vt, host.getId, serviceExecutor,
                                               executors, ioExecutor, reflections)
            service.startAsync().awaitRunning()

            Then("The service should start the container")
            val handler1 = service.handlerOf(port1.getId)
            handler1 should not be None
            handler1.get.cp shouldBe containerPort(host, port1, container, group)

            And("The handler create method should be called")
            val h1 = handler1.get.handler.asInstanceOf[TestContainer]
            h1.created shouldBe 1

            When("Changing the port binding interface")
            val port2 = port1.setInterfaceName("veth2")
            store.update(port2)

            Then("The service should stop the container")
            h1.deleted shouldBe 1

            And("The service should create a new container")
            val handler2 = service.handlerOf(port1.getId)
            handler2 should not be None
            handler2 should not be handler1
            handler2.get.cp shouldBe containerPort(host, port2, container, group)

            And("The second handler create method should be called")
            val h2 = handler1.get.handler.asInstanceOf[TestContainer]
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
            val service = new ContainerService(vt, host.getId, serviceExecutor,
                                               executors, ioExecutor, reflections)
            service.startAsync().awaitRunning()

            Then("The service should start the first container")
            val handler1 = service.handlerOf(port1.getId)
            val h1 = handler1.get.handler.asInstanceOf[TestContainer]
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
            handler2 should not be None
            handler2 should not be handler1
            handler2.get.cp shouldBe containerPort(host, port2, container2, group)

            val h2 = handler2.get.handler.asInstanceOf[TestContainer]
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
            val service = new ContainerService(vt, host.getId, serviceExecutor,
                                               executors, ioExecutor, reflections)
            service.startAsync().awaitRunning()

            Then("The service should start the both containers")
            val handler1 = service.handlerOf(port1.getId)
            val h1 = handler1.get.handler.asInstanceOf[TestContainer]
            h1.created shouldBe 1

            val handler2 = service.handlerOf(port2.getId)
            val h2 = handler2.get.handler.asInstanceOf[TestContainer]
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
            val service = new ContainerService(vt, host.getId, serviceExecutor,
                                               executors, ioExecutor, reflections)
            service.startAsync().awaitRunning()

            Then("The service should start the both containers")
            val handler1 = service.handlerOf(port1.getId)
            val h1 = handler1.get.handler.asInstanceOf[TestContainer]
            h1.created shouldBe 1

            val handler2 = service.handlerOf(port2.getId)
            val h2 = handler2.get.handler.asInstanceOf[TestContainer]
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

    feature("Test service updates handles container weight and limit") {
        scenario("Status updates include the container weight") {
            Given("A host with container weight")
            val host = createHost(containerWeight = 10)
            store.create(host)

            And("A container service")
            val service = new ContainerService(vt, host.getId, serviceExecutor,
                                               executors, ioExecutor, reflections)
            service.startAsync().awaitRunning()

            Then("The container service status should be set")
            getServiceStatus(host.getId).get.getWeight shouldBe 10

            When("The host updates the container weight")
            store.update(host.toBuilder.setContainerWeight(11).build())

            Then("The container service status should be set")
            getServiceStatus(host.getId).get.getWeight shouldBe 11

            service.stopAsync().awaitTerminated()
        }

        scenario("Status updates include the container count and quota") {
            Given("A host with container limit")
            val host = createHost(containerLimit = 10)
            store.create(host)

            And("A container service")
            val service = new ContainerService(vt, host.getId, serviceExecutor,
                                               executors, ioExecutor, reflections)
            service.startAsync().awaitRunning()

            Then("The container service status should be set")
            getServiceStatus(host.getId).get.getCount shouldBe 0
            getServiceStatus(host.getId).get.getQuota shouldBe 10

            When("The host updates the container weight")
            store.update(host.toBuilder.setContainerLimit(11).build())

            Then("The container service status should be set")
            getServiceStatus(host.getId).get.getCount shouldBe 0
            getServiceStatus(host.getId).get.getQuota shouldBe 11

            service.stopAsync().awaitTerminated()
        }

        scenario("Count and quota are updated when creating containers") {
            Given("A host with container limit")
            val host = createHost(containerLimit = 10)
            store.create(host)

            And("A container service")
            val service = new ContainerService(vt, host.getId, serviceExecutor,
                                               executors, ioExecutor, reflections)
            service.startAsync().awaitRunning()

            Then("The container service status should be set")
            getServiceStatus(host.getId).get.getCount shouldBe 0
            getServiceStatus(host.getId).get.getQuota shouldBe 10

            When("Creating a container")
            val bridge = createBridge()
            val group = createServiceContainerGroup()
            val container = createServiceContainer(configurationId = Some(randomUUID),
                                                   serviceType = Some("test-good"),
                                                   groupId = Some(group.getId))
            val port = createBridgePort(bridgeId = Some(bridge.getId),
                                        hostId = Some(host.getId),
                                        interfaceName = Some("veth1"),
                                        containerId = Some(container.getId))
            store.multi(Seq(CreateOp(bridge), CreateOp(group),
                            CreateOp(container), CreateOp(port)))

            Then("The service should start the container")
            val handler = service.handlerOf(port.getId).get
            val h = handler.handler.asInstanceOf[TestContainer]
            h.created shouldBe 1

            Then("The container count and quota should be updated")
            getServiceStatus(host.getId).get.getCount shouldBe 1
            getServiceStatus(host.getId).get.getQuota shouldBe 9

            service.stopAsync().awaitTerminated()
        }

        scenario("Quota is updated when the limit is changed") {
            Given("A host with container limit")
            val host1 = createHost(containerLimit = 10)
            store.create(host1)

            And("A container service")
            val service = new ContainerService(vt, host1.getId, serviceExecutor,
                                               executors, ioExecutor, reflections)
            service.startAsync().awaitRunning()

            Then("The container service status should be set")
            getServiceStatus(host1.getId).get.getCount shouldBe 0
            getServiceStatus(host1.getId).get.getQuota shouldBe 10

            When("The limit is updated")
            val host2 = host1.toBuilder.setContainerLimit(20).build()
            store.update(host2)

            Then("The container service should contain the updated limit")
            getServiceStatus(host1.getId).get.getCount shouldBe 0
            getServiceStatus(host1.getId).get.getQuota shouldBe 20
        }

        scenario("Count and quota are not updated when container fails to start") {
            Given("A host with container limit")
            val host = createHost(containerLimit = 10)
            store.create(host)

            And("A container service")
            val service = new ContainerService(vt, host.getId, serviceExecutor,
                                               executors, ioExecutor, reflections)
            service.startAsync().awaitRunning()

            Then("The container service status should be set")
            getServiceStatus(host.getId).get.getCount shouldBe 0
            getServiceStatus(host.getId).get.getQuota shouldBe 10

            When("Creating a container")
            val bridge = createBridge()
            val group = createServiceContainerGroup()
            val container = createServiceContainer(configurationId = Some(randomUUID),
                                                   serviceType = Some("test-error-create"),
                                                   groupId = Some(group.getId))
            val port = createBridgePort(bridgeId = Some(bridge.getId),
                                        hostId = Some(host.getId),
                                        interfaceName = Some("veth1"),
                                        containerId = Some(container.getId))
            store.multi(Seq(CreateOp(bridge), CreateOp(group),
                            CreateOp(container), CreateOp(port)))

            Then("The service should not start the container")
            service.handlerOf(port.getId) shouldBe None

            Then("The container count and quota should not be updated")
            getServiceStatus(host.getId).get.getCount shouldBe 0
            getServiceStatus(host.getId).get.getQuota shouldBe 10

            service.stopAsync().awaitTerminated()
        }

        def testCountAndQuotaOnDelete(containerName: String): Unit = {
            Given("A host with container limit and a container")
            val host = createHost(containerLimit = 10)
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
            val service = new ContainerService(vt, host.getId, serviceExecutor,
                                               executors, ioExecutor, reflections)
            service.startAsync().awaitRunning()

            Then("The service should start the container")
            val handler = service.handlerOf(port.getId).get
            val h = handler.handler.asInstanceOf[TestContainer]
            h.created shouldBe 1

            Then("The container count and quota should be updated")
            getServiceStatus(host.getId).get.getCount shouldBe 1
            getServiceStatus(host.getId).get.getQuota shouldBe 9

            When("Deleting the port binding")
            store.delete(classOf[Port], port.getId)

            Then("The service should stop the container")
            h.deleted shouldBe 1
            service.handlerList shouldBe empty

            And("The count and quota should be updated")
            getServiceStatus(host.getId).get.getCount shouldBe 0
            getServiceStatus(host.getId).get.getQuota shouldBe 10

            service.stopAsync().awaitTerminated()
        }

        scenario("Quota should be incremented when container stops") {
            testCountAndQuotaOnDelete("test-good")
        }

        scenario("Quota should be incremented when container fails to stop") {
            testCountAndQuotaOnDelete("test-error-delete")
        }

        scenario("Container fails to start if limit exceeded") {
            Given("A host with container limit and a container")
            val host = createHost(containerLimit = 0)
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
            val service = new ContainerService(vt, host.getId, serviceExecutor,
                                               executors, ioExecutor, reflections)
            service.startAsync().awaitRunning()

            Then("The service should not start the container")
            service.handlerOf(port.getId) shouldBe None

            And("The quota should be zero")
            getServiceStatus(host.getId).get.getQuota shouldBe 0

            And("The container status should be ERROR")
            val status = getContainerStatus(container.getId).get
            status.getStatusCode shouldBe Code.ERROR
            status.getStatusMessage shouldBe "LIMIT_EXCEEDED"

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
            val service = new ContainerService(vt, host.getId, serviceExecutor,
                                               executors, ioExecutor, reflections)
            service.startAsync().awaitRunning()

            Then("The container status should be STARTING")
            getContainerStatus(container.getId).get.getStatusCode shouldBe Code.STARTING

            When("Stopping the service")
            service.stopAsync().awaitTerminated()

            Then("The container status should not be set")
            getContainerStatus(container.getId) shouldBe None
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
            val service = new ContainerService(vt, host.getId, serviceExecutor,
                                               executors, ioExecutor, reflections)
            service.startAsync().awaitRunning()

            Then("The container status should be STARTING")
            getContainerStatus(container.getId).get.getStatusCode shouldBe Code.STARTING

            When("The container completes the status observable")
            val handler = service.handlerOf(port.getId)
            val h = handler.get.handler.asInstanceOf[TestContainer]
            h.stream.onCompleted()

            Then("The container status should not be set")
            getContainerStatus(container.getId) shouldBe None

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
            val service = new ContainerService(vt, host.getId, serviceExecutor,
                                               executors, ioExecutor, reflections)
            service.startAsync().awaitRunning()

            Then("The container status should be STARTING")
            getContainerStatus(container.getId).get.getStatusCode shouldBe Code.STARTING

            When("The container completes the status observable")
            val handler = service.handlerOf(port.getId)
            val h = handler.get.handler.asInstanceOf[TestContainer]
            h.stream onError new Exception("some message")

            Then("The container status should not be set")
            val status = getContainerStatus(container.getId).get
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
            val service = new ContainerService(vt, host.getId, serviceExecutor,
                                               executors, ioExecutor, reflections)
            service.startAsync().awaitRunning()

            Then("The container status should be STARTING")
            getContainerStatus(container.getId).get.getStatusCode shouldBe Code.STARTING

            When("The container sets a health status")
            val handler = service.handlerOf(port.getId)
            val h = handler.get.handler.asInstanceOf[TestContainer]
            h.stream onNext ContainerHealth(Code.RUNNING, TestNamespace, "all good")

            Then("The container status should be RUNNING")
            val status1 = getContainerStatus(container.getId).get
            status1.getStatusCode shouldBe Code.RUNNING
            status1.getNamespaceName shouldBe TestNamespace
            status1.getStatusMessage shouldBe "all good"

            When("The container sets a health status")
            h.stream onNext ContainerHealth(Code.ERROR, TestNamespace, "epic fail")

            Then("The container status should be ERROR")
            val status2 = getContainerStatus(container.getId).get
            status2.getStatusCode shouldBe Code.ERROR
            status2.getNamespaceName shouldBe TestNamespace
            status2.getStatusMessage shouldBe "epic fail"

            When("The container sets a health status")
            h.stream onNext ContainerHealth(Code.RUNNING, TestNamespace, "all good again")

            Then("The container status should be RUNNING")
            val status3 = getContainerStatus(container.getId).get
            status3.getStatusCode shouldBe Code.RUNNING
            status3.getStatusMessage shouldBe "all good again"

            service.stopAsync().awaitTerminated()
        }

        scenario("Status set when starting and stopping a good container") {
            Given("A port bound to a host with a container")
            val host = createHost()
            val bridge = createBridge()
            val group = createServiceContainerGroup()
            store.multi(Seq(CreateOp(host), CreateOp(bridge), CreateOp(group)))

            And("A container service")
            val service = new ContainerService(vt, host.getId, serviceExecutor,
                                               executors, ioExecutor, reflections)
            service.startAsync().awaitRunning()

            When("Adding a service container")
            val container = createServiceContainer(configurationId = Some(randomUUID),
                                                   serviceType = Some("test-good"),
                                                   groupId = Some(group.getId))
            val port1 = createBridgePort(bridgeId = Some(bridge.getId),
                                        hostId = Some(host.getId),
                                        interfaceName = Some("veth"),
                                        containerId = Some(container.getId))
            store.multi(Seq(CreateOp(container), CreateOp(port1)))

            Then("The container status should be STARTING")
            getContainerStatus(container.getId).get.getStatusCode shouldBe Code.STARTING

            When("Unbinding the service container")
            val port2 = port1.clearHostId()
            store.update(port2)

            Then("The container status should be none")
            getContainerStatus(container.getId) shouldBe None
        }

        scenario("Status set when starting and stopping a bad create container") {
            Given("A port bound to a host with a container")
            val host = createHost()
            val bridge = createBridge()
            val group = createServiceContainerGroup()
            store.multi(Seq(CreateOp(host), CreateOp(bridge), CreateOp(group)))

            And("A container service")
            val service = new ContainerService(vt, host.getId, serviceExecutor,
                                               executors, ioExecutor, reflections)
            service.startAsync().awaitRunning()

            When("Adding a service container")
            val container = createServiceContainer(configurationId = Some(randomUUID),
                                                   serviceType = Some("test-error-create"),
                                                   groupId = Some(group.getId))
            val port = createBridgePort(bridgeId = Some(bridge.getId),
                                        hostId = Some(host.getId),
                                        interfaceName = Some("veth"),
                                        containerId = Some(container.getId))
            store.multi(Seq(CreateOp(container), CreateOp(port)))

            Then("The container status should be ERROR")
            getContainerStatus(container.getId).get.getStatusCode shouldBe Code.ERROR

            When("Unbinding the service container")
            val port2 = port.clearHostId()
            store.update(port2)

            Then("The container status should be ERROR")
            getContainerStatus(container.getId).get.getStatusCode shouldBe Code.ERROR
        }

        scenario("Status set when starting and stopping a bad delete container") {
            Given("A port bound to a host with a container")
            val host = createHost()
            val bridge = createBridge()
            val group = createServiceContainerGroup()
            store.multi(Seq(CreateOp(host), CreateOp(bridge), CreateOp(group)))

            And("A container service")
            val service = new ContainerService(vt, host.getId, serviceExecutor,
                                               executors, ioExecutor, reflections)
            service.startAsync().awaitRunning()

            When("Adding a service container")
            val container = createServiceContainer(configurationId = Some(randomUUID),
                                                   serviceType = Some("test-error-delete"),
                                                   groupId = Some(group.getId))
            val port = createBridgePort(bridgeId = Some(bridge.getId),
                                        hostId = Some(host.getId),
                                        interfaceName = Some("veth"),
                                        containerId = Some(container.getId))
            store.multi(Seq(CreateOp(container), CreateOp(port)))

            Then("The container status should be STARTING")
            getContainerStatus(container.getId).get.getStatusCode shouldBe Code.STARTING

            When("Unbinding the service container")
            val port2 = port.clearHostId()
            store.update(port2)

            Then("The container status should be STOPPING")
            getContainerStatus(container.getId).get.getStatusCode shouldBe Code.STOPPING
        }
    }

    feature("Test service logs the container operations") {
        scenario("Service logs starting and stopping the container") {
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
            val service = new ContainerService(vt, host.getId, serviceExecutor,
                                               executors, ioExecutor, reflections)
            service.startAsync().awaitRunning()

            When("The container reports the created configuration")
            val handler = service.handlerOf(port.getId)
            val h = handler.get.handler.asInstanceOf[TestContainer]
            h.stream onNext ContainerOp(ContainerFlag.Created, "a")

            Then("The log file exists")
            val path = FileSystems.getDefault.getPath(
                s"$logDir/${config.containers.logDirectory}/" +
                s"${port.getId.asJava}")
            Files.exists(path) shouldBe true

            When("The container reports the deleted configuration")
            h.stream onNext ContainerOp(ContainerFlag.Deleted, "a")

            Then("The log file does not exists")
            Files.exists(path) shouldBe false

            service.stopAsync().awaitTerminated()
        }

        scenario("Service clears the containers log upon startup") {
            Given("An existing log file")
            val dirPath = FileSystems.getDefault.getPath(
                s"$logDir/${config.containers.logDirectory}")
            val filePath = FileSystems.getDefault.getPath(
                s"$logDir/${config.containers.logDirectory}/" +
                s"${UUID.randomUUID()}")
            Files.createDirectory(dirPath)
            FileUtils.writeByteArrayToFile(
                filePath.toFile,
                Log.newBuilder().setId(UUID.randomUUID().toString)
                    .setType("test-good").setName("a")
                    .build().toByteArray)

            And("A container service")
            val host = createHost()
            val service = new ContainerService(vt, host.getId, serviceExecutor,
                                               executors, ioExecutor, reflections)
            store.create(host)

            When("The service starts")
            service.startAsync().awaitRunning()

            Then("The containers log file is deleted")
            Files.exists(filePath) shouldBe false

            service.stopAsync().awaitTerminated()
        }

        scenario("Service performs cleanup on existing containers") {
            Given("An existing log file")
            val dirPath = FileSystems.getDefault.getPath(
                s"$logDir/${config.containers.logDirectory}")
            val filePath1 = FileSystems.getDefault.getPath(
                s"$logDir/${config.containers.logDirectory}/" +
                s"${UUID.randomUUID()}.test-good")
            val filePath2 = FileSystems.getDefault.getPath(
                s"$logDir/${config.containers.logDirectory}/" +
                s"${UUID.randomUUID()}.test-good")
            Files.createDirectory(dirPath)
            FileUtils.writeByteArrayToFile(
                filePath1.toFile,
                Log.newBuilder().setId(UUID.randomUUID().toString)
                    .setType("test-good").setName("a")
                    .build().toByteArray)
            FileUtils.writeByteArrayToFile(
                filePath2.toFile,
                Log.newBuilder().setId(UUID.randomUUID().toString)
                    .setType("test-good").setName("a")
                    .build().toByteArray)

            And("A container service")
            val host = createHost()
            val service = new ContainerService(vt, host.getId, serviceExecutor,
                                               executors, ioExecutor, reflections)
            store.create(host)

            When("The service starts")
            GoodContainer.cleaned.set(0)
            service.startAsync().awaitRunning()

            Then("The container cleanup method should have been called")
            GoodContainer.cleaned.get() should be > 0

            service.stopAsync().awaitTerminated()
        }

        scenario("Service handles container errors on cleanup") {
            Given("An existing log file")
            val dirPath = FileSystems.getDefault
                .getPath(s"$logDir/${config.containers.logDirectory}")
            val filePath = FileSystems.getDefault.getPath(
                s"$logDir/${config.containers.logDirectory}/" +
                s"${UUID.randomUUID()}")
            Files.createDirectory(dirPath)
            FileUtils.writeByteArrayToFile(
                filePath.toFile,
                Log.newBuilder().setId(UUID.randomUUID().toString)
                    .setType("test-error-cleanup").setName("a")
                    .build().toByteArray)

            And("A container service")
            val host = createHost()
            val service = new ContainerService(vt, host.getId, serviceExecutor,
                                               executors, ioExecutor, reflections)
            store.create(host)

            When("The service starts")
            service.startAsync().awaitRunning()

            And("The service stops")
            service.stopAsync().awaitTerminated()
        }

        scenario("Service handles container exceptions on cleanup") {
            Given("An existing log file")
            val dirPath = FileSystems.getDefault
                .getPath(s"$logDir/${config.containers.logDirectory}")
            val filePath = FileSystems.getDefault.getPath(
                s"$logDir/${config.containers.logDirectory}/" +
                s"${UUID.randomUUID()}")
            Files.createDirectory(dirPath)
            FileUtils.writeByteArrayToFile(
                filePath.toFile,
                Log.newBuilder().setId(UUID.randomUUID().toString)
                    .setType("test-error-cleanup").setName("a")
                    .build().toByteArray)

            And("A container service")
            val host = createHost()
            val service = new ContainerService(vt, host.getId, serviceExecutor,
                                               executors, ioExecutor, reflections)
            store.create(host)

            When("The service starts")
            service.startAsync().awaitRunning()

            And("The service stops")
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
            val service = new ContainerService(vt, host.getId, serviceExecutor,
                                               executors, ioExecutor, reflections)
            service.startAsync().awaitRunning()

            Then("The service should not have the container")
            service.handlerOf(port.getId) shouldBe None

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
            val service = new ContainerService(vt, host.getId, serviceExecutor,
                                               executors, ioExecutor, reflections)
            service.startAsync().awaitRunning()

            Then("The service should start the container")
            val handler = service.handlerOf(port.getId)
            handler should not be None
            handler.get.cp shouldBe containerPort(host, port, container1, group)

            And("The handler create method should be called")
            val h = handler.get.handler.asInstanceOf[TestContainer]
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
            val service = new ContainerService(vt, host.getId, serviceExecutor,
                                               executors, ioExecutor, reflections)
            service.startAsync().awaitRunning()

            Then("The service should start the container")
            val handler = service.handlerOf(port.getId)
            handler should not be None
            handler.get.cp shouldBe containerPort(host, port, container1, group)

            And("The handler create method should be called")
            val h = handler.get.handler.asInstanceOf[TestContainer]
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

    feature("Service handles host weight") {
        scenario("Service handles weight notifications overflow") {
            Given("A do-nothing executor")
            val counter = new AtomicInteger()
            val executor = new SameThreadButAfterExecutorService {
                override def submit[T](task: Callable[T])
                : java.util.concurrent.Future[T] = {
                    // The first four notifications are emitted when the service
                    // starts: we allow those.
                    if (counter.incrementAndGet() < 4) {
                        super.submit(task)
                    } else {
                        SettableFuture.create()
                    }
                }
            }

            And("A host")
            val host = createHost()
            store.create(host)

            And("A container service")
            val service = new ContainerService(vt, host.getId, executor,
                                               executors, ioExecutor, reflections)
            service.startAsync().awaitRunning()

            Then("Sending many notifications should not affect the service")
            for (index <- 0 to ContainerService.NotificationBufferSize << 1) {
                store.update(host.toBuilder.setContainerWeight(index).build())
            }

            When("Resetting the counter")
            counter.set(Int.MinValue)

            And("Updating the host with a new weight")
            store.update(host.toBuilder.setContainerWeight(1).build())

            Then("The service should update the container status")
            val status = ContainerServiceStatus.newBuilder()
                                               .setWeight(1)
                                               .setQuota(-1)
                                               .setCount(0)
                                               .build()
                                               .toString
            checkContainerKey(host.getId, status)
        }

        scenario("Service handles host weight observable completion") {
            Given("A host")
            val host = createHost()
            store.create(host)

            And("A container service")
            val service = new ContainerService(vt, host.getId, serviceExecutor,
                                               executors, ioExecutor, reflections)

            When("The service starts")
            service.startAsync().awaitRunning()

            Then("The service should set the container state")
            val status = ContainerServiceStatus.newBuilder()
                                               .setWeight(1)
                                               .setQuota(-1)
                                               .setCount(0)
                                               .build()
                                               .toString
            checkContainerKey(host.getId, status)

            When("When the host weight observable completes")
            store.delete(classOf[Host], host.getId)
            backend.asInstanceOf[MidonetTestBackend].connectionState.onCompleted()

            Then("The service should clear the container state")
            checkContainerKey(hostId, status = null)
        }

        scenario("Service handles host weight observable error") {
            Given("A host")
            val host = createHost()
            store.create(host)

            And("A container service")
            val service = new ContainerService(vt, host.getId, serviceExecutor,
                                               executors, ioExecutor, reflections)

            When("The service starts")
            service.startAsync().awaitRunning()

            Then("The service should set the container state")
            val status = ContainerServiceStatus.newBuilder()
                                               .setWeight(1)
                                               .setQuota(-1)
                                               .setCount(0)
                                               .build()
                                               .toString
            checkContainerKey(host.getId, status)

            When("When the host weight observable emits an error")
            backend.asInstanceOf[MidonetTestBackend]
                   .connectionState onError new Exception()

            Then("The service should clear the container state")
            checkContainerKey(hostId, status = null)
        }

        scenario("Service handles error when setting the service status") {
            Given("A host")
            val host = createHost()
            store.create(host)

            And("A container service")
            val service = new ContainerService(vt, host.getId, serviceExecutor,
                                               executors, ioExecutor, reflections)

            When("The service starts")
            service.startAsync().awaitRunning()

            Then("The service should set the container state")
            val status = ContainerServiceStatus.newBuilder()
                                               .setWeight(1)
                                               .setQuota(-1)
                                               .setCount(0)
                                               .build()
                                               .toString
            checkContainerKey(host.getId, status)

            And("An error while setting the status does not fail")
            store.delete(classOf[Host], host.getId)
            backend.asInstanceOf[MidonetTestBackend]
                   .connectionState onNext RECONNECTED
        }

        scenario("Service detects the fast-curator connection state") {
            Given("A host")
            val host = createHost()
            store.create(host)

            And("A container service")
            val service = new ContainerService(vt, host.getId, serviceExecutor,
                                               executors, ioExecutor, reflections)
            service.startAsync().awaitRunning()

            Then("The container key should be present in the state store")
            val status = ContainerServiceStatus.newBuilder()
                                               .setWeight(1)
                                               .setQuota(-1)
                                               .setCount(0)
                                               .build()
                                               .toString
            checkContainerKey(host.getId, status)

            When("We remove the container key")
            stateStore.removeValue(classOf[Host], hostId, ContainerKey,
                                   value = null).await()
            checkContainerKey(hostId, status = null)

            And("We notify a reconnection of the fail fast curator")
            val testBackend = backend.asInstanceOf[MidonetTestBackend]
            testBackend.connectionState onNext RECONNECTED

            Then("The container key should be re-created")
            eventually {
                checkContainerKey(hostId, status)
            }

            service.stopAsync().awaitTerminated()
        }
    }
}
