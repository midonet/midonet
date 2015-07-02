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

import scala.concurrent.duration._

import org.apache.zookeeper.ZooKeeper.States
import org.junit.runner.RunWith
import org.scalatest.GivenWhenThen
import org.scalatest.junit.JUnitRunner
import rx.Observable
import rx.observers.TestObserver

import org.midonet.cluster.data.storage.StorageTestClasses._
import org.midonet.cluster.data.storage.TransactionManager.Key
import org.midonet.cluster.util.{ClassAwaitableObserver, CuratorTestFramework, PathCacheDisconnectedException}
import org.midonet.util.reactivex.AwaitableObserver

@RunWith(classOf[JUnitRunner])
class ZookeeperObjectMapperTest extends StorageTest with CuratorTestFramework
                                with GivenWhenThen {

    import StorageTest._

    private val timeout = 5 seconds

    protected override def setup(): Unit = {
        storage = createStorage
        assert = () => {}
        initAndBuildStorage(storage)
    }

    protected override def createStorage = {
        new ZookeeperObjectMapper(ZK_ROOT, curator)
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

            Then("The storage returns the same observable instance")
            storage.observable(classOf[PojoBridge], bridge.id) eq obs shouldBe true

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

            Then("The storage returns the same observable instance")
            storage.observable(classOf[PojoBridge], bridge.id) eq obs shouldBe true

            When("The observer resubscribes")
            storage.observable(classOf[PojoBridge], bridge.id)
                .subscribe(observer)

            Then("The observer receives the current bridge")
            observer.awaitOnNext(3, timeout)
            observer.getOnNextEvents should have size 3

            And("The storage returns the same observable instance")
            storage.observable(classOf[PojoBridge], bridge.id) eq obs shouldBe true
        }

        scenario("Test subscribe all with GC") {
            val obs = new ClassAwaitableObserver[PojoBridge](0)
            val sub = storage.observable(classOf[PojoBridge]).subscribe(obs)

            val zoom = storage.asInstanceOf[ZookeeperObjectMapper]
            zoom.subscriptionCount(classOf[PojoBridge]) should equal (Option(1))
            sub.unsubscribe()
            zoom.subscriptionCount(classOf[PojoBridge]) should equal (None)

            obs.getOnCompletedEvents should have size 0
            obs.getOnErrorEvents should have size 1
            assert(obs.getOnErrorEvents.get(0)
                       .isInstanceOf[PathCacheDisconnectedException])
        }
    }

    feature("Test Zookeeper") {
        scenario("Test get path") {
            val zoom = storage.asInstanceOf[ZookeeperObjectMapper]
            zoom.getClassPath(classOf[PojoBridge]) shouldBe s"$ZK_ROOT/1/PojoBridge"
        }
    }

    feature("Metrics") {
        scenario("ZK connection state") {
            val zoom = storage.asInstanceOf[ZookeeperObjectMapper]
            zoom.zkConnectionState shouldBe States.CONNECTED.name

            curator.getZookeeperClient.getZooKeeper.close()
            zoom.zkConnectionState shouldBe States.CLOSED.name
        }

        scenario("Object watchers") {
            Given("One bridge")
            val bridge1 = createPojoBridge()
            storage.create(bridge1)

            And("An observer")
            val observer1 = new TestObserver[PojoBridge]
                               with AwaitableObserver[PojoBridge]

            And("A storage observable")
            val obs1 = storage.observable(classOf[PojoBridge], bridge1.id)

            When("The observer subscribes to the observable")
            val sub1 = obs1.subscribe(observer1)
            Then("We receive the bridge")
            observer1.awaitOnNext(1, timeout)

            Then("The number of watchers returned by zoom is 1")
            val zoom = storage.asInstanceOf[ZookeeperObjectMapper]
            zoom.objWatcherCount shouldBe 1

            And("The list of subscribers is correct")
            val key1 = new Key(classOf[PojoBridge], bridge1.id.toString)
            checkSubscribers(List((key1.toString, 1)), zoom.objSubscribers)

            When("We create a 2nd bridge")
            val bridge2 = createPojoBridge()
            storage.create(bridge2)

            And("We subscribe to it")
            val observer2 = new TestObserver[PojoBridge]
                                with AwaitableObserver[PojoBridge]
            val obs2 = storage.observable(classOf[PojoBridge], bridge2.id)
            val sub2 = obs2.subscribe(observer2)

            Then("The number of watchers returned by zoom is 2")
            zoom.objWatcherCount shouldBe 2

            And("The list of subscribers is correct")
            val key2 = new Key(classOf[PojoBridge], bridge2.id.toString)
            checkSubscribers(List((key1.toString, 1), (key2.toString, 1)),
                             zoom.objSubscribers)

            When("A 2nd subscriber subscribers to the 1st bridge")
            val observer3 = new TestObserver[PojoBridge]
                                with AwaitableObserver[PojoBridge]()
            val sub3 = obs1.subscribe(observer3)
            
            Then("The number of watchers is 2")
            zoom.objWatcherCount shouldBe 2
            
            And("The list of subscribers is correct")
            checkSubscribers(List((key1.toString, 2), (key2.toString, 1)),
                             zoom.objSubscribers)
            
            And("When the 1st observer unsubscribes from the 1st bridge")
            sub1.unsubscribe()

            Then("The number of watchers returned by zoom is 2")
            zoom.objWatcherCount shouldBe 2

            And("The list of subscribers is correct")
            checkSubscribers(List((key1.toString, 1), (key2.toString, 1)),
                             zoom.objSubscribers)

            And("When the 2nd observer unsubscribes from the 2nd bridge")
            sub2.unsubscribe()

            Then("The number of watchers returned by zoom is 1")
            zoom.objWatcherCount shouldBe 1

            And("The list of subscribers is correct")
            checkSubscribers(List((key1.toString, 1)), zoom.objSubscribers)

            And("When the 3rd observer unsubscribes from the 1st bridge")
            sub3.unsubscribe()

            Then("The number of watchers returned by zoom is 0")
            zoom.objWatcherCount shouldBe 0

            And("The list of subscribers is empty")
            checkSubscribers(List.empty, zoom.objSubscribers)
        }

        scenario("Class watchers") {
            Given("One bridge")
            val bridge1 = createPojoBridge()
            storage.create(bridge1)

            And("An observer")
            val observer1 = new TestObserver[Observable[PojoBridge]]
                                with AwaitableObserver[Observable[PojoBridge]]

            And("A storage observable")
            val obs1 = storage.observable(classOf[PojoBridge])

            When("The observer subscribes to the observable")
            val sub1 = obs1.subscribe(observer1)
            Then("We receive the bridge observable")
            observer1.awaitOnNext(1, timeout)

            Then("The number of class watchers returned by zoom is 1")
            val zoom = storage.asInstanceOf[ZookeeperObjectMapper]
            zoom.classWatcherCount shouldBe 1

            And("The list of subscribers is correct")
            checkSubscribers(List((classOf[PojoBridge].getName, 1)),
                             zoom.classSubscribers)

            When("A 2nd observer subscribes to the bridge")
            val observer2 = new TestObserver[Observable[PojoBridge]]
                                with AwaitableObserver[Observable[PojoBridge]]
            val sub2 = obs1.subscribe(observer2)

            Then("The number of class watchers is still 1")
            zoom.classWatcherCount shouldBe 1

            And("The list of subscribers is correct")
            checkSubscribers(List((classOf[PojoBridge].getName, 2)),
                             zoom.classSubscribers)

            When("We create a 2nd bridge")
            val bridge2 = createPojoBridge()
            storage.create(bridge2)

            Then("We get notified of the 2nd bridge observable")
            observer1.awaitOnNext(2, timeout) shouldBe true

            And("The number of class watchers is still 1")
            zoom.classWatcherCount shouldBe 1

            And("The list of subscribers is correct")
            checkSubscribers(List((classOf[PojoBridge].getName, 2)),
                             zoom.classSubscribers)

            When("We create a router")
            val router = createPojoRouter()
            storage.create(router)

            And("We subscribe to a router observable")
            val observer3 = new TestObserver[Observable[PojoRouter]]
                                with AwaitableObserver[Observable[PojoRouter]]
            val obs2 = zoom.observable(classOf[PojoRouter])
            val sub3 = obs2.subscribe(observer3)

            Then("We get nofitied of the router observable")
            observer3.awaitOnNext(1, timeout) shouldBe true

            And("The number of class watchers is 2")
            zoom.classWatcherCount shouldBe 2

            And("The list of subscribers is correct")
            checkSubscribers(List((classOf[PojoBridge].getName, 2),
                                  (classOf[PojoRouter].getName, 1)),
                             zoom.classSubscribers)

            And("When the 3 subscriber unsubscribes")
            sub3.unsubscribe()

            Then("The number of class watchers is 1")
            zoom.classWatcherCount shouldBe 1

            And("The list of subscribers is correct")
            checkSubscribers(List((classOf[PojoBridge].getName, 2)),
                             zoom.classSubscribers)

            And("When the two remaining subscribers unsubscribe")
            sub1.unsubscribe()
            sub2.unsubscribe()

            Then("The list of class watchers is 0")
            zoom.classWatcherCount shouldBe 0

            And("The list of subscribers is empty")
            checkSubscribers(List.empty, zoom.classSubscribers)
        }
    }

    private def checkSubscribers(expectedSubscribers: List[(String, Int)],
                                zoomSubscribers: String): Unit = {
        zoomSubscribers.count(_.equals(':')) shouldBe expectedSubscribers.size
        expectedSubscribers.foreach(sub => {
            val key = sub._1
            val count = sub._2
            zoomSubscribers.contains(key + ":" + count) shouldBe true
        })
    }
}
