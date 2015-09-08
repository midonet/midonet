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

package org.midonet.southbound.vtep

import java.util.UUID

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

import org.junit.runner.RunWith
import org.mockito.Mockito
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FeatureSpec, GivenWhenThen, Matchers}
import rx.subjects.BehaviorSubject

import org.midonet.cluster.data.vtep.VtepConnection.ConnectionState._
import org.midonet.cluster.data.vtep.model.MacLocation
import org.midonet.cluster.data.vtep.{VtepDataClient, VtepStateException}
import org.midonet.southbound.vtep.OvsdbVtepBuilder._
import org.midonet.southbound.vtep.mock.InMemoryOvsdbVtep
import org.midonet.util.concurrent._
import org.midonet.util.reactivex.TestAwaitableObserver

@RunWith(classOf[JUnitRunner])
class OvsdbVtepDataClientTest extends FeatureSpec with BeforeAndAfter
                                      with Matchers with GivenWhenThen {

    private val timeout = 5 seconds
    private var vtep: InMemoryOvsdbVtep = _
    private var connection: OvsdbVtepConnection = _
    private val state = BehaviorSubject.create(Disconnected)

    private def createVtep(): VtepDataClient = {
        state onNext Disconnected
        Mockito.when(connection.getHandle).thenReturn(Some(vtep.getHandle))
        Mockito.when(connection.connect())
               .thenReturn(Future.successful(Connected))
        Mockito.when(connection.disconnect())
               .thenReturn(Future.successful(Disconnected))
        Mockito.when(connection.observable).thenReturn(state)

        OvsdbVtepDataClient(connection)
    }

    before {
        vtep = new InMemoryOvsdbVtep
        connection = Mockito.mock(classOf[OvsdbVtepConnection])
    }

    feature("Client futures returns fails if VTEP is disconnected") {
        scenario("Physical switch") {
            Given("A VTEP client")
            val client = createVtep()

            Then("Requesting the physical switch should fail")
            intercept[VtepStateException] {
                Await.result(client.physicalSwitch, timeout)
            }
        }

        scenario("Logical switch") {
            Given("A logical switch")
            val ls = vtep.createLogicalSwitch()

            And("A VTEP client")
            val client = createVtep()

            Then("Requesting the logical switch should fail")
            intercept[VtepStateException] {
                Await.result(client.logicalSwitch(ls.name), timeout)
            }
        }

        scenario("Current local MAC") {
            Given("A VTEP client")
            val client = createVtep()

            Then("Requesting the current local MAC table should fail")
            intercept[VtepStateException] {
                Await.result(client.currentMacLocal, timeout)
            }
        }

        scenario("Local MAC updates") {
            Given("A VTEP with a physical swicth, logical switch and MAC")
            val ps = vtep.createPhysicalSwitch()
            val ls = vtep.createLogicalSwitch()
            val mac = vtep.createLocalUcastMac(ls.uuid)

            And("A VTEP client")
            val client = createVtep()

            When("An observer subscribes to local MAC updates")
            val observer = new TestAwaitableObserver[MacLocation]
            client.macLocalUpdates.subscribe(observer)

            And("Connecting the client")
            client.connect().await(timeout)

            Then("The observer should receive the MAC location")
            observer.awaitCompletion(timeout)
            observer.getOnErrorEvents.get(0).getClass shouldBe classOf[VtepStateException]
        }

        scenario("Remote MAC updater") {
            Given("A VTEP client")
            val client = createVtep()

            Then("Requesting the remote MAC updater should fail")
            intercept[VtepStateException] {
                Await.result(client.macRemoteUpdater, timeout)
            }
        }

        scenario("Create logical switch") {
            Given("A VTEP client")
            val client = createVtep()

            Then("Creating a logical switch should fail")
            intercept[VtepStateException] {
                Await.result(client.createLogicalSwitch("ls-name", 100), timeout)
            }
        }

        scenario("Remove logical switch") {
            Given("A VTEP with a logical switch")
            val ls = vtep.createLogicalSwitch()

            And("A VTEP client")
            val client = createVtep()

            Then("Removing the logical switch should fail")
            intercept[VtepStateException] {
                Await.result(client.deleteLogicalSwitch(ls.uuid), timeout)
            }
        }

        scenario("Set bindings") {
            Given("A VTEP with a physical and logical switch")
            vtep.createPhysicalSwitch()
            val ls = vtep.createLogicalSwitch()

            And("A VTEP client")
            val client = createVtep()

            When("Setting the bindings should fail")
            intercept[VtepStateException] {
                Await.result(client.setBindings(ls.uuid, Iterable.empty),
                             timeout)
            }
        }
    }

    feature("Client futures returns data when the VTEP is connecting") {
        scenario("Physical switch") {
            Given("A VTEP client")
            val client = createVtep()

            When("The client is connecting")
            state onNext Connecting

            And("Requesting the physical switch")
            val future = client.physicalSwitch
            future.isCompleted shouldBe false

            And("The client is ready")
            state onNext Ready

            Then("Requesting the VXLAN tunnel IP should succeed")
            Await.result(future, timeout) should not be null
        }

        scenario("Logical switch") {
            Given("A logical switch")
            val ls = vtep.createLogicalSwitch()

            And("A VTEP client")
            val client = createVtep()

            When("The client is connecting")
            state onNext Connecting

            And("Requesting the logical switch")
            val future = client.logicalSwitch(ls.name)
            future.isCompleted shouldBe false

            And("The client is ready")
            state onNext Ready

            Then("Requesting the VXLAN tunnel IP should succeed")
            Await.result(future, timeout) should not be null
        }

        scenario("Current local MAC") {
            Given("A VTEP client")
            val client = createVtep()

            When("The client is connecting")
            state onNext Connecting

            And("Requesting the current local MAC table")
            val future = client.currentMacLocal
            future.isCompleted shouldBe false

            And("The client is ready")
            state onNext Ready

            Then("Requesting the current local MAC table should succeed")
            Await.result(future, timeout) should not be null
        }

        scenario("Local MAC updates") {
            Given("A VTEP with a physical swicth, logical switch and MAC")
            val ps = vtep.createPhysicalSwitch()
            val ls = vtep.createLogicalSwitch()
            val mac = vtep.createLocalUcastMac(ls.uuid)

            And("A VTEP client")
            val client = createVtep()

            When("The client is connecting")
            state onNext Connecting

            And("An observer subscribes to local MAC updates")
            val observer = new TestAwaitableObserver[MacLocation]
            client.macLocalUpdates.subscribe(observer)
            observer.getOnNextEvents should have size 0

            And("The client is ready")
            state onNext Ready

            Then("The observer should receive the MAC location")
            observer.awaitOnNext(1, timeout) shouldBe true
            observer.getOnNextEvents.get(0) shouldBe new MacLocation(
                mac.mac, mac.ip, ls.name,
                Await.result(client.physicalSwitch, timeout).get.tunnelIp.get)
        }

        scenario("Remote MAC updater") {
            Given("A VTEP client")
            val client = createVtep()

            When("The client is connecting")
            state onNext Connecting

            And("Requesting the remote MAC updater")
            val future = client.macRemoteUpdater
            future.isCompleted shouldBe false

            And("The client is ready")
            state onNext Ready

            Then("Requesting the remote MAC updater should succeed")
            Await.result(future, timeout) should not be null
        }

        scenario("Create logical switch") {
            Given("A VTEP client")
            val client = createVtep()

            When("The client is connecting")
            state onNext Connecting

            And("Creating a logical switch")
            val future = client.createLogicalSwitch("ls-name", 100)
            future.isCompleted shouldBe false

            And("The client is ready")
            state onNext Ready

            Then("Creating a logical switch should succeed")
            Await.result(future, timeout) should not be null
        }

        scenario("Remove logical switch") {
            Given("A VTEP with a logical switch")
            val lsId = UUID.randomUUID()
            vtep.createLogicalSwitch(lsId)

            And("A VTEP client")
            val client = createVtep()

            When("The client is connecting")
            state onNext Connecting

            And("Removing the logical switch")
            val future = client.deleteLogicalSwitch(lsId)
            future.isCompleted shouldBe false

            And("The client is ready")
            state onNext Ready

            Then("Removing a logical switch should succeed")
            Await.result(future, timeout) shouldBe 1
        }

        scenario("Set bindings") {
            Given("A VTEP with a physical and logical switch")
            vtep.createPhysicalSwitch()
            val ls = vtep.createLogicalSwitch()

            And("A VTEP client")
            val client = createVtep()

            When("The client is connecting")
            state onNext Connecting

            And("Setting the bindings")
            val future = client.setBindings(ls.uuid, Iterable.empty)
            future.isCompleted shouldBe false

            And("The client is ready")
            state onNext Ready

            Then("Creating bindings should succeed")
            Await.result(future, timeout) shouldBe 0
        }
    }

    feature("Client futures fail if the VTEP connection is broken") {
        scenario("Physical switch") {
            Given("A VTEP client")
            val client = createVtep()

            When("The client is broken")
            state onNext Broken

            Then("Requesting the physical should fail")
            intercept[VtepStateException] {
                Await.result(client.physicalSwitch, timeout)
            }
        }

        scenario("Logical switch") {
            Given("A logical switch")
            val ls = vtep.createLogicalSwitch()

            And("A VTEP client")
            val client = createVtep()

            When("The client is broken")
            state onNext Broken

            Then("Requesting the physical should fail")
            intercept[VtepStateException] {
                Await.result(client.logicalSwitch(ls.name), timeout)
            }
        }

        scenario("Current local MAC") {
            Given("A VTEP client")
            val client = createVtep()

            When("The client is broken")
            state onNext Broken

            Then("Requesting the current local MAC table should fail")
            intercept[VtepStateException] {
                Await.result(client.currentMacLocal, timeout)
            }
        }

        scenario("Local MAC updates") {
            Given("A VTEP with a physical swicth, logical switch and MAC")
            val ps = vtep.createPhysicalSwitch()
            val ls = vtep.createLogicalSwitch()
            val mac = vtep.createLocalUcastMac(ls.uuid)

            And("A VTEP client")
            val client = createVtep()

            When("The client is ready")
            state onNext Ready

            And("An observer subscribes to local MAC updates")
            val observer = new TestAwaitableObserver[MacLocation]
            client.macLocalUpdates.subscribe(observer)

            Then("The observer should receive the MAC location")
            observer.awaitOnNext(1, timeout) shouldBe true
            observer.getOnNextEvents.get(0) shouldBe new MacLocation(
                mac.mac, mac.ip, ls.name,
                Await.result(client.physicalSwitch, timeout).get.tunnelIp.get)

            When("The client is broken")
            state onNext Broken

            Then("The observer should receive an error")
            observer.awaitCompletion(timeout)
            observer.getOnErrorEvents.get(0).getClass shouldBe classOf[VtepStateException]
        }

        scenario("Remote MAC updater") {
            Given("A VTEP client")
            val client = createVtep()

            When("The client is broken")
            state onNext Broken

            Then("Requesting the remote MAC updater should fail")
            intercept[VtepStateException] {
                Await.result(client.macRemoteUpdater, timeout)
            }
        }

        scenario("Create logical switch") {
            Given("A VTEP client")
            val client = createVtep()

            When("The client is broken")
            state onNext Broken

            Then("Creating a logical switch should fail")
            intercept[VtepStateException] {
                Await.result(client.createLogicalSwitch("ls-name", 100), timeout)
            }
        }

        scenario("Remove logical switch") {
            Given("A VTEP with a logical switch")
            val ls = vtep.createLogicalSwitch()

            And("A VTEP client")
            val client = createVtep()

            When("The client is broken")
            state onNext Broken

            Then("Removing a logical switch should fail")
            intercept[VtepStateException] {
                Await.result(client.deleteLogicalSwitch(ls.uuid), timeout)
            }
        }

        scenario("Set bindings") {
            Given("A VTEP with a physical and logical switch")
            vtep.createPhysicalSwitch()
            val ls = vtep.createLogicalSwitch()

            And("A VTEP client")
            val client = createVtep()

            When("The client is broken")
            state onNext Broken

            Then("Setting bindings should fail")
            intercept[VtepStateException] {
                Await.result(client.setBindings(ls.uuid, Iterable.empty),
                             timeout)
            }
        }
    }

}
