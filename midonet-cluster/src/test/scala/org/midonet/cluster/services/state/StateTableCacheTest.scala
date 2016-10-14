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

package org.midonet.cluster.services.state

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

import scala.collection.JavaConverters._
import scala.concurrent.{Future, Promise}
import scala.concurrent.duration._
import scala.reflect.ClassTag

import com.typesafe.config.ConfigFactory

import org.apache.zookeeper.{CreateMode, KeeperException}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, GivenWhenThen, Matchers}

import rx.Observable

import org.midonet.cluster.StateProxyConfig
import org.midonet.cluster.data.storage._
import org.midonet.cluster.rest_api.models.Bridge
import org.midonet.cluster.rpc.State.ProxyResponse.Notify
import org.midonet.cluster.rpc.State.ProxyResponse.Notify.Completed.Code
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.test.util.ZookeeperTestSuite
import org.midonet.packets.MAC
import org.midonet.util.concurrent.SameThreadButAfterExecutorService
import org.midonet.util.reactivex.TestAwaitableObserver

import ch.qos.logback.classic.{Level, Logger}

@RunWith(classOf[JUnitRunner])
class StateTableCacheTest extends FeatureSpec with Matchers with GivenWhenThen
                          with ZookeeperTestSuite {

    private class TestObserver(auto: Boolean = true,
                               doThrow: Boolean = false)
        extends TestAwaitableObserver[Notify] with StateTableObserver {
        var promise = Promise[AnyRef]()
        override def next(notify: Notify): Future[AnyRef] = {
            onNext(notify)
            if (doThrow) throw new Exception()
            if (auto) Future.successful(null) else promise.future
        }
    }

    class NotifyMatcher(notify: Notify) {
        def shouldBeSnapshotFor(begin: Boolean, end: Boolean, entries: (Any, Any)*)
        : Unit = {
            notify.hasUpdate shouldBe true
            notify.getUpdate.getType shouldBe Notify.Update.Type.SNAPSHOT
            notify.getUpdate.getBegin shouldBe begin
            notify.getUpdate.getEnd shouldBe end
            notify.getUpdate.getEntriesCount shouldBe entries.size

            notify.getUpdate.getEntriesList.asScala.map(e => e.getKey -> e.getValue) should
                contain theSameElementsAs entries.map(e => {
                    StateEntryDecoder.get(classOf[MAC]).decode(e._1.toString) ->
                    StateEntryDecoder.get(classOf[UUID]).decode(e._2.toString)
                })
        }

        def shouldBeSnapshotFor(begin: Boolean, end: Boolean, count: Int)
        : Unit = {
            notify.hasUpdate shouldBe true
            notify.getUpdate.getType shouldBe Notify.Update.Type.SNAPSHOT
            notify.getUpdate.getBegin shouldBe begin
            notify.getUpdate.getEnd shouldBe end
            notify.getUpdate.getEntriesCount shouldBe count
        }

        def shouldBeUpdateFor(begin: Boolean, end: Boolean, entries: (Any, Any)*)
        : Unit = {
            notify.hasUpdate shouldBe true
            notify.getUpdate.getType shouldBe Notify.Update.Type.RELATIVE
            notify.getUpdate.getBegin shouldBe begin
            notify.getUpdate.getEnd shouldBe end
            notify.getUpdate.getEntriesCount shouldBe entries.size
            notify.getUpdate.getEntriesList.asScala.map(e => {
                e.getKey -> (if (e.hasValue) e.getValue else null)
            }) should contain theSameElementsAs entries.map(e => {
                StateEntryDecoder.get(classOf[MAC]).decode(e._1.toString) -> {
                    if (e._2 != null)
                        StateEntryDecoder.get(classOf[UUID]).decode(e._2.toString)
                    else null
                }
            })
        }

        def shouldBeCompletedFor(code: Code, nsdbCode: Int)
        : Unit = {
            notify.hasCompleted shouldBe true
            notify.getCompleted.getCode shouldBe code
            notify.getCompleted.getNsdbCode shouldBe nsdbCode
        }
    }

    private var proxyConfig: StateProxyConfig = _
    private val storage = new StateTableStorage with StateTablePaths {

        override protected def pathExists(path: String): Boolean = false
        override protected def rootPath: String = ZK_ROOT
        override protected def zoomPath: String = ZK_ROOT
        override protected def version = new AtomicLong()

        override def getTable[K, V](clazz: Class[_], id: Any, name: String,
                                    args: Any*)
                                   (implicit key: ClassTag[K],
                                    value: ClassTag[V]): StateTable[K, V] = ???
        override def tableArguments(clazz: Class[_], id: Any, name: String,
                                    args: Any*): Future[Set[String]] = ???
        override def multi(ops: Seq[PersistenceOp]): Unit = ???
        override def isRegistered(clazz: Class[_]): Boolean = ???
        override def observable[T](clazz: Class[T], id: Any): Observable[T] = ???
        override def observable[T](clazz: Class[T]): Observable[Observable[T]] = ???
        override def registerClass(clazz: Class[_]): Unit = ???
        override def transaction(): Transaction = ???
        override def tryTransaction[R](f: (Transaction) => R): R = ???
        override def get[T](clazz: Class[T], id: Any): Future[T] = ???
        override def exists(clazz: Class[_], id: Any): Future[Boolean] = ???
        override def getAll[T](clazz: Class[T], ids: Seq[_ <: Any]): Future[Seq[T]] = ???
        override def getAll[T](clazz: Class[T]): Future[Seq[T]] = ???
    }

    private val timeout = 5 seconds
    private val counter = new AtomicLong()

    StateTableCache.Log.underlying.asInstanceOf[Logger].setLevel(Level.TRACE)

    before {
        proxyConfig = new StateProxyConfig(ConfigFactory.parseString(
            s"""
               |cluster.state_proxy.initial_subscriber_queue_size : 16
               |cluster.state_proxy.notify_batch_size : 4
             """.stripMargin))
    }

    private def tablePath(id: UUID): String = {
        storage.tablePath(classOf[Bridge], id, MidonetBackend.MacTable)
    }

    private def newCache(create: Boolean = true, id: UUID = UUID.randomUUID())(
                         onClose: => Unit)
    : StateTableCache = {
        if (create) {
            zkClient.create().creatingParentsIfNeeded()
                    .forPath(tablePath(id))
        }
        new StateTableCache(proxyConfig, storage, zkClient, counter,
                            classOf[Bridge], id, classOf[MAC],
                            classOf[UUID], MidonetBackend.MacTable, Seq.empty,
                            new SameThreadButAfterExecutorService,
                            _ => onClose)
    }

    private def addEntry(id: UUID, key: Any, value: Any): Unit = {
        zkClient.create().withMode(CreateMode.EPHEMERAL_SEQUENTIAL)
                .forPath(s"${tablePath(id)}/$key,$value,")
    }

    private def addEphemeral(id: UUID, key: Any, value: Any, version: Int): Unit = {
        zkClient.create().withMode(CreateMode.EPHEMERAL)
                .forPath(s"${tablePath(id)}/$key,$value,${"%010d".format(version)}")
    }

    private def addPersistent(id: UUID, key: Any, value: Any): Unit = {
        zkClient.create().withMode(CreateMode.PERSISTENT)
                .forPath(s"${tablePath(id)}/$key,$value,${"%010d".format(Int.MaxValue)}")
    }

    private def removeEntry(id: UUID, key: Any, value: Any, version: Int)
    : Unit = {
        zkClient.delete()
                .forPath(s"${tablePath(id)}/$key,$value,${"%010d".format(version)}")
    }

    private implicit def asMatcher(notify: Notify): NotifyMatcher = {
        new NotifyMatcher(notify)
    }

    feature("Cache state lifecycle") {
        scenario("Cache can be created and closed") {
            Given("A state table cache")
            var closed = false
            val cache = newCache() { closed = true }

            Then("The cache is stopped")
            cache.isStopped shouldBe true
            cache.isClosed shouldBe false

            And("There are no subscribers")
            cache.hasSubscribers shouldBe false

            When("Closing the cache")
            cache.close()

            Then("The cache is closed")
            cache.isStopped shouldBe false
            cache.isClosed shouldBe true

            And("There are no subscribers")
            cache.hasSubscribers shouldBe false

            And("The close handler should be called")
            closed shouldBe true
        }
    }

    feature("Cache handles subscriptions") {
        scenario("Observer subscribes and unsubscribes") {
            Given("A state table cache")
            var closed = false
            val cache = newCache() { closed = true }

            And("An observer")
            val observer = new TestObserver

            When("The observer subscribes")
            val subscription = cache.subscribe(observer, lastVersion = None)

            Then("The subscription should be subscribed")
            subscription.isUnsubscribed shouldBe false

            And("The observer should receive one notification")
            observer.awaitOnNext(1, timeout) shouldBe true
            observer.getOnNextEvents.get(0) shouldBeSnapshotFor(begin = true,
                end = true)

            When("The observer unsubscribes")
            subscription.unsubscribe()

            Then("The subscription should be unsubscribe")
            subscription.isUnsubscribed shouldBe true

            And("The cache should be closed")
            cache.isClosed shouldBe true
        }

        scenario("Cache handles multiple observers") {
            Given("A state table cache")
            var closed = false
            val cache = newCache() { closed = true }

            And("Three observers")
            val observer1 = new TestObserver
            val observer2 = new TestObserver
            val observer3 = new TestObserver

            When("The first observer subscribes")
            val sub1 = cache.subscribe(observer1, lastVersion = None)

            Then("The observer should receive one notification")
            observer1.awaitOnNext(1, timeout) shouldBe true
            observer1.getOnNextEvents.get(0) shouldBeSnapshotFor(begin = true,
                end = true)

            When("The second observer subscribes")
            val sub2 = cache.subscribe(observer2, lastVersion = None)

            Then("The observer should receive one notification")
            observer2.awaitOnNext(1, timeout) shouldBe true
            observer2.getOnNextEvents.get(0) shouldBeSnapshotFor(begin = true,
                end = true)

            When("The first observer unscubscribes")
            sub1.unsubscribe()

            Then("The cache should not be closed")
            cache.isClosed shouldBe false

            When("The third observer subscribes")
            val sub3 = cache.subscribe(observer3, lastVersion = None)

            Then("The observer should receive one notification")
            observer3.awaitOnNext(1, timeout) shouldBe true
            observer3.getOnNextEvents.get(0) shouldBeSnapshotFor(begin = true,
                end = true)

            When("The observers unsubscribe")
            sub2.unsubscribe()
            sub3.unsubscribe()

            Then("The cache should be closed")
            cache.isClosed shouldBe true
            closed shouldBe true
        }

        scenario("Cache does not allow subscriptions after close") {
            Given("A state table cache")
            var closed = false
            val cache = newCache() { closed = true }

            And("An observer")
            val observer = new TestObserver

            When("The cache closes")
            cache.close()

            Then("The observer subscribing should throw an exception")
            intercept[StateTableCacheClosedException] {
                cache.subscribe(observer, lastVersion = None)
            }
        }

        scenario("Calling unsubscribe on an unsubscribed subscription") {
            Given("A state table cache")
            var closed = false
            val cache = newCache() { closed = true }

            And("An observer")
            val observer = new TestObserver

            When("The observer subscribes")
            val subscription = cache.subscribe(observer, lastVersion = None)

            Then("The subscription should be subscribed")
            subscription.isUnsubscribed shouldBe false

            When("The observer unsubscribes")
            subscription.unsubscribe()

            Then("The observer should be unsubscribed")
            subscription.isUnsubscribed shouldBe true

            When("Calling unsubscribe a second time")
            subscription.unsubscribe()

            Then("The observer should be unsubscribed")
            subscription.isUnsubscribed shouldBe true
        }
    }

    feature("Cache emits table updates") {
        scenario("Table does not exist") {
            Given("A state table cache for a non-existing table")
            var closed = false
            val cache = newCache(create = false) { closed = true }

            And("An observer")
            val observer = new TestObserver

            When("The observer subscribes")
            val subscription = cache.subscribe(observer, lastVersion = None)

            Then("The observer should receive an error")
            observer.awaitOnNext(1, timeout)
            observer.getOnNextEvents.get(0) shouldBeCompletedFor(Code.NSDB_ERROR,
                KeeperException.Code.NONODE.intValue())

            And("The observer should be unsubscribed")
            subscription.isUnsubscribed shouldBe true

            And("The cache should be closed")
            cache.isClosed shouldBe true
            closed shouldBe true
        }

        scenario("State table with no initial entries") {
            Given("A state table cache")
            var closed = false
            val id = UUID.randomUUID()
            val cache = newCache(create = true, id) { closed = true }

            And("An observer")
            val observer = new TestObserver

            When("The observer subscribes")
            cache.subscribe(observer, lastVersion = None)

            Then("The observer receives an empty snapshot")
            observer.awaitOnNext(1, timeout) shouldBe true
            observer.getOnNextEvents.get(0) shouldBeSnapshotFor(begin = true,
                end = true)

            When("Adding a first entry")
            val key1 = MAC.random()
            val value1 = UUID.randomUUID()
            addEntry(id, key1, value1)

            Then("The observer receives the update")
            observer.awaitOnNext(2, timeout) shouldBe true
            observer.getOnNextEvents.get(1) shouldBeUpdateFor(begin = true,
                end = true, key1 -> value1)

            When("Adding a second entry")
            val key2 = MAC.random()
            val value2 = UUID.randomUUID()
            addEntry(id, key2, value2)

            Then("The observer receives the update")
            observer.awaitOnNext(3, timeout) shouldBe true
            observer.getOnNextEvents.get(2) shouldBeUpdateFor(begin = true,
                end = true, key2 -> value2)

            When("Removing the first entry")
            removeEntry(id, key1, value1, 0)

            Then("The observer receives the update")
            observer.awaitOnNext(4, timeout) shouldBe true
            observer.getOnNextEvents.get(3) shouldBeUpdateFor(begin = true,
                end = true, key1 -> null)

            When("Removing the second entry")
            removeEntry(id, key2, value2, 1)

            Then("The observer receives the update")
            observer.awaitOnNext(5, timeout) shouldBe true
            observer.getOnNextEvents.get(4) shouldBeUpdateFor(begin = true,
                end = true, key2 -> null)

            cache.close()
        }

        scenario("State table with initial entries") {
            Given("A state table cache")
            var closed = false
            val id = UUID.randomUUID()
            val cache = newCache(create = true, id) { closed = true }

            And("A first entry")
            val key1 = MAC.random()
            val value1 = UUID.randomUUID()
            addEntry(id, key1, value1)

            And("An observer")
            val observer = new TestObserver

            When("The observer subscribes")
            cache.subscribe(observer, lastVersion = None)

            Then("The observer receives a snapshot with the entry")
            observer.awaitOnNext(1, timeout) shouldBe true
            observer.getOnNextEvents.get(0) shouldBeSnapshotFor(begin = true,
                end = true, key1 -> value1)

            When("Adding a second entry")
            val key2 = MAC.random()
            val value2 = UUID.randomUUID()
            addEntry(id, key2, value2)

            Then("The observer receives the update")
            observer.awaitOnNext(2, timeout) shouldBe true
            observer.getOnNextEvents.get(1) shouldBeUpdateFor(begin = true,
                end = true, key2 -> value2)

            When("Removing the first entry")
            removeEntry(id, key1, value1, 0)

            Then("The observer receives the update")
            observer.awaitOnNext(3, timeout) shouldBe true
            observer.getOnNextEvents.get(2) shouldBeUpdateFor(begin = true,
                end = true, key1 -> null)

            When("Removing the second entry")
            removeEntry(id, key2, value2, 1)

            Then("The observer receives the update")
            observer.awaitOnNext(4, timeout) shouldBe true
            observer.getOnNextEvents.get(3) shouldBeUpdateFor(begin = true,
                end = true, key2 -> null)

            cache.close()
        }

        scenario("State table with multiple observers") {
            Given("A state table cache")
            var closed = false
            val id = UUID.randomUUID()
            val cache = newCache(create = true, id) { closed = true }

            And("A first entry")
            val key1 = MAC.random()
            val value1 = UUID.randomUUID()
            addEntry(id, key1, value1)

            And("Three observers")
            val observer1 = new TestObserver
            val observer2 = new TestObserver
            val observer3 = new TestObserver

            When("The first observer subscribes")
            cache.subscribe(observer1, lastVersion = None)

            Then("The first observer receives a snapshot with the entry")
            observer1.awaitOnNext(1, timeout) shouldBe true
            observer1.getOnNextEvents.get(0) shouldBeSnapshotFor(begin = true,
                end = true, key1 -> value1)

            When("Adding a second entry")
            val key2 = MAC.random()
            val value2 = UUID.randomUUID()
            addEntry(id, key2, value2)

            Then("The first observer receives the update")
            observer1.awaitOnNext(2, timeout) shouldBe true
            observer1.getOnNextEvents.get(1) shouldBeUpdateFor(begin = true,
                end = true, key2 -> value2)

            When("The second observer subscribes")
            cache.subscribe(observer2, lastVersion = None)

            Then("The second observer receives a snapshot")
            observer2.awaitOnNext(1, timeout) shouldBe true
            observer2.getOnNextEvents.get(0) shouldBeSnapshotFor(begin = true,
                end = true, key1 -> value1, key2 -> value2)

            When("Adding a third entry")
            val key3 = MAC.random()
            val value3 = UUID.randomUUID()
            addEntry(id, key3, value3)

            Then("The first observer receives the update")
            observer1.awaitOnNext(3, timeout) shouldBe true
            observer1.getOnNextEvents.get(2) shouldBeUpdateFor(begin = true,
                end = true, key3 -> value3)

            And("The second observer receives the update")
            observer2.awaitOnNext(2, timeout) shouldBe true
            observer2.getOnNextEvents.get(1) shouldBeUpdateFor(begin = true,
                end = true, key3 -> value3)

            When("Removing the first entry")
            removeEntry(id, key1, value1, 0)

            Then("The first observer receives the update")
            observer1.awaitOnNext(4, timeout) shouldBe true
            observer1.getOnNextEvents.get(3) shouldBeUpdateFor(begin = true,
                end = true, key1 -> null)

            And("The second observer receives the update")
            observer2.awaitOnNext(3, timeout) shouldBe true
            observer2.getOnNextEvents.get(2) shouldBeUpdateFor(begin = true,
                end = true, key1 -> null)

            When("The third observer subscribes")
            cache.subscribe(observer3, lastVersion = None)

            Then("The third observer receives a snapshot")
            observer3.awaitOnNext(1, timeout) shouldBe true
            observer3.getOnNextEvents.get(0) shouldBeSnapshotFor(begin = true,
                end = true, key2 -> value2, key3 -> value3)

            When("Removing the second entry")
            removeEntry(id, key2, value2, 1)

            Then("All observers receive the update")
            observer1.awaitOnNext(5, timeout) shouldBe true
            observer1.getOnNextEvents.get(4) shouldBeUpdateFor(begin = true,
                end = true, key2 -> null)

            observer2.awaitOnNext(4, timeout) shouldBe true
            observer2.getOnNextEvents.get(3) shouldBeUpdateFor(begin = true,
                end = true, key2 -> null)

            observer3.awaitOnNext(2, timeout) shouldBe true
            observer3.getOnNextEvents.get(1) shouldBeUpdateFor(begin = true,
                end = true, key2 -> null)

            cache.close()
        }

        scenario("State table handles a throwing observer") {
            Given("A state table cache")
            var closed = false
            val id = UUID.randomUUID()
            val cache = newCache(create = true, id) { closed = true }

            And("A first entry")
            val key1 = MAC.random()
            val value1 = UUID.randomUUID()
            addEntry(id, key1, value1)

            And("A bad observer")
            val badObserver = new TestObserver(auto = true, doThrow = true)

            When("The observer subscribes")
            cache.subscribe(badObserver, lastVersion = None)

            Then("The bad observer receives a snapshot with the entry")
            badObserver.awaitOnNext(1, timeout) shouldBe true
            badObserver.getOnNextEvents.get(0) shouldBeSnapshotFor(begin = true,
                end = true, key1 -> value1)

            When("A good observer subscribes")
            val goodObserver = new TestObserver()
            cache.subscribe(goodObserver, lastVersion = None)

            Then("The good observer receives a snapshot with the entry")
            goodObserver.awaitOnNext(1, timeout) shouldBe true
            goodObserver.getOnNextEvents.get(0) shouldBeSnapshotFor(begin = true,
                end = true, key1 -> value1)

            When("Adding a second entry")
            val key2 = MAC.random()
            val value2 = UUID.randomUUID()
            addEntry(id, key2, value2)

            Then("Both observers should receive the update")
            badObserver.awaitOnNext(2, timeout) shouldBe true
            badObserver.getOnNextEvents.get(1) shouldBeUpdateFor(begin = true,
                end = true, key2 -> value2)

            goodObserver.awaitOnNext(2, timeout) shouldBe true
            goodObserver.getOnNextEvents.get(1) shouldBeUpdateFor(begin = true,
                end = true, key2 -> value2)

            cache.close()
        }

        scenario("State table with multiple key versions") {
            Given("A state table cache")
            var closed = false
            val id = UUID.randomUUID()
            val cache = newCache(create = true, id) { closed = true }

            And("A first entry")
            val key1 = MAC.random()
            val value1_1 = UUID.randomUUID()
            addEphemeral(id, key1, value1_1, 0)

            And("An observer")
            val observer = new TestObserver

            When("The observer subscribes")
            cache.subscribe(observer, lastVersion = None)

            Then("The observer receives a snapshot with the entry")
            observer.awaitOnNext(1, timeout) shouldBe true
            observer.getOnNextEvents.get(0) shouldBeSnapshotFor(begin = true,
                end = true, key1 -> value1_1)

            And("The key version is 0")
            observer.getOnNextEvents.get(0).getUpdate.getEntries(0).getVersion shouldBe 0

            When("Adding an entry for the same key with higher version")
            val value1_2 = UUID.randomUUID()
            addEphemeral(id, key1, value1_2, 1)

            Then("The observer receives the update")
            observer.awaitOnNext(2, timeout) shouldBe true
            observer.getOnNextEvents.get(1) shouldBeUpdateFor(begin = true,
                end = true, key1 -> value1_2)

            And("The key version is 1")
            observer.getOnNextEvents.get(1).getUpdate.getEntries(0).getVersion shouldBe 1

            When("Removing the second key")
            removeEntry(id, key1, value1_2, 1)

            Then("The observer receives the key reverted to previous value")
            observer.awaitOnNext(3, timeout) shouldBe true
            observer.getOnNextEvents.get(2) shouldBeUpdateFor(begin = true,
                end = true, key1 -> value1_1)

            And("The key version is 0")
            observer.getOnNextEvents.get(2).getUpdate.getEntries(0).getVersion shouldBe 0

            cache.close()
        }

        scenario("State table with persistent entries") {
            Given("A state table cache")
            var closed = false
            val id = UUID.randomUUID()
            val cache = newCache(create = true, id) { closed = true }

            And("A first persistent entry")
            val key1 = MAC.random()
            val value1_1 = UUID.randomUUID()
            addPersistent(id, key1, value1_1)

            And("An observer")
            val observer = new TestObserver

            When("The observer subscribes")
            cache.subscribe(observer, lastVersion = None)

            Then("The observer receives a snapshot with the entry")
            observer.awaitOnNext(1, timeout) shouldBe true
            observer.getOnNextEvents.get(0) shouldBeSnapshotFor(begin = true,
                end = true, key1 -> value1_1)

            And("The key version is persistent")
            observer.getOnNextEvents.get(0).getUpdate.getEntries(0).getVersion shouldBe Int.MaxValue

            When("Adding a learned entry for the same key")
            val value1_2 = UUID.randomUUID()
            addEphemeral(id, key1, value1_2, 0)

            Then("The observer receives the update")
            observer.awaitOnNext(2, timeout) shouldBe true
            observer.getOnNextEvents.get(1) shouldBeUpdateFor(begin = true,
                end = true, key1 -> value1_2)

            And("The key version is 0")
            observer.getOnNextEvents.get(1).getUpdate.getEntries(0).getVersion shouldBe 0

            When("Removing the learned key")
            removeEntry(id, key1, value1_2, 0)

            Then("The observer receives the persistent entry")
            observer.awaitOnNext(3, timeout) shouldBe true
            observer.getOnNextEvents.get(2) shouldBeUpdateFor(begin = true,
                end = true, key1 -> value1_1)

            And("The key version is persistent")
            observer.getOnNextEvents.get(2).getUpdate.getEntries(0).getVersion shouldBe Int.MaxValue

            cache.close()
        }

        scenario("State table with invalid entries") {
            Given("A state table cache")
            var closed = false
            val id = UUID.randomUUID()
            val cache = newCache(create = true, id) { closed = true }

            And("An invalid entry")
            zkClient.create().creatingParentsIfNeeded()
                .withMode(CreateMode.PERSISTENT)
                .forPath(s"${tablePath(id)}/some-invalid-entry")

            And("An observer")
            val observer = new TestObserver

            When("The observer subscribes")
            cache.subscribe(observer, lastVersion = None)

            Then("The observer receives an empty snapshot")
            observer.awaitOnNext(1, timeout) shouldBe true
            observer.getOnNextEvents.get(0) shouldBeSnapshotFor(begin = true,
                end = true)

            When("Adding an entry")
            val key = MAC.random()
            val value = UUID.randomUUID()
            addEntry(id, key, value)

            Then("The observer receives the update")
            observer.awaitOnNext(2, timeout) shouldBe true
            observer.getOnNextEvents.get(1) shouldBeUpdateFor(begin = true,
                end = true, key -> value)

            When("Removing the entry")
            removeEntry(id, key, value, 1)

            Then("The observer receives the update")
            observer.awaitOnNext(3, timeout) shouldBe true
            observer.getOnNextEvents.get(2) shouldBeUpdateFor(begin = true,
                end = true, key -> null)

            cache.close()
        }

        scenario("State table with invalid encoding") {
            Given("A state table cache")
            var closed = false
            val id = UUID.randomUUID()
            val cache = newCache(create = true, id) { closed = true }

            And("An invalid entry")
            zkClient.create().creatingParentsIfNeeded()
                .withMode(CreateMode.PERSISTENT)
                .forPath(s"${tablePath(id)}/invalid-key,invalid-value,0")

            And("An observer")
            val observer = new TestObserver

            When("The observer subscribes")
            cache.subscribe(observer, lastVersion = None)

            Then("The observer receives an empty snapshot")
            observer.awaitOnNext(1, timeout) shouldBe true
            observer.getOnNextEvents.get(0) shouldBeSnapshotFor(begin = true,
                end = true)

            When("Adding an entry")
            val key = MAC.random()
            val value = UUID.randomUUID()
            addEntry(id, key, value)

            Then("The observer receives the update")
            observer.awaitOnNext(2, timeout) shouldBe true
            observer.getOnNextEvents.get(1) shouldBeUpdateFor(begin = true,
                end = true, key -> value)

            When("Removing the entry")
            removeEntry(id, key, value, 1)

            Then("The observer receives the update")
            observer.awaitOnNext(3, timeout) shouldBe true
            observer.getOnNextEvents.get(2) shouldBeUpdateFor(begin = true,
                end = true, key -> null)

            cache.close()
        }

        scenario("Cache batches notifications") {
            Given("A state table cache")
            var closed = false
            val id = UUID.randomUUID()
            val cache = newCache(create = true, id) { closed = true }

            And("Six entries")
            for (index <- 0 until 10) {
                addEntry(id, MAC.random(), UUID.randomUUID())
            }

            And("An observer")
            val observer = new TestObserver

            When("The observer subscribes")
            cache.subscribe(observer, lastVersion = None)

            Then("The observer receives three snapshot message")
            observer.awaitOnNext(3, timeout) shouldBe true
            observer.getOnNextEvents.get(0) shouldBeSnapshotFor(begin = true,
                end = false, 4)
            observer.getOnNextEvents.get(1) shouldBeSnapshotFor(begin = false,
                end = false, 4)
            observer.getOnNextEvents.get(2) shouldBeSnapshotFor(begin = false,
                end = true, 2)

            cache.close()
        }

        scenario("Cache handles back-pressure") {
            Given("A state table cache")
            var closed = false
            val id = UUID.randomUUID()
            val cache = newCache(create = true, id) { closed = true }

            And("A first entry")
            val key1 = MAC.random()
            val value1 = UUID.randomUUID()
            addEntry(id, key1, value1)

            And("An observer with back-pressure")
            val observer = new TestObserver(auto = false)

            When("The observer subscribes")
            cache.subscribe(observer, lastVersion = None)

            Then("The first observer receives a snapshot with the entry")
            observer.awaitOnNext(1, timeout) shouldBe true
            observer.getOnNextEvents.get(0) shouldBeSnapshotFor(begin = true,
                end = true, key1 -> value1)

            When("Adding a second entry")
            val key2 = MAC.random()
            val value2 = UUID.randomUUID()
            addEntry(id, key2, value2)

            Then("The observer does not receive a second update")
            observer.getOnNextEvents.size() shouldBe 1

            When("The observer completes it first promise")
            observer.promise.trySuccess(None)

            Then("The observer receives a second update")
            observer.awaitOnNext(2, timeout) shouldBe true
            observer.getOnNextEvents.get(1) shouldBeUpdateFor(begin = true,
                end = true, key2 -> value2)

            cache.close()
        }
    }

    feature("Cache handles connection changes") {
        scenario("Connection closed by client") {
            Given("A state table cache")
            var closed = false
            val id = UUID.randomUUID()
            val cache = newCache(create = true, id) { closed = true }

            And("An observer")
            val observer = new TestObserver

            When("The observer subscribes")
            cache.subscribe(observer, lastVersion = None)

            Then("The observer receives an empty snapshot with the entry")
            observer.awaitOnNext(1, timeout) shouldBe true
            observer.getOnNextEvents.get(0) shouldBeSnapshotFor(begin = true,
                end = true)

            When("Restarting the server")
            zkServer.restart()

            And("Adding an entry")
            val key = MAC.random()
            val value = UUID.randomUUID()
            addEntry(id, key, value)

            Then("The observer receives the update")
            observer.awaitOnNext(2, timeout) shouldBe true
            observer.getOnNextEvents.get(1) shouldBeUpdateFor(begin = true,
                end = true, key -> value)

            cache.close()
        }
    }

}
