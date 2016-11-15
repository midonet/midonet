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

import java.util
import java.util.UUID

import com.typesafe.config.ConfigFactory

import org.junit.runner.RunWith
import org.scalatest.{GivenWhenThen, FeatureSpec, Matchers, BeforeAndAfter}
import org.scalatest.junit.JUnitRunner

import rx.observers.TestObserver
import rx.subjects.PublishSubject

import org.midonet.cluster.ContainersConfig
import org.midonet.cluster.models.State.ContainerStatus.Code
import org.midonet.cluster.models.Topology.{ServiceContainerPolicy, Port, ServiceContainer}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.containers.schedulers.ContainerScheduler.DownState
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.containers.Context
import org.midonet.util.concurrent._
import org.midonet.util.reactivex._

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
        var timer = PublishSubject.create[java.lang.Long]
        protected override def timeoutObservable = timer
        protected override def retryObservable = timer
        protected override def currentTime = time
    }

    private val config = new ContainersConfig(ConfigFactory.parseString(
        """
          |cluster.containers.enabled : true
          |cluster.containers.scheduler_timeout : 10s
          |cluster.containers.scheduler_retry : 15s
          |cluster.containers.scheduler_max_retries : 3
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

    feature("Scheduler handles subscriptions") {
        scenario("Scheduler supports only one subscriber") {
            Given("A container scheduler")
            val scheduler = newScheduler(UUID.randomUUID())

            And("A first observer subscribed to the scheduler")
            val obs1 = new TestObserver[SchedulerEvent]
            scheduler.observable subscribe obs1

            And("A second observer")
            val obs2 = new TestObserver[SchedulerEvent]

            When("A second observer subscribes to the scheduler")
            scheduler.observable subscribe obs2

            Then("The observer should receive an error")
            obs2.getOnErrorEvents should have size 1
            obs2.getOnErrorEvents.get(0).getClass shouldBe classOf[IllegalStateException]
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
            scheduler.schedulerState shouldBeDownFor(attempts = 0)
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
            scheduler.schedulerState shouldBeDownFor(attempts = 0)
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
            scheduler.schedulerState shouldBeDownFor(attempts = 0)
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
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, host.getId)

            And("The scheduler state should be scheduled")
            scheduler.schedulerState shouldBeScheduledFor(container, host.getId)
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
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, host1.getId)

            And("The scheduler state should be scheduled")
            scheduler.schedulerState shouldBeScheduledFor(container, host1.getId)
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
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, host1.getId)

            And("The scheduler state should be scheduled")
            scheduler.schedulerState shouldBeScheduledFor(container, host1.getId)
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
            scheduler.schedulerState shouldBeDownFor(attempts = 0)

            When("Host starts running the container service")
            createHostStatus(host.getId, weight = 0)

            Then("The observer should not receive notifications")
            obs.getOnNextEvents shouldBe empty

            And("The scheduler state should be down")
            scheduler.schedulerState shouldBeDownFor(attempts = 0)
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
            scheduler.schedulerState shouldBeDownFor(attempts = 0)

            When("Host starts running the container service")
            createHostStatus(host.getId, weight = 1)

            Then("The observer should receive a scheduled notification")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, host.getId)

            And("The scheduler state should be scheduled")
            scheduler.schedulerState shouldBeScheduledFor(container, host.getId)
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
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, host1.getId)

            And("The scheduler state should be scheduled")
            scheduler.schedulerState shouldBeScheduledFor(container, host1.getId)

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
            scheduler.schedulerState shouldBeDownFor(attempts = 0)

            When("Changing the host weight to positive")
            createHostStatus(host.getId, weight = 1)

            Then("The observer should receive a scheduled notification")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, host.getId)

            And("The scheduler state should be scheduled")
            scheduler.schedulerState shouldBeScheduledFor(container, host.getId)
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
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, host.getId)

            And("The scheduler state should be scheduled")
            scheduler.schedulerState shouldBeScheduledFor(container, host.getId)

            When("Changing the host weight to zero")
            createHostStatus(host.getId, weight = 0)

            Then("The observer should receive an unschedule notification")
            obs.getOnNextEvents should have size 2
            obs.getOnNextEvents.get(1) shouldBeUnscheduleFor(container, host.getId)

            And("The scheduler state should be down")
            scheduler.schedulerState shouldBe DownState
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
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, host1.getId)

            And("The scheduler state should be scheduled")
            scheduler.schedulerState shouldBeScheduledFor(container, host1.getId)

            When("Adding a second host")
            val host2 = createHost()
            createHostStatus(host2.getId, weight = 1)

            When("Changing the first host weight to zero")
            createHostStatus(host1.getId, weight = 0)

            Then("The observer should receive reschedule notifications")
            obs.getOnNextEvents should have size 3
            obs.getOnNextEvents.get(1) shouldBeUnscheduleFor(container, host1.getId)
            obs.getOnNextEvents.get(2) shouldBeScheduleFor(container, host2.getId)

            And("The scheduler state should be rescheduled")
            scheduler.schedulerState shouldBeRescheduledFor(
                container, host1.getId, host2.getId)
        }

        scenario("Scheduler filters eligible hosts by running status") {
            Given("A container with anywhere policy")
            val group = createGroup()
            val container = createContainer(group.getId)

            And("Several hosts, only one with container service")
            createHost()
            val host = createHost()
            createHost()
            createHostStatus(host.getId, weight = 1)

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should receive a scheduled notification")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, host.getId)

            And("The scheduler state should be scheduled")
            scheduler.schedulerState shouldBeScheduledFor(container, host.getId)
        }

        scenario("Scheduler filters eligible hosts by weight") {
            Given("A container with anywhere policy")
            val group = createGroup()
            val container = createContainer(group.getId)

            And("Several hosts, all with container service")
            val host1 = createHost()
            val host2 = createHost()
            val host3 = createHost()
            createHostStatus(host1.getId, weight = 0)
            createHostStatus(host2.getId, weight = 0)
            createHostStatus(host3.getId, weight = 1)

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should receive a scheduled notification")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, host3.getId)

            And("The scheduler state should be scheduled")
            scheduler.schedulerState shouldBeScheduledFor(container, host3.getId)
        }

        scenario("Selected host is no longer eligible and no hosts available") {
            Given("A container with anywhere policy")
            val group = createGroup()
            val container = createContainer(group.getId)

            And("A host with container service")
            val host = createHost()
            createHostStatus(host.getId, weight = 1)

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            And("The container is reported running")
            createContainerStatus(container.getId, Code.RUNNING, host.getId)

            Then("The observer should receive a scheduled notification")
            obs.getOnNextEvents should have size 2
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, host.getId)
            obs.getOnNextEvents.get(1) shouldBeUpFor(container, host.getId)

            When("The selected host is no longer eligible")
            deleteHostStatus(host.getId)

            Then("The observer should receive a down notification")
            obs.getOnNextEvents should have size 4
            obs.getOnNextEvents.get(2) shouldBeDownFor(container, host.getId)
            obs.getOnNextEvents.get(3) shouldBeUnscheduleFor(container, host.getId)

            And("The scheduler state should be down")
            scheduler.schedulerState shouldBe DownState
        }

        scenario("Selected host is no longer available and other host is available") {
            Given("A container with anywhere policy")
            val group = createGroup()
            val container = createContainer(group.getId)

            And("A host with container service")
            val host1 = createHost()
            createHostStatus(host1.getId, weight = 1)

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            And("The container is reported running")
            createContainerStatus(container.getId, Code.RUNNING, host1.getId)

            Then("The observer should receive a scheduled notification")
            obs.getOnNextEvents should have size 2
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, host1.getId)
            obs.getOnNextEvents.get(1) shouldBeUpFor(container, host1.getId)

            When("Starting another host")
            val host2 = createHost()
            createHostStatus(host2.getId, weight = 1)

            And("The selected host is no longer eligible")
            deleteHostStatus(host1.getId)

            Then("The observer should receive a down and scheduled notification")
            obs.getOnNextEvents should have size 5
            obs.getOnNextEvents.get(2) shouldBeDownFor(container, host1.getId)
            obs.getOnNextEvents.get(3) shouldBeUnscheduleFor(container, host1.getId)
            obs.getOnNextEvents.get(4) shouldBeScheduleFor(container, host2.getId)

            And("The scheduler state should be rescheduled")
            scheduler.schedulerState shouldBeRescheduledFor(
                container, host1.getId, host2.getId)
        }

        scenario("Selected host becomes unavailable and then available") {
            Given("A container with anywhere policy")
            val group = createGroup()
            val container = createContainer(group.getId)

            And("A host with container service")
            val host = createHost()
            createHostStatus(host.getId, weight = 1)

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            And("The container is reported running")
            createContainerStatus(container.getId, Code.RUNNING, host.getId)

            Then("The observer should receive a scheduled notification")
            obs.getOnNextEvents should have size 2
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, host.getId)
            obs.getOnNextEvents.get(1) shouldBeUpFor(container, host.getId)

            When("The selected host is no longer eligible")
            deleteHostStatus(host.getId)

            Then("The observer should receive a down notification")
            obs.getOnNextEvents should have size 4
            obs.getOnNextEvents.get(2) shouldBeDownFor(container, host.getId)
            obs.getOnNextEvents.get(3) shouldBeUnscheduleFor(container, host.getId)

            When("The selected host is again eligible")
            createHostStatus(host.getId, weight = 1)

            Then("The observer should receive a scheduled notification")
            obs.getOnNextEvents should have size 6
            obs.getOnNextEvents.get(4) shouldBeScheduleFor(container, host.getId)
            obs.getOnNextEvents.get(5) shouldBeUpFor(container, host.getId)

            And("The scheduler state should be scheduled")
            scheduler.schedulerState shouldBeUpFor(container, host.getId)
        }

        scenario("Selected host becomes unavailable and then another becomes available") {
            Given("A container with anywhere policy")
            val group = createGroup()
            val container = createContainer(group.getId)

            And("A host with container service")
            val host1 = createHost()
            createHostStatus(host1.getId, weight = 1)

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            And("The container is reported running")
            createContainerStatus(container.getId, Code.RUNNING, host1.getId)

            Then("The observer should receive a scheduled notification")
            obs.getOnNextEvents should have size 2
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, host1.getId)
            obs.getOnNextEvents.get(1) shouldBeUpFor(container, host1.getId)

            When("The selected host is no longer eligible")
            deleteHostStatus(host1.getId)

            Then("The observer should receive a down notification")
            obs.getOnNextEvents should have size 4
            obs.getOnNextEvents.get(2) shouldBeDownFor(container, host1.getId)
            obs.getOnNextEvents.get(3) shouldBeUnscheduleFor(container, host1.getId)

            When("Another host becomes eligible")
            val host2 = createHost()
            createHostStatus(host2.getId, weight = 1)

            Then("The observer should receive a scheduled notification")
            obs.getOnNextEvents should have size 5
            obs.getOnNextEvents.get(4) shouldBeScheduleFor(container, host2.getId)

            And("The scheduler state should be scheduled")
            scheduler.schedulerState shouldBeScheduledFor(container, host2.getId)
        }

        scenario("Scheduled container is rescheduled on a different host") {
            Given("A container with anywhere policy")
            val group = createGroup()
            val container = createContainer(group.getId)

            And("Two hosts, only one with container service")
            val host1 = createHost()
            val host2 = createHost()
            createHostStatus(host1.getId, weight = 1)

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should receive a scheduled notification")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, host1.getId)

            When("The first host becomes unavailable and the second available")
            createHostStatus(host2.getId, weight = 1)
            deleteHostStatus(host1.getId)

            Then("The observer should receive an unscheduled and scheduled notification")
            obs.getOnNextEvents should have size 3
            obs.getOnNextEvents.get(1) shouldBeUnscheduleFor(container, host1.getId)
            obs.getOnNextEvents.get(2) shouldBeScheduleFor(container, host2.getId)

            And("The scheduler state should be rescheduled")
            scheduler.schedulerState shouldBeRescheduledFor(
                container, host1.getId, host2.getId)
        }

        scenario("Selected host is no longer eligible") {
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

            createContainerStatus(container.getId, Code.RUNNING, host.getId)

            Then("The observer should receive scheduled and up notifications")
            obs.getOnNextEvents should have size 2

            When("The selected host is no longer eligible")
            deleteHostStatus(host.getId)

            Then("The scheduler state should be down")
            scheduler.schedulerState shouldBe DownState
        }

        scenario("One host with container service and zero quota") {
            Given("A container with anywhere policy")
            val group = createGroup()
            val container = createContainer(group.getId)

            And("A host with the container service")
            val host = createHost()
            createHostStatus(host.getId, weight = 1, quota = 0)

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
            scheduler.schedulerState shouldBeDownFor(attempts = 0)
        }

        def testOneHostWithEligibleQuota(quota: Int): Unit = {
            Given("A container with anywhere policy")
            val group = createGroup()
            val container = createContainer(group.getId)

            And("A host with the container service")
            val host = createHost()
            createHostStatus(host.getId, weight = 1, quota = quota)

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
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, host.getId)

            And("The scheduler state should be scheduled")
            scheduler.schedulerState shouldBeScheduledFor(container, host.getId)
        }

        scenario("One host with container service and negative quota") {
            testOneHostWithEligibleQuota(-1)
        }

        scenario("One host with container service and positive quota") {
            testOneHostWithEligibleQuota(1)
        }

        scenario("Two hosts, one with positive quota") {
            Given("A container with anywhere policy")
            val group = createGroup()
            val container = createContainer(group.getId)

            And("A host with the container service")
            val host1 = createHost()
            val host2 = createHost()
            createHostStatus(host1.getId, weight = 1, quota = 1)
            createHostStatus(host2.getId, weight = 1, quota = 0)

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
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, host1.getId)

            And("The scheduler state should be scheduled")
            scheduler.schedulerState shouldBeScheduledFor(container, host1.getId)
        }

        scenario("Host starts running the container service with zero quota") {
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
            scheduler.schedulerState shouldBeDownFor(attempts = 0)

            When("Host starts running the container service")
            createHostStatus(host.getId, weight = 1, quota = 0)

            Then("The observer should not receive notifications")
            obs.getOnNextEvents shouldBe empty

            And("The scheduler state should be down")
            scheduler.schedulerState shouldBeDownFor(attempts = 0)
        }

        def testHostStartsWithEligibleQuota(quota: Int): Unit = {
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
            scheduler.schedulerState shouldBeDownFor(attempts = 0)

            When("Host starts running the container service")
            createHostStatus(host.getId, weight = 1, quota = quota)

            Then("The observer should receive a scheduled notification")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, host.getId)

            And("The scheduler state should be scheduled")
            scheduler.schedulerState shouldBeScheduledFor(container, host.getId)
        }

        scenario("Host starts running the container service with negative quota") {
            testHostStartsWithEligibleQuota(-1)
        }

        scenario("Host starts running the container service with positive quota") {
            testHostStartsWithEligibleQuota(1)
        }

        scenario("Adding a host positive quota does not change scheduling") {
            Given("A container with anywhere policy")
            val group = createGroup()
            val container = createContainer(group.getId)

            And("A host with the container service")
            val host1 = createHost()
            createHostStatus(host1.getId, weight = 1, quota = 1)

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should receive a scheduled notification")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, host1.getId)

            And("The scheduler state should be scheduled")
            scheduler.schedulerState shouldBeScheduledFor(container, host1.getId)

            When("Adding a second host with a large quota")
            val host2 = createHost()
            createHostStatus(host2.getId, weight = 1, quota = Int.MaxValue)

            Then("The observer should not receive a new notification")
            obs.getOnNextEvents should have size 1
        }

        scenario("Quota changes to not eligible do not affect scheduling") {
            Given("A container with anywhere policy")
            val group = createGroup()
            val container = createContainer(group.getId)

            And("A host with the container service")
            val host = createHost()
            createHostStatus(host.getId, weight = 1, quota = 1)

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should receive a scheduled notification")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, host.getId)

            And("The scheduler state should be scheduled")
            scheduler.schedulerState shouldBeScheduledFor(container, host.getId)

            When("Changing the host quota to zero")
            createHostStatus(host.getId, weight = 1, quota = 0)

            Then("The observer should not receive another notification")
            obs.getOnNextEvents should have size 1
        }

        scenario("Quota changes to eligible should trigger scheduling") {
            Given("A container with anywhere policy")
            val group = createGroup()
            val container = createContainer(group.getId)

            And("A host with the container service")
            val host = createHost()
            createHostStatus(host.getId, weight = 1, quota = 0)

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should not receive notifications")
            obs.getOnNextEvents shouldBe empty

            And("The scheduler state should be down")
            scheduler.schedulerState shouldBeDownFor(attempts = 0)

            When("Changing the host quota to positive")
            createHostStatus(host.getId, weight = 1, quota = 1)

            Then("The observer should receive a scheduled notification")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, host.getId)

            And("The scheduler state should be scheduled")
            scheduler.schedulerState shouldBeScheduledFor(container, host.getId)
        }

        scenario("Scheduler filters eligible hosts by quota") {
            Given("A container with anywhere policy")
            val group = createGroup()
            val container = createContainer(group.getId)

            And("Several hosts, only one with an eligible quota")
            val host1 = createHost()
            val host2 = createHost()
            val host3 = createHost()
            createHostStatus(host1.getId, weight = 1, quota = 0)
            createHostStatus(host2.getId, weight = 1, quota = 1)
            createHostStatus(host3.getId, weight = 1, quota = 0)

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should receive a scheduled notification")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, host2.getId)

            And("The scheduler state should be scheduled")
            scheduler.schedulerState shouldBeScheduledFor(container, host2.getId)
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
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, host.getId)

            When("The container status is starting")
            createContainerStatus(container.getId, Code.STARTING, host.getId)

            Then("The observer should not receive new notifications")
            obs.getOnNextEvents should have size 1

            And("The scheduler state should be scheduled")
            scheduler.schedulerState shouldBeScheduledFor(container, host.getId)

            When("The container status is running")
            createContainerStatus(container.getId, Code.RUNNING, host.getId)

            Then("The observer should receive a scheduled notification")
            obs.getOnNextEvents should have size 2
            obs.getOnNextEvents.get(1) shouldBeUpFor(container, host.getId)

            And("The scheduler state should be up")
            scheduler.schedulerState shouldBeUpFor(container, host.getId)
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
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, host.getId)

            When("The container status is running")
            createContainerStatus(container.getId, Code.RUNNING, host.getId)

            Then("The observer should receive a scheduled notification")
            obs.getOnNextEvents should have size 2
            obs.getOnNextEvents.get(1) shouldBeUpFor(container, host.getId)

            And("The scheduler state should be up")
            scheduler.schedulerState shouldBeUpFor(container, host.getId)
        }

        scenario("Status reports container stopping") {
            Given("A container with anywhere policy")
            store.clear()
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
            val sub = scheduler.observable subscribe obs

            Then("The observer should receive a scheduled notification")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, host.getId)

            When("The container status")
            createContainerStatus(container.getId, Code.STOPPING, host.getId)

            Then("The observer should not receive another notification")
            obs.getOnNextEvents should have size 1

            And("The scheduler state should be down with retry")
            scheduler.schedulerState shouldBeDownFor(attempts = config.schedulerMaxRetries)

            sub.unsubscribe()
            scheduler.complete()
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
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, host.getId)

            When("The container status is error")
            createContainerStatus(container.getId, Code.ERROR, host.getId)

            Then("The observer should receive a down notification")
            obs.getOnNextEvents should have size 3
            obs.getOnNextEvents.get(1) shouldBeDownFor(container, host.getId)
            obs.getOnNextEvents.get(2) shouldBeUnscheduleFor(container, host.getId)

            And("The scheduler state should be down")
            scheduler.schedulerState shouldBeDownFor(attempts = 0)
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
            obs.getOnNextEvents should have size 3
            scheduler.schedulerState shouldBeDownFor(attempts = 0)

            When("The container status is cleared")
            deleteContainerStatus(container.getId, host.getId)

            Then("The observer should not receive a new notification")
            obs.getOnNextEvents should have size 3
        }

        scenario("Starting, stopping and error status on down container are ignored") {
            Seq(Code.STARTING, Code.STOPPING, Code.ERROR) foreach { code =>
                Given("A container with anywhere policy")
                store.clear()
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
                val sub = scheduler.observable subscribe obs

                And("The container status is error")
                createContainerStatus(container.getId, Code.ERROR, host.getId)

                Then("The observer should receive a down notification")
                obs.getOnNextEvents should have size 3
                scheduler.schedulerState shouldBeDownFor(attempts = 0)

                When("The container status is set")
                createContainerStatus(container.getId, code, host.getId)

                Then("The observer should not receive a new notification")
                obs.getOnNextEvents should have size 3

                sub.unsubscribe()
                scheduler.complete()
            }
        }

        scenario("Clear status on running container triggers reschedule") {
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

            And("The container status is running")
            createContainerStatus(container.getId, Code.RUNNING, host.getId)

            Then("The observer should receive a down notification")
            obs.getOnNextEvents should have size 2
            obs.getOnNextEvents.get(1) shouldBeUpFor(container, host.getId)

            When("The container status is cleared")
            deleteContainerStatus(container.getId, host.getId)

            Then("The observer should receive down and schedule notification")
            obs.getOnNextEvents should have size 4
            obs.getOnNextEvents.get(2) shouldBeDownFor(container, host.getId)
            obs.getOnNextEvents.get(3) shouldBeScheduleFor(container, host.getId)

            And("The scheduler state should be scheduled")
            scheduler.schedulerState shouldBeScheduledFor(container, host.getId)
        }

        scenario("Starting status on running container is ignored") {
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

            And("The container status is running")
            createContainerStatus(container.getId, Code.RUNNING, host.getId)

            And("The container status is starting")
            createContainerStatus(container.getId, Code.STARTING, host.getId)

            Then("The observer should not receive a new notification")
            obs.getOnNextEvents should have size 2
        }

        scenario("Stopping status on running container") {
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

            And("The container status is running")
            createContainerStatus(container.getId, Code.RUNNING, host.getId)

            And("The container status is stopping")
            createContainerStatus(container.getId, Code.STOPPING, host.getId)

            Then("The observer should receive down notification")
            obs.getOnNextEvents should have size 3
            obs.getOnNextEvents.get(2) shouldBeDownFor(container, host.getId)

            And("The scheduler state should be down")
            scheduler.schedulerState shouldBeDownFor(attempts = 0)
        }

        scenario("Error status on running container") {
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

            And("The container status is running")
            createContainerStatus(container.getId, Code.RUNNING, host.getId)

            And("The container status is error")
            createContainerStatus(container.getId, Code.ERROR, host.getId)

            Then("The observer should not receive down notification")
            obs.getOnNextEvents should have size 3
            obs.getOnNextEvents.get(2) shouldBeDownFor(container, host.getId)

            And("The scheduler state should be down")
            scheduler.schedulerState shouldBeDownFor(attempts = 0)
        }

        scenario("Scheduler handles container status error") {
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

            And("The container status is running")
            createContainerStatus(container.getId, Code.RUNNING, host.getId)

            And("The status observable emits an error")
            store.keyObservableError(host.getId.asJava.toString,
                                     classOf[ServiceContainer],
                                     container.getId, MidonetBackend.StatusKey,
                                     new Throwable)

            Then("The observer should receive down and reschedule notification")
            obs.getOnNextEvents should have size 4
            obs.getOnNextEvents.get(2) shouldBeDownFor(container, host.getId)
            obs.getOnNextEvents.get(3) shouldBeScheduleFor(container, host.getId)

            And("The scheduler state should be scheduled")
            scheduler.schedulerState shouldBeScheduledFor(container, host.getId)
        }

        scenario("Scheduler handles invalid container status") {
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

            And("The container status is running")
            createContainerStatus(container.getId, Code.RUNNING, host.getId)

            And("The status is set to an invalid value")
            store.addValueAs(host.getId.asJava.toString, classOf[ServiceContainer],
                             container.getId, MidonetBackend.StatusKey,
                             "some-value").await()

            Then("The observer should receive down and schedule notification")
            obs.getOnNextEvents should have size 4
            obs.getOnNextEvents.get(2) shouldBeDownFor(container, host.getId)
            obs.getOnNextEvents.get(3) shouldBeScheduleFor(container, host.getId)

            And("The scheduler state should be scheduled")
            scheduler.schedulerState shouldBeScheduledFor(container, host.getId)
        }
    }

    feature("Scheduler handles previous scheduling") {
        scenario("Container has port but is not scheduled") {
            Given("A host with container service")
            val host = createHost()
            val port = createPort()
            createHostStatus(host.getId, weight = 1)

            And("A container with anywhere policy already scheduled at the host")
            val group = createGroup()
            val container = createContainer(group.getId, Some(port.getId))

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should receive a scheduled notification")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, host.getId)
        }

        scenario("Container scheduled on host without service status") {
            Given("A host without container service")
            val host = createHost()
            val port = createPort(Some(host.getId))

            And("A container with anywhere policy already scheduled at the host")
            val group = createGroup()
            val container = createContainer(group.getId, Some(port.getId))

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should receive an unscheduled notification")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBeUnscheduleFor(container, host.getId)

            And("The scheduler state should be down")
            scheduler.schedulerState shouldBe DownState
        }

        scenario("Container scheduled on host without service status and other host available") {
            Given("Two hosts, the second with a container service")
            val host1 = createHost()
            val host2 = createHost()
            val port = createPort(Some(host1.getId))
            createHostStatus(host2.getId, weight = 1)

            And("A container with anywhere policy already scheduled at the host")
            val group = createGroup()
            val container = createContainer(group.getId, Some(port.getId))

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should receive an unscheduled notification")
            obs.getOnNextEvents should have size 2
            obs.getOnNextEvents.get(0) shouldBeUnscheduleFor(container, host1.getId)
            obs.getOnNextEvents.get(1) shouldBeScheduleFor(container, host2.getId)

            And("The scheduler state should be rescheduled")
            scheduler.schedulerState shouldBeRescheduledFor(
                container, host1.getId,host2.getId)
        }

        scenario("Container scheduled on host with zero weight") {
            Given("A host with container service")
            val host = createHost()
            val port = createPort(Some(host.getId))
            createHostStatus(host.getId, weight = 0)

            And("A container with anywhere policy already scheduled at the host")
            val group = createGroup()
            val container = createContainer(group.getId, Some(port.getId))

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should receive an unscheduled notification")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBeUnscheduleFor(container, host.getId)

            And("The scheduler state should be down")
            scheduler.schedulerState shouldBe DownState
        }

        scenario("Container scheduled on host with zero weight and other host available") {
            Given("Two hosts, the second with a container service")
            val host1 = createHost()
            val host2 = createHost()
            val port = createPort(Some(host1.getId))
            createHostStatus(host1.getId, weight = 0)
            createHostStatus(host2.getId, weight = 1)

            And("A container with anywhere policy already scheduled at the host")
            val group = createGroup()
            val container = createContainer(group.getId, Some(port.getId))

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should receive an unscheduled notification")
            obs.getOnNextEvents should have size 2
            obs.getOnNextEvents.get(0) shouldBeUnscheduleFor(container, host1.getId)
            obs.getOnNextEvents.get(1) shouldBeScheduleFor(container, host2.getId)

            And("The scheduler state should be rescheduled")
            scheduler.schedulerState shouldBeRescheduledFor(
                container, host1.getId, host2.getId)
        }

        scenario("Container scheduled on eligible host without status") {
            Given("A host with container service")
            val host = createHost()
            val port = createPort(Some(host.getId))
            createHostStatus(host.getId, weight = 1)

            And("A container with anywhere policy already scheduled at the host")
            val group = createGroup()
            val container = createContainer(group.getId, Some(port.getId))

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should not receive notifications")
            obs.getOnNextEvents should have size 0

            And("The scheduler state should be scheduled")
            scheduler.schedulerState shouldBeScheduledFor(container, host.getId)
        }

        scenario("Container scheduled on eligible host with starting status") {
            Given("A host with container service")
            val host = createHost()
            val port = createPort(Some(host.getId))
            createHostStatus(host.getId, weight = 1)

            And("A container with anywhere policy already scheduled at the host")
            val group = createGroup()
            val container = createContainer(group.getId, Some(port.getId))

            And("The container status is starting")
            createContainerStatus(container.getId, Code.STARTING, host.getId)

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should not receive notifications")
            obs.getOnNextEvents should have size 0

            And("The scheduler state should be scheduled")
            scheduler.schedulerState shouldBeScheduledFor(container, host.getId)
        }

        scenario("Container scheduled on eligible host with running status") {
            Given("A host with container service")
            val host = createHost()
            val port = createPort(Some(host.getId))
            createHostStatus(host.getId, weight = 1)

            And("A container with anywhere policy already scheduled at the host")
            val group = createGroup()
            val container = createContainer(group.getId, Some(port.getId))

            And("The container status is running")
            createContainerStatus(container.getId, Code.RUNNING, host.getId)

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should not receive notifications")
            obs.getOnNextEvents should have size 0

            And("The scheduler state should be up")
            scheduler.schedulerState shouldBeUpFor(container, host.getId)
        }

        scenario("Container scheduled with stopping status before and after retry") {
            Given("A host with container service")
            val host = createHost()
            val port = createPort(Some(host.getId))
            createHostStatus(host.getId, weight = 1)

            And("A container with anywhere policy already scheduled at the host")
            val group = createGroup()
            val container = createContainer(group.getId, Some(port.getId))

            And("The container status is stopping")
            createContainerStatus(container.getId, Code.STOPPING, host.getId)

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should not receive a notification")
            obs.getOnNextEvents should have size 0

            And("The scheduler state should be down with retry")
            scheduler.schedulerState shouldBeDownFor(attempts = config.schedulerMaxRetries)

            When("The retry timer expires")
            scheduler.timer onNext 0L

            Then("The observer should not receive an unscheduled notification")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBeUnscheduleFor(container, host.getId)

            And("The scheduler state should be down with retry")
            scheduler.schedulerState shouldBeDownFor(attempts = 1)
        }

        scenario("Container scheduled with stopping status and changes to cleared") {
            Given("A host with container service")
            val host = createHost()
            val port = createPort(Some(host.getId))
            createHostStatus(host.getId, weight = 1)

            And("A container with anywhere policy already scheduled at the host")
            val group = createGroup()
            val container = createContainer(group.getId, Some(port.getId))

            And("The container status is stopping")
            createContainerStatus(container.getId, Code.STOPPING, host.getId)

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should not receive a notification")
            obs.getOnNextEvents should have size 0

            And("The scheduler state should be down with retry")
            scheduler.schedulerState shouldBeDownFor(attempts = config.schedulerMaxRetries)

            When("The container status is cleared")
            deleteContainerStatus(container.getId, host.getId)

            When("The retry timer expires")
            scheduler.timer onNext 0L

            Then("The observer should not receive an unscheduled notification")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBeUnscheduleFor(container, host.getId)

            And("The scheduler state should be down with retry")
            scheduler.schedulerState shouldBeDownFor(attempts = 1)
        }

        scenario("Container scheduled with stopping status and changes to running") {
            Given("A host with container service")
            val host = createHost()
            val port = createPort(Some(host.getId))
            createHostStatus(host.getId, weight = 1)

            And("A container with anywhere policy already scheduled at the host")
            val group = createGroup()
            val container = createContainer(group.getId, Some(port.getId))

            And("The container status is stopping")
            createContainerStatus(container.getId, Code.STOPPING, host.getId)

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should not receive a notification")
            obs.getOnNextEvents should have size 0

            And("The scheduler state should be down with retry")
            scheduler.schedulerState shouldBeDownFor(attempts = config.schedulerMaxRetries)

            When("The container status is changes to running")
            deleteContainerStatus(container.getId, host.getId)
            createContainerStatus(container.getId, Code.STARTING, host.getId)
            createContainerStatus(container.getId, Code.RUNNING, host.getId)

            Then("The observer should receive an up notification")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBeUpFor(container, host.getId)

            And("The scheduler state should be up")
            scheduler.schedulerState shouldBeUpFor(container, host.getId)

            When("The retry timer expires")
            scheduler.timer onNext 0L

            Then("The observer should not receive another notification")
            obs.getOnNextEvents should have size 1

            And("The scheduler state should be up")
            scheduler.schedulerState shouldBeUpFor(container, host.getId)
        }

        scenario("Container scheduled on eligible host with stopping status and other host available") {
            Given("Two hosts with container service")
            val host1 = createHost()
            val host2 = createHost()
            val port = createPort(Some(host1.getId))
            createHostStatus(host1.getId, weight = 1)
            createHostStatus(host2.getId, weight = 1)

            And("A container with anywhere policy already scheduled at the host")
            val group = createGroup()
            val container = createContainer(group.getId, Some(port.getId))

            And("The container status is stopping")
            createContainerStatus(container.getId, Code.STOPPING, host1.getId)

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should not receive another notification")
            obs.getOnNextEvents should have size 0

            When("The retry interval expires")
            scheduler.timer onNext 0L

            Then("The observer should receive two rescheduling notifications")
            obs.getOnNextEvents should have size 2
            obs.getOnNextEvents.get(0) shouldBeUnscheduleFor(container, host1.getId)
            obs.getOnNextEvents.get(1) shouldBeScheduleFor(container, host2.getId)

            And("The scheduler state should be scheduled")
            scheduler.schedulerState shouldBeScheduledFor(container, host2.getId)
        }

        scenario("Container scheduled on eligible host with error status") {
            Given("A host with container service")
            val host = createHost()
            val port = createPort(Some(host.getId))
            createHostStatus(host.getId, weight = 1)

            And("A container with anywhere policy already scheduled at the host")
            val group = createGroup()
            val container = createContainer(group.getId, Some(port.getId))

            And("The container status is error")
            createContainerStatus(container.getId, Code.ERROR, host.getId)

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should receive an unschedule notification")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBeUnscheduleFor(container, host.getId)

            And("The scheduler state should be down")
            scheduler.schedulerState shouldBe DownState
        }

        scenario("Container scheduled on eligible host with error status and other host available") {
            Given("Two hosts with container service")
            val host1 = createHost()
            val host2 = createHost()
            val port = createPort(Some(host1.getId))
            createHostStatus(host1.getId, weight = 1)
            createHostStatus(host2.getId, weight = 1)

            And("A container with anywhere policy already scheduled at the host")
            val group = createGroup()
            val container = createContainer(group.getId, Some(port.getId))

            And("The container status is error")
            createContainerStatus(container.getId, Code.ERROR, host1.getId)

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should receive two rescheduling notifications")
            obs.getOnNextEvents should have size 2
            obs.getOnNextEvents.get(0) shouldBeUnscheduleFor(container, host1.getId)
            obs.getOnNextEvents.get(1) shouldBeScheduleFor(container, host2.getId)

            And("The scheduler state should be rescheduled")
            scheduler.schedulerState shouldBeRescheduledFor(
                container, host1.getId, host2.getId)
        }

        scenario("Container rescheduled while being rescheduled") {
            Given("Three hosts with container service")
            val host1 = createHost()
            val host2 = createHost()
            val host3 = createHost()
            val port = createPort(Some(host1.getId))
            createHostStatus(host1.getId, weight = 1)
            createHostStatus(host2.getId, weight = 1)
            createHostStatus(host3.getId, weight = 0)

            And("A container with anywhere policy already scheduled at the host")
            val group = createGroup()
            val container = createContainer(group.getId, Some(port.getId))

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should not receive any notifications")
            obs.getOnNextEvents should have size 0

            And("The scheduler state should be scheduled")
            scheduler.schedulerState shouldBeScheduledFor(container, host1.getId)

            When("The first host becomes ineligible")
            createHostStatus(host1.getId, weight = 0)

            Then("The container should be scheduled on the second host")
            obs.getOnNextEvents should have size 2
            obs.getOnNextEvents.get(0) shouldBeUnscheduleFor(container, host1.getId)
            obs.getOnNextEvents.get(1) shouldBeScheduleFor(container, host2.getId)

            And("The scheduler state should be rescheduled")
            scheduler.schedulerState shouldBeRescheduledFor(
                container, host1.getId, host2.getId)

            When("Migrating the container to the third host")
            createHostStatus(host3.getId, weight = 1)
            createHostStatus(host2.getId, weight = 0)

            Then("The container should be scheduled on the third host")
            obs.getOnNextEvents should have size 4
            obs.getOnNextEvents.get(2) shouldBeUnscheduleFor(container, host2.getId)
            obs.getOnNextEvents.get(3) shouldBeScheduleFor(container, host3.getId)

            And("The scheduler state should be rescheduled")
            scheduler.schedulerState shouldBeRescheduledFor(
                container, host2.getId, host3.getId)
        }
    }

    feature("Scheduler handles timeouts") {
        scenario("Timeout expires on scheduling") {
            Given("A host with container service")
            val host = createHost()
            createHostStatus(host.getId, weight = 1)

            And("A container with anywhere policy already scheduled at the host")
            val group = createGroup()
            val container = createContainer(group.getId)

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should receive a scheduled notification")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, host.getId)

            When("The timeout expires")
            scheduler.timer onNext 0L

            Then("The observer should receive an unschedule notification")
            obs.getOnNextEvents should have size 2
            obs.getOnNextEvents.get(1) shouldBeUnscheduleFor(container, host.getId)

            And("The scheduler state should be down")
            scheduler.schedulerState shouldBe DownState
        }

        scenario("Timeout expires on scheduling and other host is available") {
            Given("A host with container service")
            val host1 = createHost()
            createHostStatus(host1.getId, weight = 1)

            And("A container with anywhere policy already scheduled at the host")
            val group = createGroup()
            val container = createContainer(group.getId)

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should receive a scheduled notification")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, host1.getId)

            When("A second host becomes available")
            val host2 = createHost()
            createHostStatus(host2.getId, weight = 1)

            When("The timeout expires")
            scheduler.timer onNext 0L

            Then("The observer should receive a reschedule notification")
            obs.getOnNextEvents should have size 3
            obs.getOnNextEvents.get(1) shouldBeUnscheduleFor(container, host1.getId)
            obs.getOnNextEvents.get(2) shouldBeScheduleFor(container, host2.getId)

            And("The scheduler state should be rescheduled")
            scheduler.schedulerState shouldBeRescheduledFor(
                container, host1.getId, host2.getId)

            When("The timeout expires for the second scheduling")
            scheduler.timer onNext 0L

            Then("The observer should receive an unschedule notification")
            obs.getOnNextEvents should have size 4
            obs.getOnNextEvents.get(3) shouldBeUnscheduleFor(container, host2.getId)

            And("The scheduler state should be down")
            scheduler.schedulerState shouldBe DownState
        }

        scenario("Timeout expires on previous scheduling") {
            Given("A host with container service")
            val host = createHost()
            val port = createPort()
            createHostStatus(host.getId, weight = 1)

            And("A container with anywhere policy already scheduled at the host")
            val group = createGroup()
            val container = createContainer(group.getId, Some(port.getId))

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should receive a scheduled notification")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, host.getId)

            When("The timeout expires")
            scheduler.timer onNext 0L

            Then("The observer should receive an unschedule notification")
            obs.getOnNextEvents should have size 2
            obs.getOnNextEvents.get(1) shouldBeUnscheduleFor(container, host.getId)

            And("The scheduler state should be down")
            scheduler.schedulerState shouldBe DownState
        }

        scenario("Timer canceled when the container is running") {
            Given("A host with container service")
            val host = createHost()
            createHostStatus(host.getId, weight = 1)

            And("A container with anywhere policy already scheduled at the host")
            val group = createGroup()
            val container = createContainer(group.getId)

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            And("The container status is running")
            createContainerStatus(container.getId, Code.RUNNING, host.getId)

            Then("The observer should receive a scheduled and up notification")
            obs.getOnNextEvents should have size 2
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, host.getId)
            obs.getOnNextEvents.get(1) shouldBeUpFor(container, host.getId)

            When("The timeout would expire")
            scheduler.timer onNext 0L

            Then("The observer should not receive new notifications")
            obs.getOnNextEvents should have size 2
        }

        scenario("Timer canceled when the container is down") {
            Given("A host with container service")
            val host = createHost()
            createHostStatus(host.getId, weight = 1)

            And("A container with anywhere policy already scheduled at the host")
            val group = createGroup()
            val container = createContainer(group.getId)

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            And("The container status is error")
            createContainerStatus(container.getId, Code.ERROR, host.getId)

            Then("The observer should receive a scheduled and up notification")
            obs.getOnNextEvents should have size 3
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, host.getId)
            obs.getOnNextEvents.get(1) shouldBeDownFor(container, host.getId)
            obs.getOnNextEvents.get(2) shouldBeUnscheduleFor(container, host.getId)

            When("The timeout would expire")
            scheduler.timer onNext 0L

            Then("The observer should not receive new notifications")
            obs.getOnNextEvents should have size 3
        }
    }

    feature("Scheduler handles retries") {
        scenario("Scheduler retries when no hosts are available") {
            Given("A container with anywhere policy")
            val group = createGroup()
            val container = createContainer(group.getId)

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            And("The scheduler state should be down")
            scheduler.schedulerState shouldBeDownFor(isUnsubscribed = false,
                                                     attempts = 0)

            When("The retry expires")
            scheduler.timer onNext 0L

            Then("The scheduler state should be down")
            scheduler.schedulerState shouldBeDownFor(isUnsubscribed = false,
                                                     attempts = 1)
        }

        scenario("Scheduler retries up to the maximum number") {
            Given("A container with anywhere policy")
            val group = createGroup()
            val container = createContainer(group.getId)

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            And("The scheduler state should be down")
            scheduler.schedulerState shouldBeDownFor(isUnsubscribed = false,
                attempts = 0)

            When("The retry expires several times")
            for (index <- 0L to 4L) {
                scheduler.timer onNext index
            }

            Then("The scheduler state should be down and unsubscribed")
            scheduler.schedulerState shouldBeDownFor(isUnsubscribed = true)
        }

        scenario("Scheduler handles RX timers") {
            Given("A container with anywhere policy")
            val group = createGroup()
            val container = createContainer(group.getId)

            And("A host with container service")
            val host = createHost()

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            And("The scheduler state should be down")
            scheduler.schedulerState shouldBeDownFor(isUnsubscribed = false,
                attempts = 0)

            When("The retry expires several times")
            for (index <- 0L to 2L) {
                val timer = scheduler.timer
                scheduler.timer = PublishSubject.create[java.lang.Long]
                timer onNext index
                timer.onCompleted()
            }

            Then("The scheduler state should be down with 3 attempts")
            scheduler.schedulerState shouldBeDownFor(isUnsubscribed = false,
                                                     attempts = 3)
        }
    }

    feature("Scheduler handles bad hosts") {
        scenario("Bad hosts are cleared after bad host lifetime") {
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

            Then("The observer should not receive three notification")
            obs.getOnNextEvents should have size 3
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, host.getId)
            obs.getOnNextEvents.get(1) shouldBeDownFor(container, host.getId)
            obs.getOnNextEvents.get(2) shouldBeUnscheduleFor(container, host.getId)

            When("The time has advanced past the bad host lifetime")
            scheduler.time += config.schedulerBadHostLifetimeMs + 1

            And("Clear the container status")
            deleteContainerStatus(container.getId, host.getId)

            And("Update the group to trigger a rescheduling")
            store update group.toBuilder.addServiceContainerIds(container.getId)
                              .build()

            Then("The observer should receive a schedule notification")
            obs.getOnNextEvents should have size 4
            obs.getOnNextEvents.get(3) shouldBeScheduleFor(container, host.getId)

            And("The scheduler state should be scheduled")
            scheduler.schedulerState shouldBeScheduledFor(container, host.getId)
        }

        scenario("Bad hosts are cleared when a host restarts") {
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

            Then("The observer should not receive three notification")
            obs.getOnNextEvents should have size 3

            When("The host is restarted")
            deleteHostStatus(host.getId)
            deleteContainerStatus(container.getId, host.getId)
            createHostStatus(host.getId, weight = 1)

            Then("The observer should receive a schedule notification")
            obs.getOnNextEvents should have size 4
            obs.getOnNextEvents.get(3) shouldBeScheduleFor(container, host.getId)

            And("The scheduler state should be scheduled")
            scheduler.schedulerState shouldBeScheduledFor(container, host.getId)
        }
    }

    feature("Scheduler handles container updates") {
        scenario("Container group changes for down container") {
            Given("A container with anywhere policy")
            val group1 = createGroup()
            val container1 = createContainer(group1.getId)

            And("A container scheduler")
            val scheduler = newScheduler(container1.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should not receive notifications")
            obs.getOnNextEvents shouldBe empty

            When("Changing the container group")
            val group2 = createGroup()
            val container2 = container1.toBuilder
                .setServiceGroupId(group2.getId)
                .build()
            store update container2

            Then("The observer should not receive notifications")
            obs.getOnNextEvents shouldBe empty
        }

        scenario("Container group changes for scheduled container") {
            Given("A container with anywhere policy")
            val group1 = createGroup()
            val container1 = createContainer(group1.getId)

            And("A host with the container service")
            val host = createHost()
            createHostStatus(host.getId, weight = 1)

            And("A container scheduler")
            val scheduler = newScheduler(container1.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should receive a scheduled notification")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container1, host.getId)

            When("Changing the container group")
            val group2 = createGroup()
            val container2 = container1.toBuilder
                .setServiceGroupId(group2.getId)
                .build()
            store update container2

            Then("The observer should receive a rescheduled notification")
            obs.getOnNextEvents should have size 3
            obs.getOnNextEvents.get(1) shouldBeUnscheduleFor(container1, host.getId)
            obs.getOnNextEvents.get(2) shouldBeScheduleFor(container2, host.getId)
        }

        scenario("Container group changes for running container") {
            Given("A container with anywhere policy")
            val group1 = createGroup()
            val container1 = createContainer(group1.getId)

            And("A host with the container service")
            val host = createHost()
            createHostStatus(host.getId, weight = 1)

            And("A container scheduler")
            val scheduler = newScheduler(container1.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            And("The container status is running")
            createContainerStatus(container1.getId, Code.RUNNING, host.getId)

            Then("The observer should receive scheduled and up notification")
            obs.getOnNextEvents should have size 2
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container1, host.getId)
            obs.getOnNextEvents.get(1) shouldBeUpFor(container1, host.getId)

            When("Changing the container group")
            val group2 = createGroup()
            val container2 = container1.toBuilder
                .setServiceGroupId(group2.getId)
                .build()
            store update container2

            Then("The observer should receive a rescheduled notification")
            obs.getOnNextEvents should have size 6
            obs.getOnNextEvents.get(2) shouldBeDownFor(container1, host.getId)
            obs.getOnNextEvents.get(3) shouldBeUnscheduleFor(container1, host.getId)
            obs.getOnNextEvents.get(4) shouldBeScheduleFor(container2, host.getId)
            obs.getOnNextEvents.get(5) shouldBeUpFor(container2, host.getId)

            And("The scheduler state should be up")
            scheduler.schedulerState shouldBeUpFor(container2, host.getId)
        }
    }

    feature("Scheduler emits cleanup notifications") {
        scenario("Emits cleanup notifications when scheduled container is deleted") {
            Given("A host with container service")
            val host = createHost()
            createHostStatus(host.getId, weight = 1)

            And("A container with anywhere policy already scheduled at the host")
            val group = createGroup()
            val container = createContainer(group.getId)

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should receive a scheduled notification")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, host.getId)

            When("The container is deleted")
            store.delete(classOf[ServiceContainer], container.getId)

            Then("The observer should receive the cleanup notifications")
            obs.getOnNextEvents should have size 2
            obs.getOnCompletedEvents should have size 1
            obs.getOnNextEvents.get(1) shouldBeUnscheduleFor(container, host.getId)

            And("The scheduler state should be down")
            scheduler.schedulerState shouldBe DownState
        }

        scenario("Emits cleanup notifications when rescheduled container is deleted") {
            Given("Two hosts")
            val host1 = createHost()
            val host2 = createHost()
            createHostStatus(host1.getId, weight = 1)
            createHostStatus(host2.getId, weight = 0)

            And("A container with anywhere policy already scheduled at the host")
            val group = createGroup()
            val container = createContainer(group.getId)

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should receive a scheduled notification")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, host1.getId)

            When("Second host is elgigible and the second is non-eligible")
            createHostStatus(host2.getId, weight = 1)
            createHostStatus(host1.getId, weight = 0)

            Then("The observer should receive a rescheduled notification")
            obs.getOnNextEvents should have size 3
            obs.getOnNextEvents.get(1) shouldBeUnscheduleFor(container, host1.getId)
            obs.getOnNextEvents.get(2) shouldBeScheduleFor(container, host2.getId)

            And("The scheduler state should be rescheduled")
            scheduler.schedulerState shouldBeRescheduledFor(
                container, host1.getId, host2.getId)

            When("The container is deleted")
            store.delete(classOf[ServiceContainer], container.getId)

            Then("The observer should receive the cleanup notifications")
            obs.getOnNextEvents should have size 4
            obs.getOnCompletedEvents should have size 1
            obs.getOnNextEvents.get(1) shouldBeUnscheduleFor(container, host1.getId)

            And("The scheduler state should be down")
            scheduler.schedulerState shouldBe DownState
        }

        scenario("Emits cleanup notifications when running container is deleted") {
            Given("A host with container service")
            val host = createHost()
            createHostStatus(host.getId, weight = 1)

            And("A container with anywhere policy already scheduled at the host")
            val group = createGroup()
            val container = createContainer(group.getId)

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            And("The container status is running")
            createContainerStatus(container.getId, Code.RUNNING, host.getId)

            Then("The observer should receive a scheduled and up notification")
            obs.getOnNextEvents should have size 2
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, host.getId)
            obs.getOnNextEvents.get(1) shouldBeUpFor(container, host.getId)

            When("The container is deleted")
            store.delete(classOf[ServiceContainer], container.getId)

            Then("The observer should receive the cleanup notifications")
            obs.getOnNextEvents should have size 3
            obs.getOnCompletedEvents should have size 1
            obs.getOnNextEvents.get(2) shouldBeUnscheduleFor(container, host.getId)

            And("The scheduler state should be down")
            scheduler.schedulerState shouldBe DownState
        }

        scenario("Emits cleanup notifications on error") {
            Given("A host with container service")
            val host = createHost()
            createHostStatus(host.getId, weight = 1)

            And("A container with anywhere policy already scheduled at the host")
            val group = createGroup()
            val container = createContainer(group.getId)

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            And("The container status is running")
            createContainerStatus(container.getId, Code.RUNNING, host.getId)

            Then("The observer should receive a scheduled and up notification")
            obs.getOnNextEvents should have size 2

            When("Emitting an error on one input observable")
            store.observableError(classOf[ServiceContainer], container.getId,
                                  new Throwable)

            Then("The observer should receive the cleanup notifications")
            obs.getOnNextEvents should have size 3
            obs.getOnCompletedEvents should have size 1
            obs.getOnNextEvents.get(2) shouldBeUnscheduleFor(container, host.getId)

            And("The scheduler state should be down")
            scheduler.schedulerState shouldBe DownState
        }

        scenario("Cleanup notifications are not emitted when calling complete") {
            Given("A host with container service")
            val host = createHost()
            createHostStatus(host.getId, weight = 1)

            And("A container with anywhere policy already scheduled at the host")
            val group = createGroup()
            val container = createContainer(group.getId)

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            And("The container status is running")
            createContainerStatus(container.getId, Code.RUNNING, host.getId)

            Then("The observer should receive a scheduled and up notification")
            obs.getOnNextEvents should have size 2

            When("Calling complete on the scheduler")
            scheduler.complete()

            And("The container is deleted")
            store.delete(classOf[ServiceContainer], container.getId)

            Then("The observer should not receive the cleanup notifications")
            obs.getOnNextEvents should have size 2
            obs.getOnCompletedEvents should have size 1
        }
    }

    feature("Scheduler supports manual scheduling") {
        scenario("Unschedule scheduled container") {
            Given("A host with the container service")
            val host = createHost()
            val port1 = createPort()
            createHostStatus(host.getId, weight = 1)

            Given("A container with anywhere policy")
            val group = createGroup()
            val container = createContainer(group.getId, Some(port1.getId))

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should receive a scheduled notification")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, host.getId)

            When("The port is bound to the selected host")
            val port2 = port1.toBuilder.setHostId(host.getId)
                                       .setServiceContainerId(container.getId)
                                       .build()
            store.update(port2)

            Then("The observer should not receive additional notifications")
            obs.getOnNextEvents should have size 1

            When("The container is unscheduled")
            val port3 = port2.toBuilder.clearHostId().build()
            store.update(port3)

            Then("The scheduler should reschedule the container")
            obs.getOnNextEvents should have size 2
            obs.getOnNextEvents.get(1) shouldBeScheduleFor(container, host.getId)

            And("The scheduler state should be rescheduled")
            scheduler.schedulerState shouldBeRescheduledFor(
                container, host.getId, host.getId)
        }

        scenario("Unschedule rescheduled container") {
            Given("Two hosts")
            val host1 = createHost()
            val host2 = createHost()
            val port1 = createPort()
            createHostStatus(host1.getId, weight = 1)
            createHostStatus(host2.getId, weight = 0)

            Given("A container with anywhere policy")
            val group = createGroup()
            val container = createContainer(group.getId, Some(port1.getId))

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should receive a scheduled notification")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, host1.getId)

            And("The scheduler state should be scheduled")
            scheduler.schedulerState shouldBeScheduledFor(container, host1.getId)

            When("The container is rescheduled to the second host")
            createHostStatus(host2.getId, weight = 1)
            createHostStatus(host1.getId, weight = 0)

            Then("The observer should receive a rescheduled notification")
            obs.getOnNextEvents should have size 3
            obs.getOnNextEvents.get(1) shouldBeUnscheduleFor(container, host1.getId)
            obs.getOnNextEvents.get(2) shouldBeScheduleFor(container, host2.getId)

            And("The scheduler state should be rescheduled")
            scheduler.schedulerState shouldBeRescheduledFor(
                container, host1.getId, host2.getId)

            When("The port is bound to the selected host")
            val port2 = port1.toBuilder.setHostId(host2.getId)
                                       .setServiceContainerId(container.getId)
                                       .build()
            store.update(port2)

            Then("The observer should not receive additional notifications")
            obs.getOnNextEvents should have size 3

            When("The container is unscheduled")
            val port3 = port2.toBuilder.clearHostId().build()
            store.update(port3)

            Then("The observer should not receive a new notification")
            obs.getOnNextEvents should have size 3
            // Note: The scheduler expects the port binding to be cleared while
            // recheduling a container from one host to another, therefore it
            // cannot distinguish between the port binding being modified as
            // part of the rescheduling process, and being modified by a third
            // party.

            And("The scheduler state should be rescheduled")
            scheduler.schedulerState shouldBeRescheduledFor(
                container, host1.getId, host2.getId)
        }

        scenario("Unschedule running container") {
            Given("A host with container service")
            val host = createHost()
            val port1 = createPort(Some(host.getId))
            createHostStatus(host.getId, weight = 1)

            And("A container with anywhere policy already scheduled at the host")
            val group = createGroup()
            val container = createContainer(group.getId, Some(port1.getId))

            And("The container status is running")
            createContainerStatus(container.getId, Code.RUNNING, host.getId)

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should not receive notifications")
            obs.getOnNextEvents should have size 0

            And("The scheduler state should be up")
            scheduler.schedulerState shouldBeUpFor(container, host.getId)

            When("The container is unscheduled")
            val port2 = port1.toBuilder.setServiceContainerId(container.getId)
                                       .clearHostId().build()
            store.update(port2)

            Then("The scheduler should reschedule the container")
            obs.getOnNextEvents should have size 2
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, host.getId)
            obs.getOnNextEvents.get(1) shouldBeUpFor(container, host.getId)

            And("The scheduler state should be up")
            scheduler.schedulerState shouldBeUpFor(container, host.getId)
        }

        scenario("Reschedule scheduled container to non-eligible host") {
            Given("Two hosts with the container service")
            val host1 = createHost()
            val host2 = createHost()
            val port1 = createPort()
            createHostStatus(host1.getId, weight = 1)

            Given("A container with anywhere policy")
            val group = createGroup()
            val container = createContainer(group.getId, Some(port1.getId))

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should receive a scheduled notification")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, host1.getId)

            When("The container is rescheduled to the second host")
            val port2 = port1.toBuilder.setHostId(host2.getId)
                                       .setServiceContainerId(container.getId)
                                       .build()
            store.update(port2)

            Then("The scheduler should ignore the manual rescheduling")
            obs.getOnNextEvents should have size 4
            obs.getOnNextEvents.get(1) shouldBeNotifyFor(container, host2.getId)
            obs.getOnNextEvents.get(2) shouldBeUnscheduleFor(container, host2.getId)
            obs.getOnNextEvents.get(3) shouldBeScheduleFor(container, host1.getId)

            And("The scheduler state should be rescheduled")
            scheduler.schedulerState shouldBeRescheduledFor(
                container, host2.getId, host1.getId)
        }

        scenario("Reschedule scheduled container to eligible host") {
            Given("Two hosts with the container service")
            val host1 = createHost()
            val host2 = createHost()
            val port1 = createPort()
            createHostStatus(host1.getId, weight = 1)

            Given("A container with anywhere policy")
            val group = createGroup()
            val container = createContainer(group.getId, Some(port1.getId))

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should receive a scheduled notification")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, host1.getId)

            When("The port is bound to the selected host")
            val port2 = port1.toBuilder.setHostId(host1.getId)
                                       .setServiceContainerId(container.getId)
                                       .build()
            store.update(port2)

            And("Making the second host eligible")
            createHostStatus(host2.getId, weight = 1)

            And("The container is rescheduled to the second host")
            val port3 = port2.toBuilder.setHostId(host2.getId).build()
            store.update(port3)

            Then("The scheduler should accept the manual rescheduling")
            obs.getOnNextEvents should have size 2
            obs.getOnNextEvents.get(1) shouldBeNotifyFor(container, host2.getId)

            And("The scheduler state should be scheduled")
            scheduler.schedulerState shouldBeScheduledFor(container, host2.getId)
        }

        scenario("Reschedule running container to non-eligible host") {
            Given("Two hosts with the container service")
            val host1 = createHost()
            val host2 = createHost()
            val port1 = createPort(Some(host1.getId))
            createHostStatus(host1.getId, weight = 1)

            And("A container with anywhere policy already scheduled at the host")
            val group = createGroup()
            val container = createContainer(group.getId, Some(port1.getId))

            And("The container status is running")
            createContainerStatus(container.getId, Code.RUNNING, host1.getId)

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should not receive notifications")
            obs.getOnNextEvents should have size 0

            When("The container is rescheduled to the second host")
            val port2 = port1.toBuilder.setHostId(host2.getId)
                                       .setServiceContainerId(container.getId)
                                       .build()
            store.update(port2)

            Then("The scheduler should ignore the manual rescheduling")
            obs.getOnNextEvents should have size 4
            obs.getOnNextEvents.get(0) shouldBeNotifyFor(container, host2.getId)
            obs.getOnNextEvents.get(1) shouldBeUnscheduleFor(container, host2.getId)
            obs.getOnNextEvents.get(2) shouldBeScheduleFor(container, host1.getId)
            obs.getOnNextEvents.get(3) shouldBeUpFor(container, host1.getId)

            And("The scheduler state should be up")
            scheduler.schedulerState shouldBeUpFor(container, host1.getId)
        }

        scenario("Reschedule running container to eligible host") {
            Given("Two hosts with the container service")
            val host1 = createHost()
            val host2 = createHost()
            val port1 = createPort(Some(host1.getId))
            createHostStatus(host1.getId, weight = 1)

            And("A container with anywhere policy already scheduled at the host")
            val group = createGroup()
            val container = createContainer(group.getId, Some(port1.getId))

            And("The container status is running")
            createContainerStatus(container.getId, Code.RUNNING, host1.getId)

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should not receive notifications")
            obs.getOnNextEvents should have size 0

            When("Making the second host eligible")
            createHostStatus(host2.getId, weight = 1)

            And("The container is rescheduled to the second host")
            val port2 = port1.toBuilder.setHostId(host2.getId)
                                       .setServiceContainerId(container.getId)
                                       .build()
            store.update(port2)

            Then("The scheduler should accept the manual rescheduling")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBeNotifyFor(container, host2.getId)

            And("The scheduler state should be scheduled")
            scheduler.schedulerState shouldBeScheduledFor(container, host2.getId)
        }

        scenario("Reschedule a rescheduled container") {
            Given("Two hosts with the container service")
            val host1 = createHost()
            val host2 = createHost()
            val host3 = createHost()
            val port1 = createPort(Some(host1.getId))
            createHostStatus(host1.getId, weight = 1)

            And("A container with anywhere policy already scheduled at the host")
            val group = createGroup()
            val container = createContainer(group.getId, Some(port1.getId))

            And("The container status is running")
            createContainerStatus(container.getId, Code.RUNNING, host1.getId)

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should not receive notifications")
            obs.getOnNextEvents should have size 0

            When("Migrating the container to the second host")
            createHostStatus(host2.getId, weight = 1)
            createHostStatus(host1.getId, weight = 0)

            Then("The observer should receive a rescheduled notification")
            obs.getOnNextEvents should have size 3
            obs.getOnNextEvents.get(0) shouldBeDownFor(container, null)
            obs.getOnNextEvents.get(1) shouldBeUnscheduleFor(container, host1.getId)
            obs.getOnNextEvents.get(2) shouldBeScheduleFor(container, host2.getId)

            And("The scheduler state should be rescheduled")
            scheduler.schedulerState shouldBeRescheduledFor(
                container, host1.getId, host2.getId)

            And("The container is rescheduled to the third host")
            createHostStatus(host3.getId, weight = 1)
            val port2 = port1.toBuilder.setHostId(host3.getId)
                                       .setServiceContainerId(container.getId)
                                       .build()
            store.update(port2)

            Then("The scheduler should accept the manual rescheduling")
            obs.getOnNextEvents should have size 4
            obs.getOnNextEvents.get(3) shouldBeNotifyFor(container, host3.getId)

            And("The scheduler state should be rescheduled")
            scheduler.schedulerState shouldBeScheduledFor(container, host3.getId)
        }
    }

    private class ReactiveTestObserver extends TestObserver[SchedulerEvent] {
        val scheduledHosts = new util.ArrayList[UUID]
        val unscheduledHosts = new util.ArrayList[UUID]
        override def onNext(event: SchedulerEvent): Unit = {
            event match {
                case Schedule(container, hostId) =>
                    store.update(
                        store.get(classOf[Port], container.getPortId.asJava)
                            .await().toBuilder
                            .setHostId(hostId.asProto)
                            .build())
                    scheduledHosts add hostId
                case Unschedule(container, hostId) =>
                    store.update(
                        store.get(classOf[Port], container.getPortId.asJava)
                            .await().toBuilder
                            .clearHostId()
                            .build())
                    unscheduledHosts add hostId
                case _ =>
            }
            super.onNext(event)
        }
    }

    feature("Scheduler handles feedback from observer") {
        scenario("Schedule on existing eligible host") {
            Given("A container with anywhere policy")
            val group = createGroup()
            val port = createPort()
            val container = createContainer(group.getId, Some(port.getId))

            And("A host with the container service")
            val host = createHost()
            createHostStatus(host.getId, weight = 1)

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A reactive scheduler observer")
            val obs = new ReactiveTestObserver

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should receive a scheduled notification")
            obs.getOnNextEvents should have size 1
            obs.getOnErrorEvents shouldBe empty
            obs.getOnCompletedEvents shouldBe empty
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, host.getId)

            And("The observer should have scheduled at the host")
            obs.scheduledHosts should contain only host.getId.asJava
            obs.unscheduledHosts shouldBe empty

            And("The scheduler state should be scheduled")
            scheduler.schedulerState shouldBeScheduledFor(container, host.getId)
        }

        scenario("Schedule on starting eligible host") {
            Given("A container with anywhere policy")
            val group = createGroup()
            val port = createPort()
            val container = createContainer(group.getId, Some(port.getId))

            And("A host with the container service")
            val host = createHost()

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A reactive scheduler observer")
            val obs = new ReactiveTestObserver

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should not receive notifications")
            obs.getOnNextEvents shouldBe empty

            And("The scheduler state should be down")
            scheduler.schedulerState shouldBeDownFor(attempts = 0)

            When("Host starts running the container service")
            createHostStatus(host.getId, weight = 1)

            Then("The observer should receive a scheduled notification")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, host.getId)

            And("The observer should have scheduled at the host")
            obs.scheduledHosts should contain only host.getId.asJava
            obs.unscheduledHosts shouldBe empty

            And("The scheduler state should be scheduled")
            scheduler.schedulerState shouldBeScheduledFor(container, host.getId)
        }

        scenario("Single host changes from non-eligible to eligible") {
            Given("A container with anywhere policy")
            val group = createGroup()
            val port = createPort()
            val container = createContainer(group.getId, Some(port.getId))

            And("A host with the container service")
            val host = createHost()
            createHostStatus(host.getId, weight = 0)

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A reactive scheduler observer")
            val obs = new ReactiveTestObserver

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should not receive notifications")
            obs.getOnNextEvents shouldBe empty

            And("The scheduler state should be down")
            scheduler.schedulerState shouldBeDownFor(attempts = 0)

            When("Changing the host weight to positive")
            createHostStatus(host.getId, weight = 1)

            Then("The observer should receive a scheduled notification")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, host.getId)

            And("The observer should have scheduled at the host")
            obs.scheduledHosts should contain only host.getId.asJava
            obs.unscheduledHosts shouldBe empty

            And("The scheduler state should be scheduled")
            scheduler.schedulerState shouldBeScheduledFor(container, host.getId)
        }

        scenario("Single host changes from eligible to non-eligible") {
            Given("A container with anywhere policy")
            val group = createGroup()
            val port = createPort()
            val container = createContainer(group.getId, Some(port.getId))

            And("A host with the container service")
            val host = createHost()
            createHostStatus(host.getId, weight = 1)

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A reactive scheduler observer")
            val obs = new ReactiveTestObserver

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should receive a scheduled notification")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, host.getId)

            And("The observer should have scheduled at the host")
            obs.scheduledHosts should contain only host.getId.asJava
            obs.unscheduledHosts shouldBe empty

            And("The scheduler state should be scheduled")
            scheduler.schedulerState shouldBeScheduledFor(container, host.getId)

            When("Changing the host weight to zero")
            createHostStatus(host.getId, weight = 0)

            Then("The observer should receive an unschedule notification")
            obs.getOnNextEvents should have size 2
            obs.getOnNextEvents.get(1) shouldBeUnscheduleFor(container, host.getId)

            And("The observer should have scheduled at the host")
            obs.scheduledHosts should contain only host.getId.asJava
            obs.unscheduledHosts should contain only host.getId.asJava

            And("The scheduler state should be down")
            scheduler.schedulerState shouldBeDownFor(attempts = 0)
        }

        scenario("Host becomes non-eligible, fallback host available") {
            Given("A container with anywhere policy")
            val group = createGroup()
            val port = createPort()
            val container = createContainer(group.getId, Some(port.getId))

            And("A host with the container service")
            val host1 = createHost()
            createHostStatus(host1.getId, weight = 1)

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A reactive scheduler observer")
            val obs = new ReactiveTestObserver

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should receive a scheduled notification")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, host1.getId)

            And("The observer should have scheduled at the first host")
            obs.scheduledHosts should contain only host1.getId.asJava
            obs.unscheduledHosts shouldBe empty

            And("The scheduler state should be scheduled")
            scheduler.schedulerState shouldBeScheduledFor(container, host1.getId)

            When("Adding a second host")
            val host2 = createHost()
            createHostStatus(host2.getId, weight = 1)

            When("Changing the first host weight to zero")
            createHostStatus(host1.getId, weight = 0)

            Then("The observer should receive reschedule notifications")
            obs.getOnNextEvents should have size 3
            obs.getOnNextEvents.get(1) shouldBeUnscheduleFor(container, host1.getId)
            obs.getOnNextEvents.get(2) shouldBeScheduleFor(container, host2.getId)

            And("The observer should have rescheduled at the second host")
            obs.scheduledHosts should contain inOrder (host1.getId.asJava,
                                                       host2.getId.asJava)
            obs.unscheduledHosts should contain only host1.getId.asJava

            And("The scheduler state should be rescheduled")
            scheduler.schedulerState shouldBeRescheduledFor(
                container, host1.getId, host2.getId)
        }

        scenario("Host becomes non-eligible, fallback host not available") {
            Given("A container with anywhere policy")
            val group = createGroup()
            val port = createPort()
            val container = createContainer(group.getId, Some(port.getId))

            And("A host with container service")
            val host = createHost()
            createHostStatus(host.getId, weight = 1)

            And("A container scheduler")
            val scheduler = newScheduler(container.getId)

            And("A reactive scheduler observer")
            val obs = new ReactiveTestObserver

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            And("The container is reported running")
            createContainerStatus(container.getId, Code.RUNNING, host.getId)

            Then("The observer should receive a scheduled notification")
            obs.getOnNextEvents should have size 2
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, host.getId)
            obs.getOnNextEvents.get(1) shouldBeUpFor(container, host.getId)

            And("The observer should have scheduled at the host")
            obs.scheduledHosts should contain only host.getId.asJava
            obs.unscheduledHosts shouldBe empty

            When("The selected host is no longer eligible")
            deleteHostStatus(host.getId)

            Then("The observer should receive a down notification")
            obs.getOnNextEvents should have size 4
            obs.getOnNextEvents.get(2) shouldBeDownFor(container, host.getId)
            obs.getOnNextEvents.get(3) shouldBeUnscheduleFor(container, host.getId)

            And("The observer should have unscheduled at the host")
            obs.scheduledHosts should contain only host.getId.asJava
            obs.unscheduledHosts should contain only host.getId.asJava

            And("The scheduler state should be down")
            scheduler.schedulerState shouldBeDownFor(attempts = 0)
        }
    }

    feature("Scheduler handles scheduling policies") {
        scenario("Several hosts with least policy") {
            Given("A container with anywhere policy")
            val group = createGroup(ServiceContainerPolicy.LEAST_SCHEDULER)
            val container = createContainer(group.getId)

            And("Several hosts with the container service")
            val host1 = createHost()
            val host2 = createHost()
            val host3 = createHost()
            val host4 = createHost()
            createHostStatus(host1.getId, weight = 1, count = 0)
            createHostStatus(host2.getId, weight = Int.MaxValue, count = 1)
            createHostStatus(host3.getId, weight = Int.MaxValue, count = 2)
            createHostStatus(host4.getId, weight = Int.MaxValue, count = 3)

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
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, host1.getId)

            And("The scheduler state should be scheduled")
            scheduler.schedulerState shouldBeScheduledFor(container, host1.getId)
        }

        scenario("Host count does not change scheduled containers") {
            Given("A container with anywhere policy")
            val group = createGroup(ServiceContainerPolicy.LEAST_SCHEDULER)
            val container = createContainer(group.getId)

            And("Two hosts with the container service")
            val host1 = createHost()
            val host2 = createHost()
            createHostStatus(host1.getId, weight = 1, count = 10)
            createHostStatus(host2.getId, weight = 1, count = 1)

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
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, host2.getId)

            And("The scheduler state should be scheduled")
            scheduler.schedulerState shouldBeScheduledFor(container, host2.getId)

            When("The hosts report new container counters")
            createHostStatus(host1.getId, weight = 1, count = 0)
            createHostStatus(host2.getId, weight = 1, count = 10)

            Then("The observer should not receive new notifications")
            obs.getOnNextEvents should have size 1
        }

        scenario("Policy update does not affect scheduled containers") {
            Given("A container with anywhere policy")
            val group1 = createGroup(ServiceContainerPolicy.LEAST_SCHEDULER)
            val container = createContainer(group1.getId)

            And("Two hosts with the container service")
            val host1 = createHost()
            val host2 = createHost()
            createHostStatus(host1.getId, weight = Int.MaxValue, count = 0)
            createHostStatus(host2.getId, weight = 1, count = 1)

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
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, host1.getId)

            When("The policy changes")
            val group2 = group1.toBuilder.addServiceContainerIds(container.getId)
                                         .setPolicy(ServiceContainerPolicy.WEIGHTED_SCHEDULER)
                                         .build()
            store update group2

            Then("The observer should not receive new notifications")
            obs.getOnNextEvents should have size 1
        }
    }

}
