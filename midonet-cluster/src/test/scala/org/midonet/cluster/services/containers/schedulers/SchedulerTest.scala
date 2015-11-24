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
import java.util.concurrent.Executors

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{GivenWhenThen, Matchers, BeforeAndAfter, FeatureSpec}

import rx.Observable
import rx.observers.TestObserver

import org.midonet.cluster.data.storage._
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.cluster.models.Topology.{Port, Host, ServiceContainerGroup}

import org.midonet.cluster.util.UUIDUtil._
import org.midonet.util.concurrent.SameThreadButAfterExecutorService
import org.midonet.util.functors.{makeAction1, makeFunc1}

import org.midonet.util.reactivex._

@RunWith(classOf[JUnitRunner])
class SchedulerTest extends FeatureSpec with BeforeAndAfter with Matchers with GivenWhenThen with TopologyBuilder {

    private var store: Storage = _
    private var stateStore: StateStorage = _
    private var backdoor: InMemoryStorage  = _
    private val executor = new SameThreadButAfterExecutorService

    before {
        backdoor = new InMemoryStorage
        store = backdoor
        stateStore = backdoor
        MidonetBackend.setupBindings(store, stateStore)
    }

    feature("scheduler lifecycle") {

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
            val observable = scheduler.startScheduling()
            val obs = new TestObserver[CreateContainer]()

            When("Subscribe to the observable")
            observable subscribe obs

            And("The host becomes alive")
            backdoor.addValueAs(host.getId.asJava.toString, classOf[Host],
                                host.getId, MidonetBackend.AliveKey, "alive")
                .await()

            Then("A new container to host allocation is emitted")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0).hostId shouldBe host.getId.asJava

