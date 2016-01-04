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

package org.midonet.cluster.services.containers

import java.util.UUID
import java.util.concurrent.TimeUnit

import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.Logger

import org.junit.runner.RunWith
import org.reflections.Reflections
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FeatureSpec, GivenWhenThen, Matchers}
import org.slf4j.LoggerFactory

import rx.schedulers.Schedulers
import rx.subjects.{PublishSubject, Subject}

import org.midonet.cluster.ClusterNode.Context
import org.midonet.cluster._
import org.midonet.cluster.data.storage.{CreateOp, InMemoryStorage}
import org.midonet.cluster.models.State.{ContainerServiceStatus, ContainerStatus}
import org.midonet.cluster.models.Topology.{Host, ServiceContainer}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.MidonetBackend._
import org.midonet.cluster.services.containers.ContainerServiceTest.ContainerServiceTestDelegate
import org.midonet.cluster.services.containers.schedulers._
import org.midonet.cluster.storage.MidonetTestBackend
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.containers.Container
import org.midonet.util.MidonetEventually
import org.midonet.util.concurrent.SameThreadButAfterExecutorService
import org.midonet.util.reactivex._

object ContainerServiceTest {

    @Container(name = "container-service-test", version = 1)
    class ContainerServiceTestDelegate extends ContainerDelegate {
        @volatile var delegateThrows = false
        @volatile var scheduled = Seq.empty[(ServiceContainer, UUID)]
        @volatile var up = Seq.empty[(ServiceContainer, ContainerStatus)]
        @volatile var down = Seq.empty[(ServiceContainer, ContainerStatus)]
        @volatile var unscheduled = Seq.empty[(ServiceContainer, UUID)]
        override def onScheduled(container: ServiceContainer, hostId: UUID): Unit = {
            scheduled = scheduled :+ (container, hostId)
            if (delegateThrows) throw new Throwable()
        }
        override def onUp(container: ServiceContainer, status: ContainerStatus): Unit = {
            up = up :+ (container, status)
            if (delegateThrows) throw new Throwable()
        }
        override def onDown(container: ServiceContainer, status: ContainerStatus): Unit = {
            down = down :+ (container, status)
            if (delegateThrows) throw new Throwable()
        }
        override def onUnscheduled(container: ServiceContainer, hostId: UUID): Unit = {
            unscheduled = unscheduled :+ (container, hostId)
            if (delegateThrows) throw new Throwable()
        }
    }

}

