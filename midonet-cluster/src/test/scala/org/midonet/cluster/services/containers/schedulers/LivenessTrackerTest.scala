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

import scala.concurrent.ExecutionContext

import com.typesafe.scalalogging.Logger

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FeatureSpec, GivenWhenThen, Matchers}
import org.slf4j.LoggerFactory

import rx.observers.TestObserver
import rx.schedulers.Schedulers

import org.midonet.cluster.data.storage._
import org.midonet.cluster.models.Topology.Host
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.MidonetBackend.AliveKey
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.util.reactivex._

@RunWith(classOf[JUnitRunner])
class LivenessTrackerTest extends FeatureSpec with BeforeAndAfter with Matchers
                            with GivenWhenThen with TopologyBuilder {

    private var store: Storage = _
    private var stateStore: StateStorage = _
    private var backdoor: InMemoryStorage = _
    private val executor =  Executors.newSingleThreadExecutor()
    private implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(executor)
    private val log = Logger(LoggerFactory.getLogger(getClass))

    before {
        backdoor = new InMemoryStorage
        store = backdoor
        stateStore = backdoor
        MidonetBackend.setupBindings(backdoor, backdoor)
    }

    feature("Liveness tracker monitors an object state") {
        scenario("Not watching any host") {
            Given("A host that is alive")
            val host = createHost()
            store.create(host)
            backdoor.addValueAs(host.getId.asJava.toString, classOf[Host],
                                host.getId, AliveKey, "alive").await()

            When("Watching starts")
            val tracker = new LivenessTracker[Host](stateStore, AliveKey,
                                                    Schedulers.immediate(), log)
            val obs = new TestObserver[Liveness]
            tracker.observable subscribe obs

            Then("No events are emitted")
            obs.getOnNextEvents should have size 0
        }

        scenario("Watching one alive host") {
            Given("A host that is alive")
            val host = createHost()
            store.create(host)
            backdoor.addValueAs(host.getId.asJava.toString, classOf[Host],
                                host.getId, AliveKey, "alive").await()

            When("Watching starts")
            val tracker = new LivenessTracker[Host](stateStore, AliveKey,
                                                    Schedulers.immediate(), log)
            val obs = new TestObserver[Liveness]
            tracker.observable subscribe obs
            tracker.watch(Set(host.getId))

            Then("An alive event is emitted")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBe Alive(host.getId)
        }

        scenario("Watching a non-existing host") {
            Given("Nothing")
            When("Watching starts")
            val tracker = new LivenessTracker[Host](stateStore, AliveKey,
                                                    Schedulers.immediate(), log)
            val obs = new TestObserver[Liveness]
            tracker.observable subscribe obs
            tracker.watch(Set(UUID.randomUUID()))

            Then("No event is emitted")
            obs.getOnNextEvents should have size 0
        }

        scenario("Watching a non-existing host that becomes available later on") {
            Given("An id")
            val id = UUID.randomUUID()
            When("Watching starts")
            val tracker = new LivenessTracker[Host](stateStore, AliveKey,
                                                    Schedulers.immediate(), log)
            val obs = new TestObserver[Liveness]
            tracker.observable subscribe obs
            tracker.watch(Set(id))

            Then("No event is emitted")
            obs.getOnNextEvents should have size 0

            When("The host with the watched id becomes alive")
            val host = createHost(id = id)
            store.create(host)
            backdoor.addValueAs(host.getId.asJava.toString, classOf[Host],
                                host.getId, AliveKey, "alive").await()

            Then("No new event is emitted")
            obs.getOnNextEvents should have size 0

            When("We watch again the same host")
            tracker.watch(Set(id))

            Then("A new event is emitted")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBe Alive(id)

        }

        scenario("Watching a host that becomes dead") {
            Given("A host that is alive")
            val host = createHost()
            store.create(host)
            backdoor.addValueAs(host.getId.asJava.toString, classOf[Host],
                                host.getId, AliveKey, "alive").await()

            When("Watching starts")
            val tracker = new LivenessTracker[Host](stateStore, AliveKey,
                                                    Schedulers.immediate(), log)
            val obs = new TestObserver[Liveness]
            tracker.observable subscribe obs
            tracker.watch(Set(host.getId))

            Then("An alive event is emitted")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBe Alive(host.getId)

            When("The host goes down")
            backdoor.removeValueAs(host.getId.asJava.toString, classOf[Host],
                                   host.getId, AliveKey, "alive").await()

            Then("A dead event is emitted")
            obs.getOnNextEvents should have size 2
            obs.getOnNextEvents.get(1) shouldBe Dead(host.getId)
        }

        scenario("Changing the state of a not watched host") {
            Given("Two hosts that are alive")
            val host_watched = createHost()
            val host_not_watched = createHost()
            store.multi(Seq(CreateOp(host_watched), CreateOp(host_not_watched)))
            backdoor.addValueAs(host_watched.getId.asJava.toString, classOf[Host],
                                host_watched.getId, AliveKey, "alive").await()
            backdoor.addValueAs(host_not_watched.getId.asJava.toString, classOf[Host],
                                host_not_watched.getId, AliveKey, "alive").await()

            When("Watching starts")
            val tracker = new LivenessTracker[Host](stateStore, AliveKey,
                                                    Schedulers.immediate(), log)
            val obs = new TestObserver[Liveness]
            tracker.observable subscribe obs
            tracker.watch(Set(host_watched.getId))

            Then("An alive event is emitted")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBe Alive(host_watched.getId)

            When("The not watched host goes down")
            backdoor.removeValueAs(host_not_watched.getId.asJava.toString, classOf[Host],
                                   host_not_watched.getId, AliveKey, "alive").await()

            Then("No new events are emitted")
            obs.getOnNextEvents should have size 1
        }

        scenario("Watching twice on the same id") {
            Given("A host that is alive")
            val host = createHost()
            store.create(host)
            backdoor.addValueAs(host.getId.asJava.toString, classOf[Host],
                                host.getId, AliveKey, "alive").await()

            When("Watching starts")
            val tracker = new LivenessTracker[Host](stateStore, AliveKey,
                                                    Schedulers.immediate(), log)
            val obs = new TestObserver[Liveness]
            tracker.observable subscribe obs
            tracker.watch(Set(host.getId))

            Then("An alive event is emitted")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBe Alive(host.getId)

            When("Watching the same host again")
            tracker.watch(Set(host.getId))

            Then("No new events are emitted")
            obs.getOnNextEvents should have size 1
        }

        scenario("Adding a host to the watcher set") {
            Given("Two hosts that are alive")
            val host1 = createHost()
            val host2 = createHost()
            store.multi(Seq(CreateOp(host1), CreateOp(host2)))
            backdoor.addValueAs(host1.getId.asJava.toString, classOf[Host],
                                host1.getId, AliveKey, "alive").await()
            backdoor.addValueAs(host2.getId.asJava.toString, classOf[Host],
                                host2.getId, AliveKey, "alive").await()

            When("Watching starts")
            val tracker = new LivenessTracker[Host](stateStore, AliveKey,
                                                    Schedulers.immediate(), log)
            val obs = new TestObserver[Liveness]
            tracker.observable subscribe obs
            tracker.watch(Set(host1.getId))

            Then("An alive event is emitted")
            obs.getOnNextEvents should have size 1
            obs.getOnNextEvents.get(0) shouldBe Alive(host1.getId)

            When("Watching the second host")
            tracker.watch(Set(host2.getId))

            Then("An alive event is emitted for the second host")
            obs.getOnNextEvents should have size 2
            obs.getOnNextEvents.get(1) shouldBe Alive(host2.getId)
        }
    }
}