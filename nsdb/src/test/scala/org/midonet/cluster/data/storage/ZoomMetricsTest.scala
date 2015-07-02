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

import org.midonet.cluster.data.storage.FieldBinding.DeleteAction._
import org.midonet.cluster.data.storage.KeyType._
import org.midonet.cluster.data.storage.StorageTest._
import org.midonet.cluster.data.storage.StorageTestClasses._
import org.midonet.cluster.models.Topology._
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
        zoom = new ZookeeperObjectMapper(ZK_ROOT, curator, registry)
        initAndBuildStorage(zoom)
        assert = () => {}
    }

    private def initAndBuildStorage(storage: ZookeeperObjectMapper): Unit = {
        List(classOf[PojoBridge], classOf[PojoRouter], classOf[PojoPort],
             classOf[PojoChain], classOf[PojoRule], classOf[Network],
             classOf[Router], classOf[Port], classOf[Chain],
             classOf[Rule]).foreach {
                 clazz => storage.registerClass(clazz)
             }

        storage.declareBinding(classOf[PojoBridge], "inChainId", CLEAR,
                               classOf[PojoChain], "bridgeIds", CLEAR)
        storage.declareBinding(classOf[PojoBridge], "outChainId", CLEAR,
                               classOf[PojoChain], "bridgeIds", CLEAR)

        storage.declareBinding(classOf[PojoRouter], "inChainId", CLEAR,
                               classOf[PojoChain], "routerIds", CLEAR)
        storage.declareBinding(classOf[PojoRouter], "outChainId", CLEAR,
                               classOf[PojoChain], "routerIds", CLEAR)

        storage.declareBinding(classOf[PojoPort], "bridgeId", CLEAR,
                               classOf[PojoBridge], "portIds", ERROR)
        storage.declareBinding(classOf[PojoPort], "routerId", CLEAR,
                               classOf[PojoRouter], "portIds", ERROR)
        storage.declareBinding(classOf[PojoPort], "inChainId", CLEAR,
                               classOf[PojoChain], "portIds", CLEAR)
        storage.declareBinding(classOf[PojoPort], "outChainId", CLEAR,
                               classOf[PojoChain], "portIds", CLEAR)
        storage.declareBinding(classOf[PojoPort], "peerId", CLEAR,
                               classOf[PojoPort], "peerId", CLEAR)

        storage.declareBinding(classOf[PojoChain], "ruleIds", CASCADE,
                               classOf[PojoRule], "chainId", CLEAR)

        storage.declareBinding(classOf[PojoRule], "portIds", CLEAR,
                               classOf[PojoPort], "ruleIds", CLEAR)

        storage.declareBinding(classOf[Network], "inbound_filter_id", CLEAR,
                               classOf[Chain], "network_ids", CLEAR)
        storage.declareBinding(classOf[Network], "outbound_filter_id", CLEAR,
                               classOf[Chain], "network_ids", CLEAR)

        storage.declareBinding(classOf[Router], "inbound_filter_id", CLEAR,
                               classOf[Chain], "router_ids", CLEAR)
        storage.declareBinding(classOf[Router], "outbound_filter_id", CLEAR,
                               classOf[Chain], "router_ids", CLEAR)

        storage.declareBinding(classOf[Port], "network_id", CLEAR,
                               classOf[Network], "port_ids", ERROR)
        storage.declareBinding(classOf[Port], "router_id", CLEAR,
                               classOf[Router], "port_ids", ERROR)
        storage.declareBinding(classOf[Port], "inbound_filter_id", CLEAR,
                               classOf[Chain], "port_ids", CLEAR)
        storage.declareBinding(classOf[Port], "outbound_filter_id", CLEAR,
                               classOf[Chain], "port_ids", CLEAR)
        storage.declareBinding(classOf[Port], "peer_id", CLEAR,
                               classOf[Port], "peer_id", CLEAR)

        storage.declareBinding(classOf[Chain], "rule_ids", CASCADE,
                               classOf[Rule], "chain_id", CLEAR)

        storage.declareBinding(classOf[Rule], "in_port_ids", CLEAR,
                               classOf[Port], "inbound_filter_id", CLEAR)
        storage.declareBinding(classOf[Rule], "out_port_ids", CLEAR,
                               classOf[Port], "outbound_filter_id", CLEAR)

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
            zoom.objectObservableCountPerClass should contain theSameElementsAs
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
            zoom.objectObservableCountPerClass should contain theSameElementsAs
                Map(classOf[PojoBridge] -> 2)

            When("A 2nd subscriber subscribes to the 1st bridge")
            val observer3 = new TestAwaitableObserver[PojoBridge]
            val sub3 = obs1.subscribe(observer3)

            Then("The number of watchers is 2")
            zoom.objectObservableCount shouldBe 2

            And("The list of object observers is correct")
            zoom.objectObservableCountPerClass should contain theSameElementsAs
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
            zoom.objectObservableCountPerClass should contain theSameElementsAs
                Map(classOf[PojoBridge] -> 2, classOf[PojoRouter] -> 1)

            And("When the 1st observer unsubscribes from the 1st bridge")
            sub1.unsubscribe()

            Then("The number of watchers returned by zoom is 3")
            zoom.objectObservableCount shouldBe 3

            And("The list of object observers is correct")
            zoom.objectObservableCountPerClass should contain theSameElementsAs
                Map(classOf[PojoBridge] -> 2, classOf[PojoRouter] -> 1)

            And("When the 2nd observer unsubscribes from the 2nd bridge")
            sub2.unsubscribe()

            Then("The number of watchers returned by zoom is 2")
            zoom.objectObservableCount shouldBe 2

            And("The list of object observers is correct")
            zoom.objectObservableCountPerClass should contain theSameElementsAs
                Map(classOf[PojoBridge] -> 1, classOf[PojoRouter] -> 1)

            And("When the 3rd observer unsubscribes from the 1st bridge")
            sub3.unsubscribe()

            Then("The number of watchers returned by zoom is 1")
            zoom.objectObservableCount shouldBe 1

            And("The list of object observers is correct")
            zoom.objectObservableCountPerClass should contain theSameElementsAs
                Map(classOf[PojoRouter] -> 1)

            When("The 4th subscriber unsubscribes from the router")
            sub4.unsubscribe()

            Then("The number of watchers returned by zoom is 0")
            zoom.objectObservableCount shouldBe 0

            And("The list of object observers is empty")
            zoom.objectObservableCountPerClass shouldBe Map.empty
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
            zoom.classObservableSet should contain theSameElementsAs
                Set(classOf[PojoBridge])

            When("A 2nd observer subscribes to the bridge")
            val observer2 = new TestAwaitableObserver[Observable[PojoBridge]]
            val sub2 = obs1.subscribe(observer2)

            Then("The number of class watchers is still 1")
            zoom.classObservableCount shouldBe 1

            And("The list of class observers is correct")
            zoom.classObservableSet should contain theSameElementsAs
                Set(classOf[PojoBridge])

            When("We create a 2nd bridge")
            val bridge2 = createPojoBridge()
            zoom.create(bridge2)

            Then("We get notified of the 2nd bridge observable")
            observer1.awaitOnNext(2, timeout) shouldBe true

            And("The number of class watchers is still 1")
            zoom.classObservableCount shouldBe 1

            And("The list of class observers is correct")
            zoom.classObservableSet should contain theSameElementsAs
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
            zoom.classObservableSet should contain theSameElementsAs
                Set(classOf[PojoBridge], classOf[PojoRouter])

            And("When the 3 subscriber unsubscribes")
            sub3.unsubscribe()

            Then("The number of class watchers is 1")
            zoom.classObservableCount shouldBe 1

            And("The list of class observers is correct")
            zoom.classObservableSet should contain theSameElementsAs
                Set(classOf[PojoBridge])

            And("When the two remaining subscribers unsubscribe")
            sub1.unsubscribe()
            sub2.unsubscribe()

            Then("The list of class watchers is 0")
            zoom.classObservableCount shouldBe 0

            And("The list of subscribers is empty")
            zoom.classObservableSet shouldBe Set.empty
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
            intercept[NotFoundException] {
                Await.result(zoom.get(classOf[PojoBridge], UUID.randomUUID()),
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

            // TODO: No node test for object observable
        }

        // TODO: add test for premature closing of node observables
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

            When("We delete the object associated to the single value")
            zoom.delete(classOf[State], obj.id)

            Then("Both observers complete")
            observer2.awaitCompletion(timeout)
            observer3.awaitCompletion(timeout)

            And("The number of object watcher triggered should be 4")
            getMetricValue("ObjectWatchersTriggered") shouldBe 4
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

            When("We delete the object associated to the multi-value key")
            zoom.delete(classOf[State], obj.id)

            Then("The observer completes")
            observer1.awaitCompletion(timeout)

            And("The number of class watchers triggered should be 3")
            getMetricValue("TypeWatchersTriggered") shouldBe 3
        }

        // TODO: Add more tests triggering NoNode exceptions (keyObservable)
        scenario("Zoom state NoNode/NodeExists exceptions") {
            Given("A request for a single-value key that doesn't exist")
            val nonExistingId = UUID.randomUUID()
            zoom.getKey(classOf[State], nonExistingId, "single").await(timeout)

            Then("The number of ZK NoNode exceptions should be 1")
            getMetricValue("ZKNoNodeExceptionCount") shouldBe 1

            When("We request a multi-value key that doesn't exist")
            zoom.getKey(classOf[State], nonExistingId, "multi").await(timeout)

            Then("The number of ZK NoNode exceptions should be 2")
            getMetricValue("ZKNoNodeExceptionCount") shouldBe 2
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
