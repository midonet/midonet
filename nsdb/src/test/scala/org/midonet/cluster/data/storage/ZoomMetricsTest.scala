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

package org.midonet.cluster.data.storage

import java.util.UUID

import scala.concurrent.Await
import scala.concurrent.duration._

import com.codahale.metrics.{Metric, MetricFilter, MetricRegistry}
import org.apache.zookeeper.ZooKeeper.States
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FeatureSpec, GivenWhenThen, Matchers}
import rx.Observable

import org.midonet.cluster.data.storage.KeyType._
import org.midonet.cluster.data.storage.StorageTest._
import org.midonet.cluster.data.storage.StorageTestClasses._
import org.midonet.cluster.util.CuratorTestFramework
import org.midonet.util.reactivex.{TestAwaitableObserver, richObservable}

@RunWith(classOf[JUnitRunner])
class ZoomMetricsTest extends FeatureSpec
                      with BeforeAndAfter
                      with Matchers
                      with CuratorTestFramework
                      with GivenWhenThen {

    private val timeout = 5 seconds
    private var registry: MetricRegistry = _
    private var zoom: ZookeeperObjectMapper = _
    private var assert: () => Unit = _

    protected override def setup(): Unit = {
        registry = new MetricRegistry()
        zoom = new ZookeeperObjectMapper(config, "zoom", UUID.randomUUID().toString,
                                         curator, registry)
        initAndBuildStorage(zoom)
        assert = () => {}
    }

    private def initAndBuildStorage(storage: ZookeeperObjectMapper): Unit = {
        List(classOf[PojoBridge], classOf[PojoRouter]).foreach {
            clazz => storage.registerClass(clazz)
        }

        storage.registerClass(classOf[State])
        storage.registerKey(classOf[State], "single", SingleLastWriteWins)
        storage.registerKey(classOf[State], "multi", Multiple)

        storage.build()
    }

    feature("Zoom storage Metrics") {
        scenario("ZK connection state") {
            zoom.zkConnectionState shouldBe States.CONNECTED.name

            curator.getZookeeperClient.getZooKeeper.close()
            zoom.zkConnectionState shouldBe States.CLOSED.name
        }

        scenario("Object watchers") {
            Given("One bridge")
            val bridge1 = createPojoBridge()
            zoom.create(bridge1)

            And("An observer")
            val observer1 = new TestAwaitableObserver[PojoBridge]

            And("A storage observable")
            val obs1 = zoom.observable(classOf[PojoBridge], bridge1.id)

            When("The observer subscribes to the observable")
            val sub1 = obs1.subscribe(observer1)
            Then("We receive the bridge")
            observer1.awaitOnNext(1, timeout)

            Then("The number of watchers returned by zoom is 1")
            zoom.objectObservableCount shouldBe 1

            And("The list of object observers is correct")
            zoom.objectObservableCounters should contain theSameElementsAs
                Map(classOf[PojoBridge] -> 1)

            When("We create a 2nd bridge")
            val bridge2 = createPojoBridge()
            zoom.create(bridge2)

            And("We subscribe to it")
            val observer2 = new TestAwaitableObserver[PojoBridge]
            val obs2 = zoom.observable(classOf[PojoBridge], bridge2.id)
            val sub2 = obs2.subscribe(observer2)

            Then("The number of watchers returned by zoom is 2")
            zoom.objectObservableCount shouldBe 2

            And("The list of object observers is correct")
            zoom.objectObservableCounters should contain theSameElementsAs
                Map(classOf[PojoBridge] -> 2)

            When("A 2nd subscriber subscribes to the 1st bridge")
            val observer3 = new TestAwaitableObserver[PojoBridge]
            val sub3 = obs1.subscribe(observer3)

            Then("The number of watchers is 2")
            zoom.objectObservableCount shouldBe 2

            And("The list of object observers is correct")
            zoom.objectObservableCounters should contain theSameElementsAs
                Map(classOf[PojoBridge] -> 2)

            When("We create a router")
            val router = createPojoRouter()
            zoom.create(router)

            And("We subscribe to it")
            val observer4 = new TestAwaitableObserver[PojoRouter]
            val obs3 = zoom.observable(classOf[PojoRouter], router.id)
            val sub4 = obs3.subscribe(observer4)

            Then("The number of watchers returned by zoom is 3")
            zoom.objectObservableCount shouldBe 3

            And("The list of object observers is correct")
            zoom.objectObservableCounters should contain theSameElementsAs
                Map(classOf[PojoBridge] -> 2, classOf[PojoRouter] -> 1)

            And("When the 1st observer unsubscribes from the 1st bridge")
            sub1.unsubscribe()

            Then("The number of watchers returned by zoom is 3")
            zoom.objectObservableCount shouldBe 3

            And("The list of object observers is correct")
            zoom.objectObservableCounters should contain theSameElementsAs
                Map(classOf[PojoBridge] -> 2, classOf[PojoRouter] -> 1)

            And("When the 2nd observer unsubscribes from the 2nd bridge")
            sub2.unsubscribe()

            Then("The number of watchers returned by zoom is 2")
            zoom.objectObservableCount shouldBe 2

            And("The list of object observers is correct")
            zoom.objectObservableCounters should contain theSameElementsAs
                Map(classOf[PojoBridge] -> 1, classOf[PojoRouter] -> 1)

            And("When the 3rd observer unsubscribes from the 1st bridge")
            sub3.unsubscribe()

            Then("The number of watchers returned by zoom is 1")
            zoom.objectObservableCount shouldBe 1

            And("The list of object observers is correct")
            zoom.objectObservableCounters should contain theSameElementsAs
                Map(classOf[PojoRouter] -> 1)

            When("The 4th subscriber unsubscribes from the router")
            sub4.unsubscribe()

            Then("The number of watchers returned by zoom is 0")
            zoom.objectObservableCount shouldBe 0

            And("The list of object observers is empty")
            zoom.objectObservableCounters shouldBe Map.empty
        }

        scenario("Class watchers") {
            Given("One bridge")
            val bridge1 = createPojoBridge()
            zoom.create(bridge1)

            And("An observer")
            val observer1 = new TestAwaitableObserver[Observable[PojoBridge]]

            And("A storage observable")
            val obs1 = zoom.observable(classOf[PojoBridge])

            When("The observer subscribes to the observable")
            val sub1 = obs1.subscribe(observer1)
            Then("We receive the bridge observable")
            observer1.awaitOnNext(1, timeout)

            Then("The number of class watchers returned by zoom is 1")
            zoom.classObservableCount shouldBe 1

            And("The list of class observers is correct")
            zoom.existingClassObservables should contain theSameElementsAs
                Set(classOf[PojoBridge])

            When("A 2nd observer subscribes to the bridge")
            val observer2 = new TestAwaitableObserver[Observable[PojoBridge]]
            val sub2 = obs1.subscribe(observer2)

            Then("The number of class watchers is still 1")
            zoom.classObservableCount shouldBe 1

            And("The list of class observers is correct")
            zoom.existingClassObservables should contain theSameElementsAs
                Set(classOf[PojoBridge])

            When("We create a 2nd bridge")
            val bridge2 = createPojoBridge()
            zoom.create(bridge2)

            Then("We get notified of the 2nd bridge observable")
            observer1.awaitOnNext(2, timeout) shouldBe true

            And("The number of class watchers is still 1")
            zoom.classObservableCount shouldBe 1

            And("The list of class observers is correct")
            zoom.existingClassObservables should contain theSameElementsAs
                Set(classOf[PojoBridge])

            When("We create a router")
            val router = createPojoRouter()
            zoom.create(router)

            And("We subscribe to a router observable")
            val observer3 = new TestAwaitableObserver[Observable[PojoRouter]]
            val obs2 = zoom.observable(classOf[PojoRouter])
            val sub3 = obs2.subscribe(observer3)

            Then("We get nofitied of the router observable")
            observer3.awaitOnNext(1, timeout) shouldBe true

            And("The number of class watchers is 2")
            zoom.classObservableCount shouldBe 2

            And("The list of class observers is correct")
            zoom.existingClassObservables should contain theSameElementsAs
                Set(classOf[PojoBridge], classOf[PojoRouter])

            And("When the 3 subscriber unsubscribes")
            sub3.unsubscribe()

            Then("The number of class watchers is 1")
            zoom.classObservableCount shouldBe 1

            And("The list of class observers is correct")
            zoom.existingClassObservables should contain theSameElementsAs
                Set(classOf[PojoBridge])

            And("When the two remaining subscribers unsubscribe")
            sub1.unsubscribe()
            sub2.unsubscribe()

            Then("The list of class watchers is 0")
            zoom.classObservableCount shouldBe 0

            And("The list of subscribers is empty")
            zoom.existingClassObservables shouldBe Set.empty
        }

        scenario("Triggered object watchers") {
            Given("One bridge")
            val bridge = createPojoBridge()
            zoom.create(bridge)

            And("An observer")
            val observer1 = new TestAwaitableObserver[PojoBridge]

            And("A storage observable")
            val obs1 = zoom.observable(classOf[PojoBridge], bridge.id)

            When("The observer subscribes to the observable")
            obs1.subscribe(observer1)

            Then("We receive the bridge observable")
            observer1.awaitOnNext(1, timeout)

            And("The number of object watcher triggered is 0")
            getMetricValue("ObjectWatchersTriggered") shouldBe 0

            When("We update the bridge")
            bridge.name = "toto"
            zoom.update(bridge)

            Then("We receive the updated bridge")
            observer1.awaitOnNext(2, timeout)

            And("The number of object watcher triggered is 1")
            getMetricValue("ObjectWatchersTriggered") shouldBe 1

            And("When we delete the bridge")
            zoom.delete(classOf[PojoBridge], bridge.id)
            observer1.awaitCompletion(timeout)

            Then("The number of object watcher triggered is 2")
            getMetricValue("ObjectWatchersTriggered") shouldBe 2

            When("We create a router")
            val router = createPojoRouter()
            zoom.create(router)

            And("We subscribe to it")
            val observer2 = new TestAwaitableObserver[PojoRouter]
            val obs2 = zoom.observable(classOf[PojoRouter], router.id)
            obs2.subscribe(observer2)

            Then("We receive the router as a notification")
            observer2.awaitOnNext(1, timeout)

            And("The number of object watcher triggered is 2")
            getMetricValue("ObjectWatchersTriggered") shouldBe 2

            When("We update the router")
            router.name = "toto"
            zoom.update(router)

            Then("We receive the updated router")
            observer2.awaitOnNext(2, timeout)

            And("The number of object watcher triggered is 3")
            getMetricValue("ObjectWatchersTriggered") shouldBe 3

            When("We delete the router")
            zoom.delete(classOf[PojoRouter], router.id)
            observer2.awaitCompletion(timeout)

            Then("The number of object watcher triggered is 4")
            getMetricValue("ObjectWatchersTriggered") shouldBe 4
        }

        scenario("Object observable with unsubscribe and multiple observers") {
            Given("One bridge")
            val bridge1 = createPojoBridge()
            zoom.create(bridge1)

            And("An observer")
            val observer1 = new TestAwaitableObserver[PojoBridge]

            When("We subscribe to the bridge's observable")
            val obs = zoom.observable(classOf[PojoBridge], bridge1.id)
            val sub1 = obs.subscribe(observer1)

            Then("We receive a notification")
            observer1.awaitOnNext(1, timeout) shouldBe true
            getMetricValue("ObjectWatchersTriggered") shouldBe 0

            When("We unsubscribe from the bridge's observable")
            sub1.unsubscribe()

            And("Two new observers subscribe to the same bridge")
            val observer2 = new TestAwaitableObserver[PojoBridge]
            val observer3 = new TestAwaitableObserver[PojoBridge]

            obs.subscribe(observer2)
            obs.subscribe(observer3)

            Then("Both observers get notified")
            observer2.awaitOnNext(1, timeout) shouldBe true
            observer3.awaitOnNext(1, timeout) shouldBe true

            And("The number of object watchers triggered is 0")
            getMetricValue("ObjectWatchersTriggered") shouldBe 0

            When("We update the bridge")
            bridge1.name = "toto"
            zoom.update(bridge1)

            Then("Both observers are notified")
            observer2.awaitOnNext(2, timeout) shouldBe true
            observer3.awaitOnNext(2, timeout) shouldBe true

            And("The number of object watchers triggered is 1")
            getMetricValue("ObjectWatchersTriggered") shouldBe 1
        }

        scenario("Triggered class watchers") {
            Given("One bridge")
            val bridge1 = createPojoBridge()
            zoom.create(bridge1)

            And("An observer on bridges")
            val observer1 = new TestAwaitableObserver[Observable[PojoBridge]]

            And("A bridge observable")
            val obs1 = zoom.observable(classOf[PojoBridge])

            When("The observer subscribes to the observable")
            obs1.subscribe(observer1)

            Then("We receive the bridge observable")
            observer1.awaitOnNext(1, timeout)
            val bridge1Obs = observer1.getOnNextEvents.get(0)
            val bridge1Observer = new TestAwaitableObserver[PojoBridge]
            bridge1Obs.subscribe(bridge1Observer)
            bridge1Observer.awaitOnNext(1, timeout)

            And("The number of class watchers triggered is 1")
            getMetricValue("TypeWatchersTriggered") shouldBe 1

            When("We update the bridge")
            bridge1.name = "toto"
            zoom.update(bridge1)

            Then("We receive the updated bridge")
            bridge1Observer.awaitOnNext(2, timeout)

            And("The number of object watchers triggered is 1")
            getMetricValue("ObjectWatchersTriggered") shouldBe 1

            And("The number of class watchers triggered is still 1")
            getMetricValue("TypeWatchersTriggered") shouldBe 1

            When("We add a bridge")
            val bridge2 = createPojoBridge()
            zoom.create(bridge2)

            Then("We receive the new bridge observable")
            observer1.awaitOnNext(2, timeout)

            And("The number of class watchers triggered is 2")
            getMetricValue("TypeWatchersTriggered") shouldBe 2

            When("We delete the 1st bridge")
            zoom.delete(classOf[PojoBridge], bridge1.id)

            Then("We get notified")
            bridge1Observer.awaitCompletion(timeout)

            And("The number of object watchers triggered is 2")
            getMetricValue("ObjectWatchersTriggered") shouldBe 2

            And("The number of object watchers triggered is 3")
            getMetricValue("TypeWatchersTriggered") shouldBe 3

            When("We create a router")
            val router = createPojoRouter()
            zoom.create(router)
            val observer2 = new TestAwaitableObserver[Observable[PojoRouter]]

            And("We subscribe to routers")
            val obs2 = zoom.observable(classOf[PojoRouter])
            obs2.subscribe(observer2)

            Then("We receive the router observable")
            observer2.awaitOnNext(1, timeout) shouldBe true

            And("The number of class watchers triggered is 4")
            getMetricValue("TypeWatchersTriggered") shouldBe 4
        }

        scenario("Zoom NoNode/NodeExists exceptions") {
            Given("A request for a non-existing object")
            val nonExistingId = UUID.randomUUID()
            intercept[NotFoundException] {
                Await.result(zoom.get(classOf[PojoBridge], nonExistingId),
                             timeout)
            }

            Then("The number of ZK NoNode exceptions should be 1")
            getMetricValue("ZKNoNodeExceptionCount") shouldBe 1

            When("We attempt to store the same object twice in Zoom")
            val bridge = createPojoBridge()
            zoom.create(bridge)
            intercept[ObjectExistsException] {
               zoom.create(bridge)
            }

            Then("The number of ZK NodeExists exception should be 1")
            getMetricValue("ZKNodeExistsExceptionCount") shouldBe 1
            getMetricValue("ZKNoNodeExceptionCount") shouldBe 1

            When("We create an observable for a non-existing object")
            val obs = zoom.observable(classOf[PojoBridge], nonExistingId)
            val observer = new TestAwaitableObserver[PojoBridge]

            And("We subscribe to it")
            obs.subscribe(observer)

            Then("The observer completes")
            observer.awaitCompletion(timeout)

            And("The number of ZK NoNode exceptions should be 2")
            getMetricValue("ZKNoNodeExceptionCount") shouldBe 2

            When("We attempt to delete a non-existing object")
            intercept[NotFoundException] {
                zoom.delete(classOf[PojoBridge], nonExistingId)
            }

            Then("The number of ZK NoNode exceptions should be 3")
            getMetricValue("ZKNoNodeExceptionCount") shouldBe 3

            When("We attempt to update a non-existing object")
            intercept[NotFoundException] {
                zoom.update(createPojoBridge())
            }

            Then("The number of ZK NoNode exceptions should be 4")
            getMetricValue("ZKNoNodeExceptionCount") shouldBe 4
        }

        scenario("Zoom Observable premature close") {
            Given("One bridge")
            val bridge = createPojoBridge()
            zoom.create(bridge)

            When("We subscribe to the bridge's observable")
            val obs = zoom.observable(classOf[PojoBridge], bridge.id)
            val observer1 = new TestAwaitableObserver[PojoBridge]
            val sub1 = obs.subscribe(observer1)

            Then("We get notified")
            observer1.awaitOnNext(1, timeout) shouldBe true

            And("The number of premature observable close should be 0")
            getMetricValue("ObservablePrematureCloseCount") shouldBe 0

            When("We unsubscribe")
            sub1.unsubscribe()

            And("We 'get' the bridge")
            Await.result(zoom.get(classOf[PojoBridge], bridge.id), timeout)

            Then("The number of observable premature close should be 0")
            getMetricValue("ObservablePrematureCloseCount") shouldBe 0

            And("Two new observers subscribe to the bridge's observable")
            val observer2 = new TestAwaitableObserver[PojoBridge]
            val observer3 = new TestAwaitableObserver[PojoBridge]
            obs.subscribe(observer2)
            obs.subscribe(observer3)

            Then("The 2nd and 3rd observers get notified")
            observer2.awaitOnNext(1, timeout) shouldBe true
            observer3.awaitOnNext(1, timeout) shouldBe true

            And("The number of observable premature close should be 2")
            getMetricValue("ObservablePrematureCloseCount") shouldBe 2
        }
    }

    feature("Zoom state storage metrics") {
        scenario("Single-value key observable") {
            Given("An object")
            val obj = new State
            zoom.create(obj)

            When("We add a single value key")
            val key = "single"
            zoom.addValue(classOf[State], obj.id, key, value = "1")
                .await(timeout)

            And("We subscribe to it")
            val observer1 = new TestAwaitableObserver[StateKey]
            val obs = zoom.keyObservable(classOf[State], obj.id, key)
            val sub1 = obs.subscribe(observer1)

            Then("We get notified")
            observer1.awaitOnNext(1, timeout) shouldBe true

            And("The number of object watchers triggered should be 0")
            getMetricValue("ObjectWatchersTriggered") shouldBe 0

            When("We modify the single value key")
            zoom.addValue(classOf[State], obj.id, key, value = "2")
                .await(timeout)

            Then("We get notified")
            observer1.awaitOnNext(2, timeout) shouldBe true

            And("The number of object watchers triggered should be 1")
            getMetricValue("ObjectWatchersTriggered") shouldBe 1

            When("A 2nd observer subscribes to the single value")
            val observer2 = new TestAwaitableObserver[StateKey]
            obs.subscribe(observer2)
            observer2.awaitOnNext(1, timeout) shouldBe true

            And("We update the single value a 2nd time")
            zoom.addValue(classOf[State], obj.id, key, value = "3")
                .await(timeout)

            Then("Both observers get notified")
            observer1.awaitOnNext(3, timeout) shouldBe true
            observer2.awaitOnNext(2, timeout) shouldBe true

            And("The number of object watchers triggered should be 2")
            getMetricValue("ObjectWatchersTriggered") shouldBe 2

            When("The 1st observer unsubscribes")
            sub1.unsubscribe()

            And("A 3rd observer subscribes")
            val observer3 = new TestAwaitableObserver[StateKey]
            obs.subscribe(observer3)
            observer3.awaitOnNext(1, timeout) shouldBe true

            And("We modify the single value key")
            zoom.addValue(classOf[State], obj.id, key, value = "4")
                .await(timeout)

            Then("The 2nd and 3rd observers get notified")
            observer2.awaitOnNext(3, timeout) shouldBe true
            observer3.awaitOnNext(2, timeout) shouldBe true

            And("The number of object watchers triggered should be 3")
            getMetricValue("ObjectWatchersTriggered") shouldBe 3
        }

        scenario("Multi-value key observable") {
            Given("An object")
            val obj = new State
            zoom.create(obj)

            When("We add a multi-value key")
            val key = "multi"
            zoom.addValue(classOf[State], obj.id, key, value = "1")
                .await(timeout)

            And("We subscribe to it")
            val observer1 = new TestAwaitableObserver[StateKey]
            val obs = zoom.keyObservable(classOf[State], obj.id, key)
            obs.subscribe(observer1)

            Then("We get notified")
            observer1.awaitOnNext(1, timeout) shouldBe true

            And("The number of class watchers triggered should be 0")
            getMetricValue("TypeWatchersTriggered") shouldBe 0

            When("We add a 2nd value")
            zoom.addValue(classOf[State], obj.id, key, value = "2")
                .await(timeout)

            Then("We get notified")
            observer1.awaitOnNext(2, timeout) shouldBe true

            And("The number of class watchers triggered should be 1")
            getMetricValue("TypeWatchersTriggered") shouldBe 1

            When("We delete the 1st value")
            zoom.removeValue(classOf[State], obj.id, key, value = "1")
                .await(timeout)

            Then("We get notified")
            observer1.awaitOnNext(3, timeout) shouldBe true

            And("The number of class watchers triggered should be 2")
            getMetricValue("TypeWatchersTriggered") shouldBe 2
        }

        scenario("Zoom state NoNode exceptions") {
            Given("A request for a single-value key that doesn't exist")
            val nonExistingId = UUID.randomUUID()
            zoom.getKey(classOf[State], nonExistingId, "single").await(timeout)

            Then("The number of ZK NoNode exceptions should be 1")
            getMetricValue("ZKNoNodeExceptionCount") shouldBe 1

            When("We request a multi-value key that doesn't exist")
            zoom.getKey(classOf[State], nonExistingId, "multi").await(timeout)

            Then("The number of ZK NoNode exceptions should be 2")
            getMetricValue("ZKNoNodeExceptionCount") shouldBe 2

            When("We attempt to remove a non-existing single value")
            zoom.removeValue(classOf[State], nonExistingId, "single", "1")
                .await(timeout)

            Then("The number of ZK NoNode exceptions should be 3")
            getMetricValue("ZKNoNodeExceptionCount") shouldBe 3

            When("We attempt to remove a non-existing multi-value")
            zoom.removeValue(classOf[State], nonExistingId, "multi", "2")
                .await(timeout)

            Then("The number of ZK NoNode exceptions should be 4")
            getMetricValue("ZKNoNodeExceptionCount") shouldBe 4
        }

        scenario("Zoom state observable premature close") {
            Given("An object")
            val obj = new State
            zoom.create(obj)

            When("We add a single-value key")
            var key = "single"
            zoom.addValue(classOf[State], obj.id, key, value="1")

            And("We subscribe to it")
            var observer1 = new TestAwaitableObserver[StateKey]
            var obs = zoom.keyObservable(classOf[State], obj.id, key)
            var sub1 = obs.subscribe(observer1)

            Then("We get notified")
            observer1.awaitOnNext(1, timeout) shouldBe true

            And("The number of premature observable close should be 0")
            getMetricValue("ObservablePrematureCloseCount") shouldBe 0

            When("We unsubscribe to the multi-value key")
            sub1.unsubscribe()

            And("Two new observers subscribe to it")
            var observer2 = new TestAwaitableObserver[StateKey]
            var observer3 = new TestAwaitableObserver[StateKey]
            obs.subscribe(observer2)
            obs.subscribe(observer3)

            Then("The two observers get notified")
            observer2.awaitOnNext(1, timeout) shouldBe true
            observer3.awaitOnNext(1, timeout) shouldBe true

            And("The number of observable premature close should be 2")
            getMetricValue("ObservablePrematureCloseCount") shouldBe 2

            When("We add multi-value key")
            key = "multi"
            zoom.addValue(classOf[State], obj.id, key, value = "2")
                .await(timeout)

            And("We subscribe to it")
            observer1 = new TestAwaitableObserver[StateKey]
            obs = zoom.keyObservable(classOf[State], obj.id, key)
            sub1 = obs.subscribe(observer1)

            Then("We get notified")
            observer1.awaitOnNext(1, timeout) shouldBe true

            When("We unsubscribe to the multi-value key")
            sub1.unsubscribe()

            And("Two new observers subscribe to it")
            observer2 = new TestAwaitableObserver[StateKey]
            observer3 = new TestAwaitableObserver[StateKey]
            obs.subscribe(observer2)
            obs.subscribe(observer3)

            Then("The two observers get notified")
            observer2.awaitOnNext(1, timeout) shouldBe true
            observer3.awaitOnNext(1, timeout) shouldBe true

            And("The number of observable premature close should be 4")
            getMetricValue("ObservablePrematureCloseCount") shouldBe 4
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
}
