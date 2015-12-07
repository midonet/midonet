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

import java.util.concurrent.Executors

import scala.concurrent.duration._

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FeatureSpec, GivenWhenThen, Matchers}

import org.midonet.cluster.data.storage._
import org.midonet.cluster.models.Topology.{Host, ServiceContainerGroup}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.MidonetBackend.AliveKey
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.util.reactivex._
import org.midonet.util.concurrent.toFutureOps

@RunWith(classOf[JUnitRunner])
class SchedulerTest extends FeatureSpec with BeforeAndAfter with Matchers
                            with GivenWhenThen with TopologyBuilder {

    private var store: Storage = _
    private var stateStore: StateStorage = _
    private var backdoor: InMemoryStorage  = _
    private val executor = Executors.newSingleThreadExecutor()
    private val timeout = 5 seconds

    before {
        backdoor = new InMemoryStorage
        store = backdoor
        stateStore = backdoor
        MidonetBackend.setupBindings(store, stateStore)
    }

    feature("scheduler handles service container group updates") {
        scenario("Host becomes alive after scheduling") {
            Given("A service container group, a container and a host not alive")
            val group = createServiceContainerGroup()
            val container = createServiceContainer(groupId = Some(group.getId))
            val host = createHost()

            store.multi(
                Seq(CreateOp(group), CreateOp(container), CreateOp(host)))

            And("A scheduler")
            val scheduler = new Scheduler(store, stateStore, executor)
            val obs = new TestAwaitableObserver[ContainerEvent]

            When("Scheduling starts")
            scheduler.eventObservable subscribe obs
            scheduler.startScheduling()

            And("The host becomes alive")
            backdoor.addValueAs(host.getId.asJava.toString, classOf[Host],
                                host.getId, AliveKey, "alive").await()

            Then("A new container to host allocation is emitted")
            obs.awaitOnNext(1, timeout)
            obs.getOnNextEvents.get(0)
               .asInstanceOf[Allocation].hostId shouldBe host.getId.asJava

            scheduler.stopScheduling()
            obs.getOnNextEvents should have size 1
        }

        scenario("A host is removed after a mapping is done (reschedule)") {
            Given("A service container group, a container and two hosts alive")
            var group = createServiceContainerGroup()
            val groupId = group.getId.asJava
            val container = createServiceContainer(groupId = Some(group.getId))
            val containerId = container.getId.asJava
            val host1 = createHost()
            val host2 = createHost()
            val host1Id = host1.getId.asJava
            val host2Id = host2.getId.asJava

            store.multi(Seq(CreateOp(group), CreateOp(container),
                            CreateOp(host1), CreateOp(host2)))
            // reload to pick up the backrefs
            group = store.get(classOf[ServiceContainerGroup], groupId).await()

            And("Hosts that are alive before starting to schedule")
            backdoor.addValueAs(host1Id.toString, classOf[Host],
                                host1Id, AliveKey, "alive").await()
            backdoor.addValueAs(host2Id.toString, classOf[Host],
                                host2Id, AliveKey, "alive").await()

            And("A scheduler")
            val scheduler = new Scheduler(store, stateStore, executor)
            scheduler.startScheduling()
            val obs = new TestAwaitableObserver[ContainerEvent]()

            When("Subscribe to the observable")
            scheduler.eventObservable subscribe obs

            Then("A new container to host allocation is emitted")
            obs.awaitOnNext(1, timeout)

            val primaryHostId = obs.getOnNextEvents.get(0)
                                   .asInstanceOf[Allocation].hostId

            val failbackHostId = if (primaryHostId == host1Id) host2Id
                                 else host1Id

            And("The first host goes down")
            backdoor.removeValueAs(primaryHostId.toString, classOf[Host],
                                   primaryHostId, AliveKey, "alive").await()

            Then("The container is rescheduled to another host")
            obs.awaitOnNext(3, timeout)
            scheduler.stopScheduling()

            obs.getOnNextEvents.get(0) shouldEqual Allocation(containerId, group, primaryHostId)
            obs.getOnNextEvents.get(1) shouldBe Deallocation(containerId, group, primaryHostId)
            obs.getOnNextEvents.get(2) shouldBe Allocation(containerId, group, failbackHostId)
            obs.getOnNextEvents should contain allOf (
                Allocation(containerId, group, primaryHostId),
                Deallocation(containerId, group, primaryHostId),
                Allocation(containerId, group, failbackHostId)
            )
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
            val obs = new TestObserver[ContainerEvent]()

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
