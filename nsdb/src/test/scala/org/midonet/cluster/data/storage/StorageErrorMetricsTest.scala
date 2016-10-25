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

package org.midonet.cluster.data.storage

import java.util.concurrent.atomic.AtomicInteger
import java.util.{ConcurrentModificationException, UUID}

import scala.collection.JavaConverters._
import scala.concurrent.duration._

import com.codahale.metrics.MetricRegistry

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FlatSpec, GivenWhenThen, Matchers}

import rx.Observable

import org.midonet.cluster.data.storage.FieldBinding.DeleteAction
import org.midonet.cluster.data.storage.StorageTestClasses.{PojoBridge, PojoChain, PojoPort}
import org.midonet.cluster.data.storage.metrics.StorageMetrics
import org.midonet.cluster.util.MidonetBackendTest
import org.midonet.util.functors.makeRunnable
import org.midonet.util.reactivex.TestAwaitableObserver

@RunWith(classOf[JUnitRunner])
class StorageErrorMetricsTest extends FlatSpec
                                      with MidonetBackendTest
                                      with BeforeAndAfter
                                      with Matchers
                                      with GivenWhenThen {

    private trait ZoomIntrospector {
        def publicInternalObservable[T](clazz: Class[T], id: Any): Observable[T]
    }

    private var storage: ZookeeperObjectMapper with ZoomIntrospector = _
    private val timeout = 5 seconds
    private var registry: MetricRegistry = _

    protected override def setup(): Unit = {

        registry = new MetricRegistry
        storage = new ZookeeperObjectMapper(config, UUID.randomUUID().toString,
                                            curator, curator, stateTables, reactor,
                                            new StorageMetrics(registry))
                      with ZoomIntrospector {
            override def publicInternalObservable[T](clazz: Class[T], id: Any)
            : Observable[T] = {
                internalObservable(clazz, id, version.get, () => {})
            }
        }
        storage.registerClass(classOf[PojoBridge])
        storage.registerClass(classOf[PojoChain])
        storage.registerClass(classOf[PojoPort])
        storage.declareBinding(classOf[PojoBridge], "inChainId", DeleteAction.CLEAR,
                               classOf[PojoChain], "bridgeIds", DeleteAction.ERROR)
        storage.declareBinding(classOf[PojoPort], "peerId", DeleteAction.CLEAR,
                               classOf[PojoPort], "peerId", DeleteAction.CLEAR)

        storage.build()
    }

    private def getCountForCounter(suffix: String): Long =
        registry.getCounters.asScala
            .filterKeys(_ contains suffix)
            .head._2.getCount

    "Metrics" should "record concurrent object modification exceptions" in {
        Given("A bridge")
        val bridge = new PojoBridge()
        bridge.id = UUID.randomUUID()
        storage.create(bridge)

        When("Modifying the bridge concurrently")
        val failures = new AtomicInteger()
        val threads = for (index <- 0 until 10) yield {
            new Thread(makeRunnable {
                val b = new PojoBridge()
                b.id = bridge.id
                b.name = s"bridge-$index"
                try storage.update(b)
                catch {
                    case _: ConcurrentModificationException =>
                        failures.incrementAndGet()
                }
            })
        }

        for (thread <- threads){
            thread.start()
        }

        for (thread <- threads) {
            thread.join()
        }

        Then("The concurrent modification exception counter should be incremented")
        getCountForCounter("concurrentModificationException") shouldBe failures.get
    }

    "Metrics" should "record conflict exceptions" in {
        Given("Two peered ports")
        val port1 = new PojoPort()
        port1.id = UUID.randomUUID()
        storage.create(port1)

        val port2 = new PojoPort()
        port2.id = UUID.randomUUID()
        port2.peerId = port1.id
        storage.create(port2)

        When("Peering a new port")
        val port3 = new PojoPort()
        port3.id = UUID.randomUUID()
        port3.peerId = port1.id
        intercept[ReferenceConflictException] {
            storage.create(port3)
        }

        Then("The conflict exception counter should be incremented")
        getCountForCounter("conflictException") shouldBe 1
    }

    "Metrics" should "record object references exceptions" in {
        Given("A bridge with a chain")
        val chain = new PojoChain()
        chain.id = UUID.randomUUID()
        storage.create(chain)

        val bridge = new PojoBridge()
        bridge.id = UUID.randomUUID()
        bridge.inChainId = chain.id
        storage.create(bridge)

        When("Deleting the chain")
        intercept[ObjectReferencedException] {
            storage.delete(classOf[PojoChain], chain.id)
        }

        Then("The object referenced exception counter should be incremented")
        getCountForCounter("objectReferencedException") shouldBe 1
    }

    "Metrics" should "record object exists exceptions" in {
        Given("A bridge")
        val bridge = new PojoBridge()
        bridge.id = UUID.randomUUID()
        storage.create(bridge)

        When("Creating the bridge a second time")
        intercept[ObjectExistsException] {
            storage.create(bridge)
        }

        Then("The object exists exception counter should be incremented")
        getCountForCounter("objectExistsException") shouldBe 1
    }

    "Metrics" should "record object not found exception" in {
        Given("A bridge not found in storage")
        val bridge = new PojoBridge()
        bridge.id = UUID.randomUUID()

        When("Updating the bridge")
        intercept[NotFoundException] {
            storage.update(bridge)
        }

        Then("The object exists exception counter should be incremented")
        getCountForCounter("objectNotFoundException") shouldBe 1

        When("Deleting the bridge")
        intercept[NotFoundException] {
            storage.delete(classOf[PojoBridge], bridge.id)
        }

        Then("The object exists exception counter should be incremented")
        getCountForCounter("objectNotFoundException") shouldBe 2
    }

    "Metrics" should "record node exists exception" in {
        Given("A path")
        val path = s"$zkRoot/${UUID.randomUUID()}"
        storage.multi(Seq(CreateNodeOp(path, "test")))

        When("Creating the path a second time")
        intercept[StorageNodeExistsException] {
            storage.multi(Seq(CreateNodeOp(path, "test")))
        }

        Then("The storage node exists exception counter should be incremented")
        getCountForCounter("nodeExistsException") shouldBe 1
    }

    "Metrics" should "record node not found exception" in {
        Given("A path")
        val path = s"$zkRoot/${UUID.randomUUID()}"

        When("Deleting the path")
        intercept[StorageNodeNotFoundException] {
            storage.multi(Seq(UpdateNodeOp(path, "foo")))
        }

        Then("The storage node not found exception counter should be incremented")
        getCountForCounter("nodeNotFoundException") shouldBe 1
    }

    "Metrics" should "record object observable closed" in {
        Given("A bridge")
        val bridge = new PojoBridge()
        bridge.id = UUID.randomUUID()
        storage.create(bridge)

        And("A bridge observer")
        val observer = new TestAwaitableObserver[PojoBridge]

        When("The observer subscribes")
        val observable = storage.publicInternalObservable(classOf[PojoBridge],
                                                          bridge.id)
        val subscription = observable.subscribe(observer)

        Then("The observer should receive the bridge")
        observer.awaitOnNext(1, timeout) shouldBe true

        When("The observer unsubscribes and resubscribes")
        subscription.unsubscribe()
        observable.subscribe(observer)

        Then("The observer should receive the bridge")
        observer.awaitOnNext(1, timeout) shouldBe true

        And("The object observable closed counter should be incremented")
        getCountForCounter("objectObservableClosed") shouldBe 1
    }

    "Metrics" should "record not found exception on object observable" in {
        Given("A bridge observer")
        val observer = new TestAwaitableObserver[PojoBridge]

        When("The observer subscribes to a non-existing bridge")
        storage.observable(classOf[PojoBridge], UUID.randomUUID())
               .subscribe(observer)

        Then("The observer should receive an error")
        observer.awaitCompletion(timeout)

        And("The object observable not found counter should be incremented")
        getCountForCounter("objectNotFoundException") shouldBe 1
    }

    "Metrics" should "record object observable error" in {
        Given("A bad bridge object")
        val id = UUID.randomUUID()
        curator.create().creatingParentsIfNeeded()
                        .forPath(s"$zkRoot/zoom/0/models/PojoBridge/$id",
                                 "bad".getBytes)

        And("A bridge observer")
        val observer = new TestAwaitableObserver[PojoBridge]

        When("The observer subscribes")
        storage.observable(classOf[PojoBridge], id).subscribe(observer)

        Then("The observer should receive an error")
        observer.awaitCompletion(timeout)

        And("The object observable error counter should be incremented")
        getCountForCounter("objectObservableError") shouldBe 1
    }

    "Metrics" should "record class observable closed" in {
        Given("A bridge class observer")
        val observer = new TestAwaitableObserver[Observable[PojoBridge]]

        When("The observer subscribes")
        val observable = storage.observable(classOf[PojoBridge])
        val subscription = observable.subscribe(observer)

        And("The observer resubscribes")
        subscription.unsubscribe()
        observable.subscribe(observer)

        Then("The class observable closed counter should be incremented")
        getCountForCounter("classObservableClosed") shouldBe 1
    }

    "Metrics" should "record class observable error" in {
        Given("A bridge class observer")
        val observer = new TestAwaitableObserver[Observable[PojoBridge]]

        When("Deleting the class node")
        curator.delete().deletingChildrenIfNeeded()
               .forPath(s"$zkRoot/zoom/0/models/PojoBridge")

        And("Subscribing to the class observable")
        storage.observable(classOf[PojoBridge]).subscribe(observer)

        Then("The observer should receive an error")
        observer.awaitCompletion(timeout)

        And("The class observable error counter should be incremented")
        getCountForCounter("classObservableError") shouldBe 1
    }
}
