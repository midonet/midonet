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

package org.midonet.cluster.state

import java.util.UUID

import scala.concurrent.duration._
import scala.util.Random

import com.codahale.metrics.MetricRegistry

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FlatSpec, GivenWhenThen, Matchers}

import rx.observers.TestObserver
import rx.subjects.BehaviorSubject

import org.midonet.cluster.data.storage.KeyType._
import org.midonet.cluster.data.storage.metrics.StorageMetrics
import org.midonet.cluster.data.storage.{StateResult, ZookeeperObjectMapper}
import org.midonet.cluster.models.Topology.Port
import org.midonet.cluster.services.MidonetBackend._
import org.midonet.cluster.state.PortStateStorage._
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.cluster.util.MidonetBackendTest
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.util.reactivex._

@RunWith(classOf[JUnitRunner])
class PortStateStorageTest extends FlatSpec with MidonetBackendTest
                                   with Matchers with GivenWhenThen
                                   with TopologyBuilder {

    private var storage: ZookeeperObjectMapper = _
    private val hostId = UUID.randomUUID
    private var ownerId: Long = _
    private val random = new Random
    private final val timeout = 5 seconds

    protected override def setup(): Unit = {
        storage = new ZookeeperObjectMapper(config, hostId.toString, curator,
                                            curator, stateTables, reactor,
                                            new StorageMetrics(new MetricRegistry))
        ownerId = curator.getZookeeperClient.getZooKeeper.getSessionId
        initAndBuildStorage(storage)
    }

    private def initAndBuildStorage(storage: ZookeeperObjectMapper): Unit = {
        storage.registerClass(classOf[Port])
        storage.registerKey(classOf[Port], ActiveKey, SingleLastWriteWins)
        storage.build()
    }

    "State" should "return the active state" in {
        PortInactive.isActive shouldBe false
        PortActive(UUID.randomUUID(), tunnelKey = None).isActive shouldBe true
    }

    "Store" should "set the port to active" in {
        Given("A bridge port")
        val port = createBridgePort()
        storage.create(port)

        And("A tunnel key")
        val tunnelKey = random.nextLong()

        When("Setting the port as active")
        storage.setPortActive(port.getId, hostId, active = true, tunnelKey)
               .await(timeout) shouldBe StateResult(ownerId)

        Then("The port status should be active")
        storage.portState(port.getId, hostId)
               .await(timeout) shouldBe PortActive(hostId, Some(tunnelKey))

        When("Setting the port as inactive")
        storage.setPortActive(port.getId, hostId, active = false)
               .await(timeout) shouldBe StateResult(ownerId)

        Then("The port status should be inactive")
        storage.portState(port.getId, hostId)
               .await(timeout) shouldBe PortInactive
    }

    "Store observable" should "emit notifications on port status updates" in {
        Given("A bridge port")
        val port = createBridgePort()
        storage.create(port)

        And("A tunnel key")
        val tunnelKey = random.nextLong()

        And("An observer")
        val obs = new TestObserver[PortState] with AwaitableObserver[PortState]

        And("A namespace subject")
        val namespaces = BehaviorSubject.create[UUID](null)

        When("Subscribing the observer")
        storage.portStateObservable(port.getId, namespaces).subscribe(obs)

        Then("The observer should receive the port as inactive")
        obs.awaitOnNext(1, timeout) shouldBe true
        obs.getOnNextEvents.get(0) shouldBe PortInactive

        When("Updating the namespace")
        namespaces onNext hostId

        Then("The observer should receive the port as inactive")
        obs.awaitOnNext(2, timeout) shouldBe true
        obs.getOnNextEvents.get(1) shouldBe PortInactive

        When("Setting the port as active")
        storage.setPortActive(port.getId, hostId, active = true, tunnelKey)
               .await(timeout) shouldBe StateResult(ownerId)

        Then("The observer should receive the port as active")
        obs.awaitOnNext(3, timeout) shouldBe true
        obs.getOnNextEvents.get(2) shouldBe PortActive(hostId, Some(tunnelKey))

        When("Setting the port as inactive")
        storage.setPortActive(port.getId, hostId, active = false)
               .await(timeout) shouldBe StateResult(ownerId)

        Then("The observer should receive the port as inactive")
        obs.awaitOnNext(4, timeout) shouldBe true
        obs.getOnNextEvents.get(3) shouldBe PortInactive

        When("Clearing the namespace")
        namespaces onNext null

        Then("The observer should receive the port as inactive")
        obs.awaitOnNext(5, timeout) shouldBe true
        obs.getOnNextEvents.get(4) shouldBe PortInactive
    }

    "Store" should "be backwards compatible" in {
        Given("A bridge port")
        val port = createBridgePort()
        storage.create(port)

        When("Setting the port as active in compatibility mode")
        storage.addValue(classOf[Port], port.getId, ActiveKey, hostId.toString)
               .await(timeout)

        Then("The port status should be active without a tunnel key")
        storage.portState(port.getId, hostId)
               .await(timeout) shouldBe PortActive(hostId, None)

        When("Setting the port as inactive in compatibility mode")
        storage.removeValue(classOf[Port], port.getId, ActiveKey, hostId.toString)
               .await(timeout)

        Then("The port status should be inactive")
        storage.portState(port.getId, hostId)
               .await(timeout) shouldBe PortInactive
    }

    "Store" should "handle invalid state data" in {
        Given("A bridge port")
        val port = createBridgePort()
        storage.create(port)

        When("Writing invalid data to the port state")
        storage.addValue(classOf[Port], port.getId, ActiveKey, "invalid-data")
               .await(timeout)

        Then("The port status should be inactive")
        storage.portState(port.getId, hostId)
               .await(timeout) shouldBe PortInactive
    }

}
