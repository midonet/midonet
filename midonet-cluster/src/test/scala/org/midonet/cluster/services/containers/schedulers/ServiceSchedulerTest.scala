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

import scala.concurrent.duration._

import com.typesafe.config.ConfigFactory

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FeatureSpec, GivenWhenThen, Matchers}

import rx.observers.TestObserver

import org.midonet.cluster.ContainersConfig
import org.midonet.cluster.util.UUIDUtil._

@RunWith(classOf[JUnitRunner])
class ServiceSchedulerTest extends FeatureSpec with SchedulersTest
                           with BeforeAndAfter with Matchers
                           with GivenWhenThen {

    private val timeout = 5 seconds
    private val config = new ContainersConfig(ConfigFactory.parseString(
        """
          |cluster.containers.enabled : true
          |cluster.containers.scheduler_timeout : 10s
          |cluster.containers.scheduler_bad_host_lifetime : 300s
        """.stripMargin))

    feature("Scheduler handles service container group updates") {
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
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, group, host.getId)

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
            obs.getOnNextEvents.get(0) shouldBeScheduleFor(container, group, host1.getId)

            When("Second host starts running")
            createHostStatus(host2.getId, weight = 1)

            And("The first host goes down")
            deleteHostStatus(host1.getId)

            Then("The container is rescheduled to the second host")
            obs.getOnNextEvents should have size 3
            obs.getOnNextEvents.get(1) shouldBeUnscheduleFor(container, group, host1.getId)
            obs.getOnNextEvents.get(2) shouldBeScheduleFor(container, group, host2.getId)
        }

        /*
        scenario("More containers than available hosts") {
            // More containers than nodes
        }
        */
    }

    /*
    feature("The anywhere policy chooses hosts randomly") {
        scenario("Several nodes and a container using the anywhere scheduler") {
            Given("A service container group, one container and two hosts alive")
            val group = createServiceContainerGroup()
            val container1 = createServiceContainer(groupId = Some(group.getId))
            val host1 = createHost()
            val host2 = createHost()
            val host3 = createHost()

            store.multi(Seq(CreateOp(group),
                            CreateOp(container1),
                            CreateOp(host1),
                            CreateOp(host2)))

            And("The hosts are alive before start scheduling")
            backdoor.addValueAs(host1.getId.asJava.toString, classOf[Host],
                                host1.getId, AliveKey, "alive").await()
            backdoor.addValueAs(host2.getId.asJava.toString, classOf[Host],
                                host2.getId, AliveKey, "alive").await()
            val aliveHosts = Set(host1.getId.asJava, host2.getId.asJava)

            And("A scheduler")
            val scheduler = new Scheduler(store, stateStore, executor)
            val observable = scheduler.startScheduling()
            val obs = new TestObserver[SchedulerEvent]()

            When("Subscribe to the observable")
            observable subscribe obs

            Then("We repeatedly allocate a container and fail the corresponding host")
            val attempts = 10
            var previousSelectedHost: UUID = null
            var currentSelectedHost: UUID = null
            for (attempt <- 1 to attempts) {
                When("A new container to host allocation is emitted")
                obs.getOnNextEvents should have size attempt
                previousSelectedHost = currentSelectedHost
                currentSelectedHost = obs.getOnNextEvents.get(attempt-1)
                    .asInstanceOf[Allocation].hostId

                Then("The chosen hosts are among the eligible")
                aliveHosts.contains(currentSelectedHost) shouldBe true

                And("The previous selected host becomes available")
                if (previousSelectedHost == null) {
                    if (currentSelectedHost == host1.getId.asJava)
                        previousSelectedHost = host2.getId.asJava
                    else previousSelectedHost = host1.getId.asJava
                } else {
                    backdoor.addValueAs(previousSelectedHost.toString,
                                        classOf[Host], previousSelectedHost,
                                        AliveKey, "alive").await()

                }
                And("The current selected host becomes unavailable")
                backdoor.removeValueAs(currentSelectedHost.toString,
                                       classOf[Host], currentSelectedHost,
                                       AliveKey, "alive").await()

            }

            //Cleanup
            scheduler.stopScheduling()


        }
    }
    */

}
