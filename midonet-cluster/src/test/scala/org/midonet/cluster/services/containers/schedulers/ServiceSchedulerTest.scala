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

import scala.collection.JavaConverters._
import scala.concurrent.duration._

import com.typesafe.config.ConfigFactory

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FeatureSpec, GivenWhenThen, Matchers}

import rx.observers.TestObserver

import org.midonet.cluster.conf.ContainersConfig
import org.midonet.cluster.models.Topology.ServiceContainer
import org.midonet.cluster.util.UUIDUtil._

@RunWith(classOf[JUnitRunner])
class ServiceSchedulerTest extends FeatureSpec with SchedulersTest
                           with BeforeAndAfter with Matchers
                           with GivenWhenThen {

    private val config = new ContainersConfig(ConfigFactory.parseString(
        """
          |cluster.containers.enabled : true
          |cluster.containers.scheduler_timeout : 10s
          |cluster.containers.scheduler_retry : 15s
          |cluster.containers.scheduler_max_retries : 3
          |cluster.containers.scheduler_bad_host_lifetime : 300s
        """.stripMargin))

    private def newScheduler(): ServiceScheduler = {
        new ServiceScheduler(context, config)
    }

    feature("Scheduler handles subscriptions") {
        scenario("Scheduler supports only one subscriber") {
            Given("A container scheduler")
            val scheduler = newScheduler()

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

    feature("Scheduler handles container updates") {
        scenario("Scheduler does not emit for no containers") {
            And("A service scheduler")
            val scheduler = newScheduler()

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should not receive notifications")
            obs.getOnNextEvents shouldBe empty

            And("There should be no containers")
            scheduler.containerIds shouldBe empty
        }

        scenario("Scheduler emits existing containers") {
            Given("Several containers")
            val group = createGroup()
            val container1 = createContainer(group.getId)
            val container2 = createContainer(group.getId)
            val container3 = createContainer(group.getId)

            And("A host with the container service")
            val host = createHost()
            createHostStatus(host.getId, weight = 1)

            And("A service scheduler")
            val scheduler = newScheduler()

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should receive the schedule notification for all containers")
            obs.getOnNextEvents should have size 3
            obs.getOnNextEvents.asScala
               .map(_.asInstanceOf[Schedule].container) should contain allOf(
                    container1, container2, container3)

            And("There should be three containers")
            scheduler.containerIds should contain allOf(
                container1.getId.asJava,
                container2.getId.asJava,
                container3.getId.asJava)
        }

        scenario("Scheduler emits added containers") {
            Given("A host with the container service")
            val host = createHost()
            val group = createGroup()
            createHostStatus(host.getId, weight = 1)

            And("A service scheduler")
            val scheduler = newScheduler()

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            And("Adding a container")
            val container1 = createContainer(group.getId)

            Then("The observer should receive the schedule notification for all containers")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container1, host.getId)

            And("There should be one container")
            scheduler.containerIds should contain only container1.getId.asJava

            When("Adding a second container")
            val container2 = createContainer(group.getId)

            Then("The observer should receive the schedule notification for all containers")
            obs.getOnNextEvents should have size 2
            obs.getOnNextEvents.get(1) shouldBeScheduleFor(container2, host.getId)

            And("There should be two containers")
            scheduler.containerIds should contain allOf(
                container1.getId.asJava,
                container2.getId.asJava)
        }

        scenario("Scheduler removes deleted containers") {
            Given("Several containers")
            val group = createGroup()
            val container1 = createContainer(group.getId)
            val container2 = createContainer(group.getId)
            val container3 = createContainer(group.getId)

            And("A host with the container service")
            val host = createHost()
            createHostStatus(host.getId, weight = 1)

            And("A service scheduler")
            val scheduler = newScheduler()

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should receive the schedule notification for all containers")
            obs.getOnNextEvents should have size 3

            When("A container is deleted")
            store.delete(classOf[ServiceContainer], container1.getId)

            Then("The observer should receive an unschedule notification")
            obs.getOnNextEvents should have size 4
            obs.getOnNextEvents.get(3) shouldBeUnscheduleFor(container1, host.getId)

            And("There should be two containers")
            scheduler.containerIds should contain allOf(
                container2.getId.asJava,
                container3.getId.asJava)
        }
    }

    feature("Scheduler merges container scheduler notifications") {
        scenario("Host starts running after scheduling") {
            Given("A service container group, a container and a host not running")
            val group = createGroup()
            val container = createContainer(group.getId)
            val host = createHost()

            And("A scheduler")
            val scheduler = new ServiceScheduler(context, config)
            val obs = new TestObserver[SchedulerEvent]

            When("Subscribing to the observable")
            scheduler.observable subscribe obs

            And("The host begins running the container service with postive weight")
            createHostStatus(host.getId, weight = 1)

            Then("The observer receives a schedule event")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, host.getId)

            scheduler.complete()
        }

        scenario("A host is removed after a mapping is done (reschedule)") {
            Given("A service container group, a container and two hosts alive")
            val group = createGroup()
            val container = createContainer(group.getId)
            val host1 = createHost()
            val host2 = createHost()

            And("First host is running before starting to schedule")
            createHostStatus(host1.getId, weight = 1)

            And("A scheduler")
            val scheduler = new ServiceScheduler(context, config)
            val obs = new TestObserver[SchedulerEvent]

            When("Subscribing to the observable")
            scheduler.observable subscribe obs

            Then("The observer receives a schedule event")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, host1.getId)

            When("Second host starts running")
            createHostStatus(host2.getId, weight = 1)

            And("The first host goes down")
            deleteHostStatus(host1.getId)

            Then("The container is rescheduled to the second host")
            obs.getOnNextEvents should have size 3
            obs.getOnNextEvents.get(1) shouldBeUnscheduleFor(container, host1.getId)
            obs.getOnNextEvents.get(2) shouldBeScheduleFor(container, host2.getId)
        }

        scenario("Scheduler does not emit notifications after complete") {
            Given("Several containers")
            val group = createGroup()
            val container1 = createContainer(group.getId)
            val container2 = createContainer(group.getId)

            And("A host with the container service")
            val host = createHost()
            createHostStatus(host.getId, weight = 1)

            And("A service scheduler")
            val scheduler = newScheduler()

            And("A scheduler observer")
            val obs = new TestObserver[SchedulerEvent]

            When("The observer subscribes to the scheduler")
            scheduler.observable subscribe obs

            Then("The observer should receive the schedule notification for all containers")
            obs.getOnNextEvents should have size 2

            When("Complete is called")
            scheduler.complete()

            Then("The observer should receive a completed notification")
            obs.getOnNextEvents should have size 2
            obs.getOnCompletedEvents should have size 1

            When("Adding a new container")
            val container3 = createContainer(group.getId)

            Then("The observer should not receive new notifications")
            obs.getOnNextEvents should have size 2

            And("There should be two containers")
            scheduler.containerIds should contain allOf(
                container1.getId.asJava,
                container2.getId.asJava)
        }
    }
}
