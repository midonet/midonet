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

package org.midonet.cluster.services.containers.schedulers

import java.util.UUID

import com.typesafe.config.ConfigFactory

import org.junit.runner.RunWith
import org.scalatest.{GivenWhenThen, FeatureSpec, Matchers, BeforeAndAfter}
import org.scalatest.junit.JUnitRunner

import rx.observers.TestObserver
import rx.subjects.PublishSubject

import org.midonet.cluster.ContainersConfig
import org.midonet.cluster.models.State.ContainerStatus.Code
import org.midonet.cluster.services.containers.schedulers.ContainerScheduler.Down
import org.midonet.cluster.util.UUIDUtil._

@RunWith(classOf[JUnitRunner])
class ContainerSchedulerTest extends FeatureSpec with SchedulersTest
                             with BeforeAndAfter with Matchers
                             with GivenWhenThen {

    class TestScheduler(containerId: UUID,
                        context: Context,
                        config: ContainersConfig,
                        provider: HostSelectorProvider)
        extends ContainerScheduler(containerId, context, config, provider) {
        var time = 0L
        val timer = PublishSubject.create[java.lang.Long]
        protected override def timerObservable = timer
        protected override def currentTime = time
    }

    private val config = new ContainersConfig(ConfigFactory.parseString(
        """
          |cluster.containers.enabled : true
          |cluster.containers.scheduler_timeout : 10s
          |cluster.containers.scheduler_bad_host_lifetime : 300s
        """.stripMargin))
    private var provider: HostSelectorProvider = _

    protected override def beforeTest(): Unit = {
        provider = new HostSelectorProvider(context)
    }

    private def newScheduler(containerId: UUID): TestScheduler = {
        new TestScheduler(containerId, context, config, provider)
    }

    feature("Scheduler handles container notifications") {
        scenario("Container does not exist") {
            Given("A non existent container")
            val containerId = UUID.randomUUID()

            And("A container scheduler")
            val scheduler = newScheduler(containerId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should receive only a completed notification")
            obs.getOnNextEvents shouldBe empty
            obs.getOnErrorEvents shouldBe empty
            obs.getOnCompletedEvents should have size 1
        }
    }

    feature("Scheduler handles host updates") {
        scenario("No hosts available") {
            Given("A container with anywhere policy")
            val group = createGroup()
            val container = createContainer(group.getId)

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should not receive notifications")
            obs.getOnNextEvents shouldBe empty
            obs.getOnErrorEvents shouldBe empty
            obs.getOnCompletedEvents shouldBe empty

            And("The scheduler state should be down")
            scheduler.schedulerState shouldBe Down
        }

        scenario("No running hosts") {
            Given("A container with anywhere policy")
            val group = createGroup()
            val container = createContainer(group.getId)

            And("A host without the container service")
            createHost()

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should not receive notifications")
            obs.getOnNextEvents shouldBe empty
            obs.getOnErrorEvents shouldBe empty
            obs.getOnCompletedEvents shouldBe empty

            And("The scheduler state should be down")
            scheduler.schedulerState shouldBe Down
        }

        scenario("One host with container service and zero weight") {
            Given("A container with anywhere policy")
            val group = createGroup()
            val container = createContainer(group.getId)

            And("A host with the container service")
            val host = createHost()
            createHostStatus(host.getId, weight = 0)

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should not receive notifications")
            obs.getOnNextEvents shouldBe empty
            obs.getOnErrorEvents shouldBe empty
            obs.getOnCompletedEvents shouldBe empty

            And("The scheduler state should be down")
            scheduler.schedulerState shouldBe Down
        }

        scenario("One host with container service and positive weight") {
            Given("A container with anywhere policy")
            val group = createGroup()
            val container = createContainer(group.getId)

            And("A host with the container service")
            val host = createHost()
            createHostStatus(host.getId, weight = 1)

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should receive a scheduled notification")
            obs.getOnNextEvents should have size 1
            obs.getOnErrorEvents shouldBe empty
            obs.getOnCompletedEvents shouldBe empty
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, group, host.getId)

            And("The scheduler state should be scheduled")
            scheduler.schedulerState shouldBeScheduledFor(container, group, host.getId)
        }

        scenario("Two hosts, one with container service running") {
            Given("A container with anywhere policy")
            val group = createGroup()
            val container = createContainer(group.getId)

            And("A host with the container service")
            val host1 = createHost()
            createHost()
            createHostStatus(host1.getId, weight = 1)

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should receive a scheduled notification")
            obs.getOnNextEvents should have size 1
            obs.getOnErrorEvents shouldBe empty
            obs.getOnCompletedEvents shouldBe empty
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, group, host1.getId)

            And("The scheduler state should be scheduled")
            scheduler.schedulerState shouldBeScheduledFor(container, group, host1.getId)
        }

        scenario("Two hosts, one with positive weight") {
            Given("A container with anywhere policy")
            val group = createGroup()
            val container = createContainer(group.getId)

            And("A host with the container service")
            val host1 = createHost()
            val host2 = createHost()
            createHostStatus(host1.getId, weight = 1)
            createHostStatus(host2.getId, weight = 0)

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should receive a scheduled notification")
            obs.getOnNextEvents should have size 1
            obs.getOnErrorEvents shouldBe empty
            obs.getOnCompletedEvents shouldBe empty
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, group, host1.getId)

            And("The scheduler state should be scheduled")
            scheduler.schedulerState shouldBeScheduledFor(container, group, host1.getId)
        }

        scenario("Host starts running the container service with zero weight") {
            Given("A container with anywhere policy")
            val group = createGroup()
            val container = createContainer(group.getId)

            And("A host with the container service")
            val host = createHost()

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should not receive notifications")
            obs.getOnNextEvents shouldBe empty

            And("The scheduler state should be down")
            scheduler.schedulerState shouldBe Down

            When("Host starts running the container service")
            createHostStatus(host.getId, weight = 0)

            Then("The observer should not receive notifications")
            obs.getOnNextEvents shouldBe empty

            And("The scheduler state should be down")
            scheduler.schedulerState shouldBe Down
        }

        scenario("Host starts running the container service with positive weight") {
            Given("A container with anywhere policy")
            val group = createGroup()
            val container = createContainer(group.getId)

            And("A host with the container service")
            val host = createHost()

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should not receive notifications")
            obs.getOnNextEvents shouldBe empty

            And("The scheduler state should be down")
            scheduler.schedulerState shouldBe Down

            When("Host starts running the container service")
            createHostStatus(host.getId, weight = 1)

            Then("The observer should receive a scheduled notification")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, group, host.getId)

            And("The scheduler state should be scheduled")
            scheduler.schedulerState shouldBeScheduledFor(container, group, host.getId)
        }

        scenario("Adding a host with very large weight does not change scheduling") {
            Given("A container with anywhere policy")
            val group = createGroup()
            val container = createContainer(group.getId)

            And("A host with the container service")
            val host1 = createHost()
            createHostStatus(host1.getId, weight = 1)

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should receive a scheduled notification")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, group, host1.getId)

            And("The scheduler state should be scheduled")
            scheduler.schedulerState shouldBeScheduledFor(container, group, host1.getId)

            When("Adding a second host with a large weight")
            val host2 = createHost()
            createHostStatus(host2.getId, weight = Int.MaxValue)

            Then("The observer should not receive a new notification")
            obs.getOnNextEvents should have size 1
        }

        scenario("Single host weight changes from zero to positive") {
            Given("A container with anywhere policy")
            val group = createGroup()
            val container = createContainer(group.getId)

            And("A host with the container service")
            val host = createHost()
            createHostStatus(host.getId, weight = 0)

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should not receive notifications")
            obs.getOnNextEvents shouldBe empty

            And("The scheduler state should be down")
            scheduler.schedulerState shouldBe Down

            When("Changing the host weight to positive")
            createHostStatus(host.getId, weight = 1)

            Then("The observer should receive a scheduled notification")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, group, host.getId)

            And("The scheduler state should be scheduled")
            scheduler.schedulerState shouldBeScheduledFor(container, group, host.getId)
        }

        scenario("Single host weight changes from positive to zero") {
            Given("A container with anywhere policy")
            val group = createGroup()
            val container = createContainer(group.getId)

            And("A host with the container service")
            val host = createHost()
            createHostStatus(host.getId, weight = 1)

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should receive a scheduled notification")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, group, host.getId)

            And("The scheduler state should be scheduled")
            scheduler.schedulerState shouldBeScheduledFor(container, group, host.getId)

            When("Changing the host weight to zero")
            createHostStatus(host.getId, weight = 0)

            Then("The observer should receive an unschedule notification")
            obs.getOnNextEvents should have size 2
            obs.getOnNextEvents.get(1) shouldBeUnscheduleFor(container, group, host.getId)

            And("The scheduler state should be down")
            scheduler.schedulerState shouldBe Down
        }

        scenario("Host weight changes to zero, fallback host available") {
            Given("A container with anywhere policy")
            val group = createGroup()
            val container = createContainer(group.getId)

            And("A host with the container service")
            val host1 = createHost()
            createHostStatus(host1.getId, weight = 1)

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should receive a scheduled notification")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, group, host1.getId)

            And("The scheduler state should be scheduled")
            scheduler.schedulerState shouldBeScheduledFor(container, group, host1.getId)

            When("Adding a second host")
            val host2 = createHost()
            createHostStatus(host2.getId, weight = 1)

            When("Changing the first host weight to zero")
            createHostStatus(host1.getId, weight = 0)

            Then("The observer should receive reschedule notifications")
            obs.getOnNextEvents should have size 3
            obs.getOnNextEvents.get(1) shouldBeUnscheduleFor(container, group, host1.getId)
            obs.getOnNextEvents.get(2) shouldBeScheduleFor(container, group, host2.getId)

            And("The scheduler state should be down")
            scheduler.schedulerState shouldBeScheduledFor(container, group, host2.getId)
        }
    }

    feature("Scheduler handles container status") {
        scenario("Status reports container starting and running") {
            Given("A container with anywhere policy")
            val group = createGroup()
            val container = createContainer(group.getId)

            And("A host with the container service")
            val host = createHost()
            createHostStatus(host.getId, weight = 1)

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should receive a scheduled notification")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, group, host.getId)

            When("The container status is starting")
            createContainerStatus(container.getId, Code.STARTING, host.getId)

            Then("The observer should not receive new notifications")
            obs.getOnNextEvents should have size 1

            And("The scheduler state should be scheduled")
            scheduler.schedulerState shouldBeScheduledFor(container, group, host.getId)

            When("The container status is running")
            createContainerStatus(container.getId, Code.RUNNING, host.getId)

            Then("The observer should receive a scheduled notification")
            obs.getOnNextEvents should have size 2
            obs.getOnNextEvents.get(1) shouldBeUpFor(container, group, host.getId)

            And("The scheduler state should be up")
            scheduler.schedulerState shouldBeUpFor(container, group, host.getId)
        }

        scenario("Status reports container running") {
            Given("A container with anywhere policy")
            val group = createGroup()
            val container = createContainer(group.getId)

            And("A host with the container service")
            val host = createHost()
            createHostStatus(host.getId, weight = 1)

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should receive a scheduled notification")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, group, host.getId)

            When("The container status is running")
            createContainerStatus(container.getId, Code.RUNNING, host.getId)

            Then("The observer should receive a scheduled notification")
            obs.getOnNextEvents should have size 2
            obs.getOnNextEvents.get(1) shouldBeUpFor(container, group, host.getId)

            And("The scheduler state should be up")
            scheduler.schedulerState shouldBeUpFor(container, group, host.getId)
        }

        scenario("Status reports container stopping") {
            Given("A container with anywhere policy")
            val group = createGroup()
            val container = createContainer(group.getId)

            And("A host with the container service")
            val host = createHost()
            createHostStatus(host.getId, weight = 1)

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should receive a scheduled notification")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, group, host.getId)

            When("The container status is stopping")
            createContainerStatus(container.getId, Code.STOPPING, host.getId)

            Then("The observer should receive a down notification")
            obs.getOnNextEvents should have size 2
            obs.getOnNextEvents.get(1) shouldBeDownFor(container, group, host.getId)

            And("The scheduler state should be down")
            scheduler.schedulerState shouldBe Down
        }

        scenario("Status reports container error") {
            Given("A container with anywhere policy")
            val group = createGroup()
            val container = createContainer(group.getId)

            And("A host with the container service")
            val host = createHost()
            createHostStatus(host.getId, weight = 1)

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should receive a scheduled notification")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, group, host.getId)

            When("The container status is error")
            createContainerStatus(container.getId, Code.ERROR, host.getId)

            Then("The observer should receive a down notification")
            obs.getOnNextEvents should have size 2
            obs.getOnNextEvents.get(1) shouldBeDownFor(container, group, host.getId)

            And("The scheduler state should be down")
            scheduler.schedulerState shouldBe Down
        }

        scenario("Cleared status on down container is ignored") {
            Given("A container with anywhere policy")
            val group = createGroup()
            val container = createContainer(group.getId)

            And("A host with the container service")
            val host = createHost()
            createHostStatus(host.getId, weight = 1)

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            And("The container status is error")
            createContainerStatus(container.getId, Code.ERROR, host.getId)

            Then("The observer should receive a down notification")
            obs.getOnNextEvents should have size 2
            scheduler.schedulerState shouldBe Down

            When("The container status is cleared")
            deleteContainerStatus(container.getId, host.getId)

            Then("The observer should not receive a new notification")
            obs.getOnNextEvents should have size 2
        }

        scenario("Clear status on up container triggers reschedule") {
            Given("A container with anywhere policy")
            val group = createGroup()
            val container = createContainer(group.getId)

            And("A host with the container service")
            val host = createHost()
            createHostStatus(host.getId, weight = 1)

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            And("The container status is error")
            createContainerStatus(container.getId, Code.RUNNING, host.getId)

            Then("The observer should receive a down notification")
            obs.getOnNextEvents should have size 2
            obs.getOnNextEvents.get(1) shouldBeUpFor(container, group, host.getId)

            When("The container status is cleared")
            deleteContainerStatus(container.getId, host.getId)

            Then("The observer should not receive down and schedule notification")
            obs.getOnNextEvents should have size 4
            obs.getOnNextEvents.get(2) shouldBeDownFor(container, group, host.getId)
            obs.getOnNextEvents.get(3) shouldBeScheduleFor(container, group, host.getId)

            And("The scheduler state should be scheduled")
            scheduler.schedulerState shouldBeScheduledFor(container, group, host.getId)
        }
    }

    feature("Scheduler handles timeouts") {

    }

    feature("Scheduler handles container group updates") {

    }

    feature("Scheduler handles container updates") {

    }

    feature("Scheduler emits cleanup notifications") {

    }

}