            //Cleanup
            scheduler.stopScheduling()
        }

        scenario("Host is alive before start scheduling") {
            Given("A service container group, a container and a host alive")
            val group = createServiceContainerGroup()
            val container = createServiceContainer(groupId = Some(group.getId))
            val host = createHost()

            store.multi(
                Seq(CreateOp(group), CreateOp(container), CreateOp(host)))

            And("The host is alive before start scheduling")
            backdoor.addValueAs(host.getId.asJava.toString, classOf[Host],
                                host.getId, MidonetBackend.AliveKey, "alive")
                .await()

            And("A scheduler")
            val scheduler = new Scheduler(store, stateStore, executor)
            val observable = scheduler.startScheduling()
            val obs = new TestObserver[CreateContainer]()

            When("Subscribe to the observable")
            observable subscribe obs

            Then("A new container to host allocation is emitted")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0).hostId shouldBe host.getId.asJava

            //Cleanup
            scheduler.stopScheduling()
        }

        scenario("A host is removed after a mapping is done (reschedule)") {
            Given("A service container group, a container and two hosts alive")
            val group = createServiceContainerGroup()
            val container = createServiceContainer(groupId = Some(group.getId))
            val host1 = createHost()
            val host2 = createHost()

            store.multi(Seq(CreateOp(group), CreateOp(container),
                            CreateOp(host1), CreateOp(host2)))

            And("The host is alive before start scheduling")
            backdoor.addValueAs(host1.getId.asJava.toString, classOf[Host],
                                host1.getId, MidonetBackend.AliveKey, "alive")
                .await()
            backdoor.addValueAs(host2.getId.asJava.toString, classOf[Host],
                                host2.getId, MidonetBackend.AliveKey, "alive")
                .await()

            val aliveHosts = List(host1.getId.asJava, host2.getId.asJava)
            And("A scheduler")
            val scheduler = new Scheduler(store, stateStore, executor)
            val observable = scheduler.startScheduling()
            val obs = new TestObserver[CreateContainer]()

            When("Subscribe to the observable")
            observable subscribe obs

            Then("A new container to host allocation is emitted")
            obs.getOnNextEvents should have size 1
            aliveHosts.contains(obs.getOnNextEvents.get(0).hostId) shouldBe true
            val first_host = obs.getOnNextEvents.get(0).hostId

            And("The host becomes unavailable")
            backdoor.removeValueAs(first_host.toString, classOf[Host],
                                   first_host, MidonetBackend.AliveKey, "alive")
                .await()
            Then("The container is rescheduled to another host")
            obs.getOnNextEvents should have size 2
            val second_host = obs.getOnNextEvents.get(1).hostId
            (first_host != second_host) shouldBe true
        }
        scenario("More containers than available hosts") {
            // More containers than nodes
        }
    }

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
            backdoor.addValueAs(host1.getId.asJava.toString, classOf[Host], host1.getId, MidonetBackend.AliveKey, "alive").await()
            backdoor.addValueAs(host2.getId.asJava.toString, classOf[Host], host2.getId, MidonetBackend.AliveKey, "alive").await()
            val aliveHosts = Set(host1.getId.asJava, host2.getId.asJava)

            And("A scheduler")
            val scheduler = new Scheduler(store, stateStore, executor)
            val observable = scheduler.startScheduling()
            val obs = new TestObserver[CreateContainer]()

            When("Subscribe to the observable")
            observable subscribe obs

            Then("We repeatedly allocate a container and fail the corresponding host")
            val attempts = 10
            var previousSelectedHost:UUID = null
            var currentSelectedHost:UUID = null
            for (attempt <- 1 to attempts) {
                When("A new container to host allocation is emitted")
                obs.getOnNextEvents should have size attempt
                previousSelectedHost = currentSelectedHost
                currentSelectedHost = obs.getOnNextEvents.get(attempt-1).hostId

                Then("The chosen hosts are among the eligible")
                aliveHosts.contains(currentSelectedHost) shouldBe true

                And("The previous selected host becomes available")
                if (previousSelectedHost == null) {
                    if (currentSelectedHost == host1.getId.asJava)
                        previousSelectedHost = host2.getId.asJava
                    else previousSelectedHost = host1.getId.asJava
                }
                else {
                    backdoor.addValueAs(previousSelectedHost.toString, classOf[Host],
                                        previousSelectedHost, MidonetBackend.AliveKey, "alive")
                        .await()

                }
                And("The current selected host becomes unavailable")
                backdoor.removeValueAs(currentSelectedHost.toString, classOf[Host],
                                       currentSelectedHost, MidonetBackend.AliveKey, "alive")
                        .await()

            }

            //Cleanup
            scheduler.stopScheduling()


        }
    }

    feature("The port group policy chooses hosts belonging to a port group") {
        scenario("Several nodes and a container using the port group scheduler") {
            Given("Two hosts, a port group with one port associated to one host")
            val host1 = createHost()
            val host2 = createHost()
            val port = createRouterPort(hostId = Some(host1.getId))
            val portgroup = createPortGroup(portIds = Seq(port.getId))

            And("A service container group associated to a port group and one container")
            val group = createServiceContainerGroup(portGroupId = Some(portgroup.getId))
            val container = createServiceContainer(groupId = Some(group.getId))

            store.multi(Seq(CreateOp(host1),
                            CreateOp(host2),
                            CreateOp(port),
                            CreateOp(portgroup),
                            CreateOp(group),
                            CreateOp(container)))

            And("The hosts and port are alive before start scheduling")
            backdoor.addValueAs(host1.getId.asJava.toString, classOf[Host], host1.getId, MidonetBackend.AliveKey, "alive").await()
            backdoor.addValueAs(host2.getId.asJava.toString, classOf[Host], host2.getId, MidonetBackend.AliveKey, "alive").await()
            backdoor.addValueAs(host1.getId.asJava.toString, classOf[Port], port.getId, MidonetBackend.ActiveKey, "active").await()

            And("A scheduler")
            val scheduler = new Scheduler(store, stateStore, executor)
            val observable = scheduler.startScheduling()
            val obs = new TestObserver[CreateContainer]()

            When("Subscribe to the observable")
            observable subscribe obs

            Then("The container is allocated to the corresponding port group")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0).hostId shouldBe host1.getId.asJava

            When("Failing the port in the port group")
            backdoor.removeValueAs(host1.getId.toString, classOf[Port],
                                port.getId, MidonetBackend.ActiveKey, "active")
                .await()

            Then("No new mappings are emitted")
            obs.getOnNextEvents should have size 1

            //Cleanup
            scheduler.stopScheduling()


        }

        scenario("Adding a port to a port group and failing one of them (different hosts)") {
            Given("Two hosts, one port group with one port associated to one host")
            val host1 = createHost()
            val port1 = createRouterPort(hostId = Some(host1.getId))
            val portgroup = createPortGroup(portIds = Seq(port1.getId))
            val host2 = createHost()

            And("A service container group associated to a port group and one container")
            val group = createServiceContainerGroup(portGroupId = Some(portgroup.getId))
            val container = createServiceContainer(groupId = Some(group.getId))

            store.multi(Seq(CreateOp(host1),
                            CreateOp(host2),
                            CreateOp(port1),
                            CreateOp(portgroup),
                            CreateOp(group),
                            CreateOp(container)))

            And("The hosts and port are alive before start scheduling")
            backdoor.addValueAs(host1.getId.asJava.toString, classOf[Host], host1.getId, MidonetBackend.AliveKey, "alive").await()
            backdoor.addValueAs(host2.getId.asJava.toString, classOf[Host], host2.getId, MidonetBackend.AliveKey, "alive").await()
            backdoor.addValueAs(host1.getId.asJava.toString, classOf[Port], port1.getId, MidonetBackend.ActiveKey, "active").await()

            And("A scheduler")
            val scheduler = new Scheduler(store, stateStore, executor)
            val observable = scheduler.startScheduling()
            val obs = new TestObserver[CreateContainer]()

            When("Subscribe to the observable")
            observable subscribe obs

            Then("The container is allocated to the corresponding port group")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0).hostId shouldBe host1.getId.asJava

            When("Adding a port to the port group")
            val port2 = createRouterPort(hostId = Some(host2.getId))
            val portgroup2 = createPortGroup(portgroup.getId, portIds = Seq(port1.getId, port2.getId))
            store.multi(Seq(CreateOp(port2),
                            UpdateOp(portgroup2)))
            backdoor.addValueAs(host2.getId.asJava.toString, classOf[Port], port2.getId, MidonetBackend.ActiveKey, "active").await()

            Then("No new mapping is emitted (the previous one is still valid)")
            obs.getOnNextEvents should have size 1

            When("Setting the bound port down")
            backdoor.removeValueAs(host1.getId.asJava.toString, classOf[Port], port1.getId, MidonetBackend.ActiveKey, "active").await()

            Then("A new mapping is emitted pointing to the second port")
            obs.getOnNextEvents should have size 2
            obs.getOnNextEvents.get(1).hostId shouldBe host2.getId.asJava

           //Cleanup
            scheduler.stopScheduling()
        }

        scenario("Changing the port group associated to a container group") {
            Given("Two hosts, two port group with one port each associated to each host")
            val host1 = createHost()
            val port1 = createRouterPort(hostId = Some(host1.getId))
            val portgroup1 = createPortGroup(portIds = Seq(port1.getId))
            val host2 = createHost()
            val port2 = createRouterPort(hostId = Some(host2.getId))
            val portgroup2 = createPortGroup(portIds = Seq(port2.getId))

            And("A service container group associated to a port group and one container")
            val group = createServiceContainerGroup(portGroupId = Some(portgroup1.getId))
            val container = createServiceContainer(groupId = Some(group.getId))

            store.multi(Seq(CreateOp(host1),
                            CreateOp(host2),
                            CreateOp(port1),
                            CreateOp(port2),
                            CreateOp(portgroup1),
                            CreateOp(portgroup2),
                            CreateOp(group),
                            CreateOp(container)))

            And("The hosts and port are alive before start scheduling")
            backdoor.addValueAs(host1.getId.asJava.toString, classOf[Host], host1.getId, MidonetBackend.AliveKey, "alive").await()
            backdoor.addValueAs(host2.getId.asJava.toString, classOf[Host], host2.getId, MidonetBackend.AliveKey, "alive").await()
            backdoor.addValueAs(host1.getId.asJava.toString, classOf[Port], port1.getId, MidonetBackend.ActiveKey, "active").await()
            backdoor.addValueAs(host2.getId.asJava.toString, classOf[Port], port2.getId, MidonetBackend.ActiveKey, "active").await()

            And("A scheduler")
            val scheduler = new Scheduler(store, stateStore, executor)
            val observable = scheduler.startScheduling()
            val obs = new TestObserver[CreateContainer]()

            When("Subscribe to the observable")
            observable subscribe obs

            Then("The container is allocated to the corresponding port group")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0).hostId shouldBe host1.getId.asJava

            When("Changin the port group")
            val group2 = createServiceContainerGroup(group.getId, portGroupId = Some(portgroup2.getId))
            val container2 = createServiceContainer(container.getId, groupId = Some(group2.getId))
            store.multi(Seq(UpdateOp(group2),
                            UpdateOp(container2)))
            Then("A new mapping is emitted with the container on the new port group")
            obs.getOnNextEvents should have size 2
            obs.getOnNextEvents.get(1).hostId shouldBe host2.getId.asJava

           //Cleanup
            scheduler.stopScheduling()
        }
    }

}
