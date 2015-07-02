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

import com.codahale.metrics.{Metric, MetricFilter, Gauge, MetricRegistry}
import org.apache.zookeeper.ZooKeeper.States
import org.junit.runner.RunWith
import org.scalatest.GivenWhenThen
import org.scalatest.junit.JUnitRunner
import rx.Observable
import rx.observers.TestObserver

import org.midonet.cluster.data.storage.StorageTestClasses._
import org.midonet.cluster.util.{ClassAwaitableObserver, CuratorTestFramework, PathCacheDisconnectedException}
import org.midonet.util.reactivex.AwaitableObserver

@RunWith(classOf[JUnitRunner])
class ZookeeperObjectMapperTest extends StorageTest with CuratorTestFramework
                                with GivenWhenThen {

    import StorageTest._

    private val timeout = 5 seconds
    private var registry: MetricRegistry = _

    protected override def setup(): Unit = {
        registry = new MetricRegistry()
        storage = createStorage
        assert = () => {}
        initAndBuildStorage(storage)
    }

    protected override def createStorage = {
        new ZookeeperObjectMapper(ZK_ROOT, curator, registry)
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
            zoom.objectObservableCount shouldBe 1

            And("The list of object observers is correct")
            val pojoBridgeClazz = classOf[PojoBridge].getName
            checkObjectObservables(List((pojoBridgeClazz, 1)),
                                   zoom.objectObservableCountPerClass)

            When("We create a 2nd bridge")
            val bridge2 = createPojoBridge()
            storage.create(bridge2)

            And("We subscribe to it")
            val observer2 = new TestObserver[PojoBridge]
                                with AwaitableObserver[PojoBridge]
            val obs2 = storage.observable(classOf[PojoBridge], bridge2.id)
            val sub2 = obs2.subscribe(observer2)

            Then("The number of watchers returned by zoom is 2")
            zoom.objectObservableCount shouldBe 2

            And("The list of object observers is correct")
            checkObjectObservables(List((pojoBridgeClazz, 2)),
                                   zoom.objectObservableCountPerClass)

            When("A 2nd subscriber subscribers to the 1st bridge")
            val observer3 = new TestObserver[PojoBridge]
                                with AwaitableObserver[PojoBridge]()
            val sub3 = obs1.subscribe(observer3)

            Then("The number of watchers is 2")
            zoom.objectObservableCount shouldBe 2

            And("The list of object observers is correct")
            checkObjectObservables(List((pojoBridgeClazz, 2)),
                                   zoom.objectObservableCountPerClass)

            When("We create a router")
            val router = createPojoRouter()
            storage.create(router)

            And("We subscribe to it")
            val observer4 = new TestObserver[PojoRouter]
                                with AwaitableObserver[PojoRouter]
            val obs3 = storage.observable(classOf[PojoRouter], router.id)
            val sub4 = obs3.subscribe(observer4)

            Then("The number of watchers returned by zoom is 3")
            zoom.objectObservableCount shouldBe 3

            And("The list of object observers is correct")
            val pojoRouterClazz = classOf[PojoRouter].getName
            checkObjectObservables(List((pojoBridgeClazz, 2),
                                        (pojoRouterClazz, 1)),
                                   zoom.objectObservableCountPerClass)

            And("When the 1st observer unsubscribes from the 1st bridge")
            sub1.unsubscribe()

            Then("The number of watchers returned by zoom is 3")
            zoom.objectObservableCount shouldBe 3

            And("The list of object observers is correct")
            checkObjectObservables(List((pojoBridgeClazz, 2),
                                        (pojoRouterClazz, 1)),
                                   zoom.objectObservableCountPerClass)

            And("When the 2nd observer unsubscribes from the 2nd bridge")
            sub2.unsubscribe()

            Then("The number of watchers returned by zoom is 2")
            zoom.objectObservableCount shouldBe 2

            And("The list of object observers is correct")
            checkObjectObservables(List((pojoBridgeClazz, 1),
                                        (pojoRouterClazz, 1)),
                                   zoom.objectObservableCountPerClass)

            And("When the 3rd observer unsubscribes from the 1st bridge")
            sub3.unsubscribe()

            Then("The number of watchers returned by zoom is 1")
            zoom.objectObservableCount shouldBe 1

            And("The list of object observers is correct")
            checkObjectObservables(List((pojoRouterClazz, 1)),
                                   zoom.objectObservableCountPerClass)

            When("The 4th subscriber unsubscribes from the router")
            sub4.unsubscribe()

            Then("The number of watchers returned by zoom is 0")
            zoom.objectObservableCount shouldBe 0

            And("The list of object observers is empty")
            checkObjectObservables(List.empty,
                                   zoom.objectObservableCountPerClass)
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
            zoom.classObservableCount shouldBe 1

            And("The list of class observers is correct")
            val pojoBridgeClazz = classOf[PojoBridge].getName
            checkClassObservables(List(pojoBridgeClazz), zoom.classObservables)

            When("A 2nd observer subscribes to the bridge")
            val observer2 = new TestObserver[Observable[PojoBridge]]
                                with AwaitableObserver[Observable[PojoBridge]]
            val sub2 = obs1.subscribe(observer2)

            Then("The number of class watchers is still 1")
            zoom.classObservableCount shouldBe 1

            And("The list of class observers is correct")
            checkClassObservables(List(pojoBridgeClazz), zoom.classObservables)

            When("We create a 2nd bridge")
            val bridge2 = createPojoBridge()
            storage.create(bridge2)

            Then("We get notified of the 2nd bridge observable")
            observer1.awaitOnNext(2, timeout) shouldBe true

            And("The number of class watchers is still 1")
            zoom.classObservableCount shouldBe 1

            And("The list of class observers is correct")
            checkClassObservables(List(pojoBridgeClazz), zoom.classObservables)

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
            zoom.classObservableCount shouldBe 2

            And("The list of class observers is correct")
            val pojoRouterClazz = classOf[PojoRouter].getName
            checkClassObservables(List(pojoBridgeClazz, pojoRouterClazz),
                                  zoom.classObservables)

            And("When the 3 subscriber unsubscribes")
            sub3.unsubscribe()

            Then("The number of class watchers is 1")
            zoom.classObservableCount shouldBe 1

            And("The list of class observers is correct")
            checkClassObservables(List(pojoBridgeClazz), zoom.classObservables)


            And("When the two remaining subscribers unsubscribe")
            sub1.unsubscribe()
            sub2.unsubscribe()

            Then("The list of class watchers is 0")
            zoom.classObservableCount shouldBe 0

            And("The list of subscribers is empty")
            checkClassObservables(List.empty, zoom.classObservables)
        }

        scenario("Triggered object watchers") {
            Given("One bridge")
            val bridge = createPojoBridge()
            storage.create(bridge)

            And("An observer")
            val observer1 = new TestObserver[PojoBridge]
                                with AwaitableObserver[PojoBridge]

            And("A storage observable")
            val obs1 = storage.observable(classOf[PojoBridge], bridge.id)

            When("The observer subscribes to the observable")
            obs1.subscribe(observer1)

            Then("We receive the bridge observable")
            observer1.awaitOnNext(1, timeout)

            And("The number of object watcher triggered is 1")
            getMetricValue("ObjectWatchersTriggered") shouldBe 1

            When("We update the bridge")
            bridge.name = "toto"
            storage.update(bridge)

            Then("We receive the updated bridge")
            observer1.awaitOnNext(2, timeout)

            And("The number of object watcher triggered is 2")
            getMetricValue("ObjectWatchersTriggered") shouldBe 2

            And("When we delete thr bridge")
            storage.delete(classOf[PojoBridge], bridge.id)
            observer1.awaitCompletion(timeout)

            Then("The number of object watcher triggered is 3")
            getMetricValue("ObjectWatchersTriggered") shouldBe 3

            When("We create a router")
            val router = createPojoRouter()
            storage.create(router)

            And("We subscribe to it")
            val zoom = storage.asInstanceOf[ZookeeperObjectMapper]
            val observer2 = new TestObserver[PojoRouter]
                                with AwaitableObserver[PojoRouter]
            val obs2 = zoom.observable(classOf[PojoRouter], router.id)
            obs2.subscribe(observer2)

            Then("We receive the router as a notification")
            observer2.awaitOnNext(1, timeout)

            And("The number of object watcher triggered is 4")
            getMetricValue("ObjectWatchersTriggered") shouldBe 4

            When("We update the router")
            router.name = "toto"
            storage.update(router)

            Then("We receive the updated router")
            observer2.awaitOnNext(2, timeout)

            And("The number of object watcher triggered is 5")
            getMetricValue("ObjectWatchersTriggered") shouldBe 5

            When("We delete the router")
            storage.delete(classOf[PojoRouter], router.id)
            observer2.awaitCompletion(timeout)

            Then("The number of object watcher triggered is 6")
            getMetricValue("ObjectWatchersTriggered") shouldBe 6
        }

        scenario("Triggered class watchers") {
            Given("One bridge")
            val bridge1 = createPojoBridge()
            storage.create(bridge1)

            And("An observer on bridges")
            val observer1 = new TestObserver[Observable[PojoBridge]]
                                with AwaitableObserver[Observable[PojoBridge]]

            And("A bridge observable")
            val obs1 = storage.observable(classOf[PojoBridge])

            When("The observer subscribes to the observable")
            obs1.subscribe(observer1)

            Then("We receive the bridge observable")
            observer1.awaitOnNext(1, timeout)
            val bridge1Obs = observer1.getOnNextEvents.get(0)
            val bridge1Observer = new TestObserver[PojoBridge]
                                      with AwaitableObserver[PojoBridge]
            bridge1Obs.subscribe(bridge1Observer)
            bridge1Observer.awaitOnNext(1, timeout)

            And("The number of class watchers triggered is 1")
            getMetricValue("TypeWatchersTriggered") shouldBe 1

            When("We update the bridge")
            bridge1.name = "toto"
            storage.update(bridge1)

            Then("We receive the updated bridge")
            bridge1Observer.awaitOnNext(2, timeout)

            And("The number of object watchers triggered is 2")
            getMetricValue("ObjectWatchersTriggered") shouldBe 2

            When("We add a bridge")
            val bridge2 = createPojoBridge()
            storage.create(bridge2)

            Then("We receive the new bridge observable")
            observer1.awaitOnNext(2, timeout)

            And("The number of class watchers triggered is 2")
            getMetricValue("TypeWatchersTriggered") shouldBe 2

            When("We delete the 1st bridge")
            storage.delete(classOf[PojoBridge], bridge1.id)

            Then("We get notified")
            bridge1Observer.awaitCompletion(timeout)

            And("The number of class watchers triggered is 3")
            getMetricValue("TypeWatchersTriggered") shouldBe 3

            When("We create a router")
            val router = createPojoRouter()
            storage.create(router)
            val observer2 = new TestObserver[Observable[PojoRouter]]
                                with AwaitableObserver[Observable[PojoRouter]]

            And("We subscribe to routers")
            val obs2 = storage.observable(classOf[PojoRouter])
            obs2.subscribe(observer2)

            Then("We receive the router observable")
            observer2.awaitOnNext(1, timeout) shouldBe true

            And("The number of class watchers triggered is 4")
            getMetricValue("TypeWatchersTriggered") shouldBe 4
        }
    }

    private def getMetricValue(suffix: String): Long = {
        val metricFilter = new MetricFilter {
            override def matches(name: String, metric: Metric): Boolean =
                name.contains(suffix)
        }
        val gauges = registry.getGauges(metricFilter)
        gauges.get(gauges.firstKey).getValue.asInstanceOf[Long]
    }

    private def checkObjectObservables(expectedObservables: List[(String, Int)],
                                       zoomObservables: String): Unit = {
        zoomObservables.count(_.equals(':')) shouldBe expectedObservables.size
        for ((clazz, count) <- expectedObservables) {
            zoomObservables.contains(clazz + ":" + count) shouldBe true
        }
    }

    private def checkClassObservables(expectedObservables: List[String],
                                      zoomObservables: String): Unit = {
        zoomObservables.count(_.equals('\n')) shouldBe expectedObservables.size
        expectedObservables.foreach(clazz => {
            zoomObservables.contains(clazz) shouldBe true
        })
    }
}
