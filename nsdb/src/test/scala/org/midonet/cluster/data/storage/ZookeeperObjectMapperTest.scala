/*
 * Copyright 2014 Midokura SARL
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
package org.midonet.cluster.data.storage

import java.util.UUID

import scala.concurrent.duration._

import org.junit.runner.RunWith
import org.scalatest.GivenWhenThen
import org.scalatest.junit.JUnitRunner
import rx.observers.TestObserver

import org.midonet.cluster.data.storage.StorageTestClasses._
import org.midonet.cluster.util.{MidonetBackendTest, ClassAwaitableObserver, PathCacheDisconnectedException}
import org.midonet.util.reactivex.AwaitableObserver

@RunWith(classOf[JUnitRunner])
class ZookeeperObjectMapperTest extends StorageTest with MidonetBackendTest
                                with GivenWhenThen {

    import StorageTest._

    private val timeout = 5 seconds
    private val hostId = UUID.randomUUID.toString

    protected override def setup(): Unit = {
        storage = createStorage
        assert = () => {}
        initAndBuildStorage(storage)
    }

    protected override def createStorage = {
        new ZookeeperObjectMapper(zkRoot, hostId, curator, reactor,
                                  connection, connectionWatcher)
    }

    feature("Test subscribe") {
        scenario("Test object observable recovers after close") {
            Given("A bridge")
            val bridge = createPojoBridge()
            storage.create(bridge)

            And("An observer")
            val observer = new TestObserver[PojoBridge]
                               with AwaitableObserver[PojoBridge]

            And("A storage observable")
            val obs = storage.observable(classOf[PojoBridge], bridge.id)

            When("The observer subscribes to the observable")
            val sub = obs.subscribe(observer)

            Then("The observer receives the current bridge")
            observer.awaitOnNext(1, timeout)
            observer.getOnNextEvents should have size 1

            When("The observer unsubscribes")
            sub.unsubscribe()

            Then("The storage does not return the same observable instance")
            storage.observable(classOf[PojoBridge], bridge.id) ne obs shouldBe true

            When("The observer resubscribes")
            storage.observable(classOf[PojoBridge], bridge.id)
                   .subscribe(observer)

            Then("The observer receives the current bridge")
            observer.awaitOnNext(2, timeout)
            observer.getOnNextEvents should have size 2

            And("The storage returns a different observable instance")
            storage.observable(classOf[PojoBridge], bridge.id) ne obs shouldBe true
        }

        scenario("Test object observable is reused by concurrent subscribers") {
            Given("A bridge")
            val bridge = createPojoBridge()
            storage.create(bridge)

            And("An observer")
            val observer = new TestObserver[PojoBridge]
                               with AwaitableObserver[PojoBridge]

            And("A storage observable")
            val obs = storage.observable(classOf[PojoBridge], bridge.id)

            When("The observer subscribes to the observable")
            val sub1 = obs.subscribe(observer)

            Then("The observer receives the current bridge")
            observer.awaitOnNext(1, timeout)
            observer.getOnNextEvents should have size 1

            When("The observer subscribes a second time to the observable")
            val sub2 = obs.subscribe(observer)

            Then("The observer receives the current bridge")
            observer.awaitOnNext(2, timeout)
            observer.getOnNextEvents should have size 2

            When("The first subscription unsubscribes")
            sub1.unsubscribe()

            Then("The storage does not return the same observable instance")
            storage.observable(classOf[PojoBridge], bridge.id) ne obs shouldBe true

            When("The observer resubscribes")
            storage.observable(classOf[PojoBridge], bridge.id)
                .subscribe(observer)

            Then("The observer receives the current bridge")
            observer.awaitOnNext(3, timeout)
            observer.getOnNextEvents should have size 3

            And("The storage does not return the same observable instance")
            storage.observable(classOf[PojoBridge], bridge.id) ne obs shouldBe true
        }

        scenario("Test subscribe all with GC") {
            val obs = new ClassAwaitableObserver[PojoBridge](0)
            val sub = storage.observable(classOf[PojoBridge]).subscribe(obs)

            val zoom = storage.asInstanceOf[ZookeeperObjectMapper]
            zoom.subscriptionCount(classOf[PojoBridge]) shouldBe Option(1)
            sub.unsubscribe()
            zoom.subscriptionCount(classOf[PojoBridge]) shouldBe None

            obs.getOnCompletedEvents should have size 0
            obs.getOnErrorEvents should have size 1
            assert(obs.getOnErrorEvents.get(0)
                       .isInstanceOf[PathCacheDisconnectedException])
        }
    }

    feature("Test Zookeeper") {
        scenario("Test get path") {
            val zoom = storage.asInstanceOf[ZookeeperObjectMapper]
            zoom.classPath(classOf[PojoBridge]) shouldBe s"${zkRoot}/${zoom.version}/models/PojoBridge"
        }
    }
}
