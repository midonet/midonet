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

import com.google.common.util.concurrent.Service
import com.typesafe.config.ConfigFactory

import org.apache.curator.framework.recipes.leader.LeaderLatch.CloseMode
import org.junit.runner.RunWith
import org.reflections.Reflections
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FeatureSpec, GivenWhenThen, Matchers}
import org.slf4j.LoggerFactory

import rx.schedulers.Schedulers
import rx.subjects.{PublishSubject, Subject}

import org.midonet.cluster._
import org.midonet.cluster.data.storage.{CreateOp, InMemoryStorage}
import org.midonet.cluster.models.State.{ContainerServiceStatus, ContainerStatus}
import org.midonet.cluster.models.Topology.{Host, ServiceContainer}
import org.midonet.cluster.services.MidonetBackend._
import org.midonet.cluster.services.containers.ContainerService.MaximumFailures
import org.midonet.cluster.services.containers.ContainerServiceTest.ContainerServiceTestDelegate
import org.midonet.cluster.services.containers.schedulers._
import org.midonet.cluster.services._
import org.midonet.cluster.storage.MidonetTestBackend
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.containers
import org.midonet.containers.{Container, ContainerDelegate}
import org.midonet.minion.Context
import org.midonet.util.MidonetEventually
import org.midonet.util.concurrent.SameThreadButAfterExecutorService
import org.midonet.util.logging.Logger
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

    private class TestService(backend: MidonetBackend,
                              reflections: Reflections,
                              latchProvider: LeaderLatchProvider,
                              config: ClusterConfig)
        extends ContainerService(new Context(UUID.randomUUID()), backend,
                                 reflections, latchProvider, config) {
        def create(container: ServiceContainer) = delegateOf(container)
        def get(container: ServiceContainer) = delegate(container)
    }

    private class MockService(backend: MidonetBackend,
                              latchProvider: LeaderLatchProvider,
                              config: ClusterConfig)
        extends TestService(backend, reflections, latchProvider, config) {

        var events: Subject[SchedulerEvent, SchedulerEvent] = _

        protected override def newScheduler(): ServiceScheduler = {
            val executor = new SameThreadButAfterExecutorService
            val scheduler = Schedulers.from(executor)
            val context = containers.Context(
                backend.store, backend.stateStore, executor, scheduler,
                Logger(LoggerFactory.getLogger("container-service-test")))
            events = PublishSubject.create[SchedulerEvent]
            new ServiceScheduler(context, config.containers) {
                override val observable = events
            }
        }
    }

    private val config = ClusterConfig.forTests(ConfigFactory.parseString(
        """
          |cluster.containers.enabled : true
          |cluster.containers.scheduler_timeout : 10s
          |cluster.containers.scheduler_bad_host_lifetime : 300s
        """.stripMargin))
    private var backend: MidonetBackend = _

    private var latchProvider: LeaderLatchProvider = _

    private def leaderLatch(): MockLeaderLatch = {
        latchProvider.get(ContainerService.latchPath(config))
                     .asInstanceOf[MockLeaderLatch]
    }

    before {
        backend = new MidonetTestBackend()
        backend.startAsync().awaitRunning()
        latchProvider = new MockLeaderLatchProvider(backend, config)
    }

    after {
        backend.stopAsync().awaitTerminated()
    }

    private def newService(andStartIt: Boolean): MockService = {
        val s = new MockService(backend, latchProvider, config)
        if (andStartIt) {
            s.startAsync().awaitRunning(10, TimeUnit.SECONDS)
            leaderLatch().isLeader()
        }
        s
    }

    feature("Test service lifecycle") {
        scenario("Service starts, goes through leadership cycles, and stops") {
            Given("A container service that is started, and assigned as leader")
            val service = newService(andStartIt = false)

            leaderLatch().listeners should have size 1
            leaderLatch().isStarted shouldBe false

            service.startAsync().awaitRunning(10, TimeUnit.SECONDS)
            leaderLatch().isStarted shouldBe true
            leaderLatch().isLeader()

            Then("The service should be subscribed to scheduling notifications")
            service.isSubscribed shouldBe true

            When("The leader role is taken over by another node")
            leaderLatch().notLeader()

            Then("The scheduling should stop")
            service.isSubscribed shouldBe false
            leaderLatch().listeners should have size 1

            When("Leadership is recovered")
            leaderLatch().isLeader()

            Then("Scheduling should resume")
            leaderLatch().listeners should have size 1
            eventually {
                service.isSubscribed shouldBe true
            }

            When("The service is stopped")
            service.stopAsync().awaitTerminated(10, TimeUnit.SECONDS)

            Then("The service should be unsubscribed from scheduling notifications")
            service.isSubscribed shouldBe false
            leaderLatch().listeners shouldBe empty
            leaderLatch().isStarted shouldBe false
            leaderLatch().closeMode shouldBe CloseMode.SILENT
        }

        scenario("Service is enabled in the default configuration schema") {
            Given("A container service that is started")
            val service = newService(andStartIt = true)

            Then("The service is enabled")
            service.isEnabled shouldBe true
        }
    }

    feature("Test service processes container notifications") {
        scenario("Service loads the container delegate") {
            Given("A container service")
            val service = newService(andStartIt = true)

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
            val service = newService(andStartIt = true)

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
            val service = newService(andStartIt = true)

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
            val service = newService(andStartIt = true)

            When("The scheduler emits errors below limit they are tolerated")
            for (index <- 1 until MaximumFailures) {
                service.events onError new Throwable(s"Recoverable $index")
                eventually { service.schedulerObserverErrorCount shouldBe index}
                service.isSubscribed shouldBe true
            }

            When("The scheduler emits one error too many")
            service.events onError new Throwable(s"Non recoverable")

            Then("The service should be unsubscribed")
            eventually { service.isSubscribed shouldBe false }

            service.state() shouldBe Service.State.FAILED
            service.stopAsync()
        }

        scenario("Service handles scheduler completion") {
            Given("A container service")
            val service = newService(andStartIt = true)

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
            val service = new TestService(backend, reflections, latchProvider,
                                          config)

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
            leaderLatch().isLeader()

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