@RunWith(classOf[JUnitRunner])
class ContainerServiceTest extends FeatureSpec with GivenWhenThen with Matchers
                           with BeforeAndAfter with MidonetEventually
                           with TopologyBuilder {

    private val reflections =
        new Reflections("org.midonet.cluster.services.containers")

    private class TestService(backend: MidonetBackend, config: ClusterConfig)
        extends ContainerService(new Context(UUID.randomUUID()), backend,
                                 reflections, config) {
        def create(container: ServiceContainer) = delegateOf(container)
        def get(container: ServiceContainer) = delegate(container)
    }

    private class MockService(backend: MidonetBackend, config: ClusterConfig)
        extends TestService(backend, config) {

        var events: Subject[SchedulerEvent, SchedulerEvent] = _

        protected override def newScheduler(): ServiceScheduler = {
            val executor = new SameThreadButAfterExecutorService
            val scheduler = Schedulers.from(executor)
            val context = schedulers.Context(
                backend.store, backend.stateStore, executor, scheduler,
                Logger(LoggerFactory.getLogger("container-service-test")))
            events = PublishSubject.create[SchedulerEvent]
            new ServiceScheduler(context, config.containers) {
                override val observable = events
            }
        }
    }

    private val config = new ClusterConfig(ConfigFactory.parseString(
        """
          |cluster.containers.enabled : true
          |cluster.containers.scheduler_timeout : 10s
          |cluster.containers.scheduler_bad_host_lifetime : 300s
        """.stripMargin))
    private var backend: MidonetBackend = _

    before {
        backend = new MidonetTestBackend()
        backend.startAsync().awaitRunning()
    }

    after {
        backend.stopAsync().awaitTerminated()
    }

    private def newService(): MockService = {
        new MockService(backend, config)
    }

    feature("Test service lifecycle") {
        scenario("Service starts and stops") {
            Given("A container service")
            val service = newService()

            When("The service is started")
            service.startAsync().awaitRunning(10, TimeUnit.SECONDS)

            Then("The service should be subscribed to scheduling notifications")
            service.isSubscribed shouldBe true

            When("The service is stopped")
            service.stopAsync().awaitTerminated(10, TimeUnit.SECONDS)

            Then("The service should be unsubscribed from scheduling notifications")
            service.isSubscribed shouldBe false
        }

        scenario("Service is enabled in the default configuration schema") {
            Given("A container service")
            val service = newService()

            Then("The service is enabled")
            service.isEnabled shouldBe true
        }
    }

    feature("Test service processes container notifications") {
        scenario("Service loads the container delegate") {
            Given("A container service")
            val service = newService()
            service.startAsync().awaitRunning()

            And("A container and host")
            val container = createServiceContainer(
                serviceType = Some("container-service-test"))
            When("The scheduler emits a container notification")
            service.events onNext Schedule(container, UUID.randomUUID())

            Then("The service should have a delegate for the container type")
            eventually {
                service.get(container) should not be empty
            }
        }

        scenario("Service handles schedule events") {
            Given("A container service")
            val service = newService()
            service.startAsync().awaitRunning()

            And("A container and host")
            val container = createServiceContainer(
                serviceType = Some("container-service-test"))
            val hostId = UUID.randomUUID()
            val status = ContainerStatus.getDefaultInstance

            When("The scheduler emits a scheduled notification")
            service.events onNext Schedule(container, hostId)

            Then("The delegate should receive the scheduled event")
            eventually {
                val delegate = service.get(container).get
                                      .asInstanceOf[ContainerServiceTestDelegate]
                delegate.scheduled should contain only ((container, hostId))
                delegate.scheduled should have size 1
                delegate.scheduled.head shouldBe (container, hostId)
            }

            When("The scheduler emits an up notification")
            service.events onNext Up(container, status)

            Then("The delegate should receive the up event")
            eventually {
                val delegate = service.get(container).get
                    .asInstanceOf[ContainerServiceTestDelegate]
                delegate.up should have size 1
                delegate.up.head shouldBe (container, status)
            }

            When("The scheduler emits a down notification")
            service.events onNext Down(container, status)

            Then("The delegate should receive the down event")
            eventually {
                val delegate = service.get(container).get
                                      .asInstanceOf[ContainerServiceTestDelegate]
                delegate.down should have size 1
                delegate.down.head shouldBe (container, status)
            }

            When("The scheduler emits an unscheduled notification")
            service.events onNext Unschedule(container, hostId)

            Then("The delegate should receive the unscheduled event")
            eventually {
                val delegate = service.get(container).get
                    .asInstanceOf[ContainerServiceTestDelegate]
                delegate.unscheduled should have size 1
                delegate.unscheduled.head shouldBe (container, hostId)
            }
        }

        scenario("Service handles delegate exceptions") {
            Given("A container service")
            val service = newService()
            service.startAsync().awaitRunning()

            And("A container and host")
            val container = createServiceContainer(
                serviceType = Some("container-service-test"))
            val hostId = UUID.randomUUID()
            val status = ContainerStatus.getDefaultInstance

            And("Create the delegate")
            val delegate = service.create(container)
                                  .asInstanceOf[ContainerServiceTestDelegate]
            delegate should not be null
            delegate.delegateThrows = true

            When("The scheduler emits a scheduled notification")
            service.events onNext Schedule(container, hostId)

            Then("The delegate should receive the scheduled event")
            eventually {
                delegate.scheduled should have size 1
                delegate.scheduled.head shouldBe (container, hostId)
            }

            And("The service should be subscribed")
            service.isSubscribed shouldBe true

            When("The scheduler emits an up notification")
            service.events onNext Up(container, status)

            Then("The delegate should receive the up event")
            eventually {
                delegate.up should have size 1
                delegate.up.head shouldBe (container, status)
            }

            When("The scheduler emits a down notification")
            service.events onNext Down(container, status)

            And("The service should be subscribed")
            service.isSubscribed shouldBe true

            Then("The delegate should receive the down event")
            eventually {
                delegate.down should have size 1
                delegate.down.head shouldBe (container, status)
            }

            And("The service should be subscribed")
            service.isSubscribed shouldBe true

            When("The scheduler emits an unscheduled notification")
            service.events onNext Unschedule(container, hostId)

            Then("The delegate should receive the unscheduled event")
            eventually {
                delegate.unscheduled should have size 1
                delegate.unscheduled.head shouldBe (container, hostId)
            }

            And("The service should be subscribed")
            service.isSubscribed shouldBe true
        }

        scenario("Service handles scheduler error") {
            Given("A container service")
            val service = newService()
            service.startAsync().awaitRunning()

            When("The scheduler emits an error")
            service.events onError new Throwable()

            Then("The service should be unsubscribed")
            eventually {
                service.isSubscribed shouldBe false
            }
        }

        scenario("Service handles scheduler completion") {
            Given("A container service")
            val service = newService()
            service.startAsync().awaitRunning()

            When("The scheduler completes")
            service.events.onCompleted()

            Then("The service should be unsubscribed")
            eventually {
                service.isSubscribed shouldBe false
            }
        }
    }

    feature("Service uses the container scheduler") {
        scenario("Service schedules container") {
            Given("A container service")
            val service = new TestService(backend, config)

            And("A container and a host")
            val group = createServiceContainerGroup()
            val container = createServiceContainer(
                serviceType = Some("container-service-test"),
                groupId = Some(group.getId))
            val host = createHost()
            backend.store.multi(Seq(CreateOp(group), CreateOp(container), CreateOp(host)))

            And("The host is running the container service")
            val status = ContainerServiceStatus.newBuilder().setWeight(1).build()
            val backdoor = backend.stateStore.asInstanceOf[InMemoryStorage]
            backdoor.addValueAs(host.getId.asJava.toString, classOf[Host],
                                host.getId, ContainerKey, status.toString).await()

            When("Starting the container service")
            service.startAsync().awaitRunning()

            Then("The service should be subscribed")
            service.isSubscribed shouldBe true

            And("The delegate should receive the scheduled event")
            eventually {
                val delegate = service.get(container).get
                    .asInstanceOf[ContainerServiceTestDelegate]
                delegate.scheduled should have size 1
                delegate.scheduled.head shouldBe (container, host.getId.asJava)
            }

            service.stopAsync().awaitTerminated()
        }
    }
}
