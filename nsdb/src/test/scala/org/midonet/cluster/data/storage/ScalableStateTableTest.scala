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

import java.util.UUID

import scala.concurrent.duration._

import ch.qos.logback.classic.{Level, Logger}

import org.apache.curator.framework.state.ConnectionState
import org.apache.curator.utils.ZKPaths
import org.apache.zookeeper.KeeperException.ConnectionLossException
import org.junit.runner.RunWith
import org.scalatest.{FeatureSpec, GivenWhenThen, Matchers}
import org.scalatest.junit.JUnitRunner

import rx.Observable
import rx.observers.TestObserver
import rx.subjects.PublishSubject

import org.midonet.cluster.backend.zookeeper.ZkDirectory
import org.midonet.cluster.backend.{Directory, MockDirectory}
import org.midonet.cluster.data.storage.StateTable.{Key, Update}
import org.midonet.cluster.rpc.State.ProxyResponse.Notify
import org.midonet.cluster.services.state.client.{StateSubscriptionKey, StateTableClient}
import org.midonet.cluster.services.state.client.StateTableClient.ConnectionState.{ConnectionState => ProxyConnectionState}
import org.midonet.cluster.storage.CuratorZkConnection
import org.midonet.cluster.util.CuratorTestFramework
import org.midonet.util.MidonetEventually
import org.midonet.util.eventloop.CallingThreadReactor
import org.midonet.util.reactivex.TestAwaitableObserver

@RunWith(classOf[JUnitRunner])
class ScalableStateTableTest extends FeatureSpec with Matchers
                             with GivenWhenThen with CuratorTestFramework
                             with MidonetEventually {

    private class Table(objectId: UUID,
                        override val directory: Directory,
                        override val proxy: StateTableClient,
                        override val connection: Observable[ConnectionState])
        extends ScalableStateTable[String, String]
        with DirectoryStateTable[String, String] {

        log.underlying.asInstanceOf[Logger].setLevel(Level.TRACE)

        protected[storage] override lazy val nullValue = null
        protected[storage] override lazy val tableKey =
            Key(classOf[Object], objectId, classOf[String], classOf[String],
                "test", Seq.empty)

        protected override def decodeKey(string: String): String = string
        protected override def decodeValue(string: String): String = string
        protected override def encodeKey(key: String): String = key
        protected override def encodeValue(value: String): String = value
    }

    private val proxy = new StateTableClient {
        override def stop(): Boolean = false
        override def observable(table: StateSubscriptionKey): Observable[Notify.Update] =
            Observable.never()
        override def connection: Observable[ProxyConnectionState] =
            Observable.never()
        override def start(): Unit = { }
    }

    private val reactor = new CallingThreadReactor
    private val timeout = 5 seconds

    private def mockTable(connection: Observable[ConnectionState] =
                              Observable.never())
    : ScalableStateTable[String, String] = {
        new Table(UUID.randomUUID(), new MockDirectory(), proxy, connection)
    }

    private def zkTable(create: Boolean = true)
    : (ScalableStateTable[String, String], String) = {
        val id = UUID.randomUUID()
        val connection = new CuratorZkConnection(curator, reactor)
        val path = s"$zkRoot/$id"
        if (create) {
            ZKPaths.mkdirs(curator.getZookeeperClient.getZooKeeper, path)
        }
        val directory = new ZkDirectory(connection, path, reactor)
        (new Table(id, directory, proxy, Observable.never()), path)
    }

    feature("Test table lifecycle") {
        scenario("Table starts and stops and counts subscriptions") {
            Given("A state table with mock directory")
            val table = mockTable()

            Then("Requesting the table snapshot returns empty")
            table.localSnapshot shouldBe Map.empty

            When("Starting the table")
            table.start()

            Then("Requesting the table snapshot succeeds")
            table.localSnapshot shouldBe empty

            When("Starting the table a second time")
            table.start()

            Then("Requesting the table snapshot succeeds")
            table.localSnapshot shouldBe empty

            When("Stopping the table")
            table.stop()

            Then("Requesting the table snapshot succeeds")
            table.localSnapshot shouldBe empty

            When("Stopping the table a second time")
            table.stop()

            Then("Requesting the table snapshot returns empty")
            table.localSnapshot shouldBe empty
        }

        scenario("Table starts on observable subscribers") {
            Given("A state table with mock directory")
            val table = mockTable()

            And("A table observer")
            val observer = new TestObserver[StateTable.Update[String, String]]

            When("The observer subscribes to the table")
            val subscription = table.observable.subscribe(observer)

            Then("Requesting the table snapshot succeeds")
            table.localSnapshot shouldBe empty

            When("The observer unsubscribes")
            subscription.unsubscribe()

            Then("The table is stopped")
            table.isStopped shouldBe true
        }

        scenario("Table mixes user start with observable subscriptions") {
            Given("A state table with mock directory")
            val table = mockTable()

            And("A table observer")
            val observer = new TestObserver[StateTable.Update[String, String]]

            When("The observer subscribes to the table")
            val subscription = table.observable.subscribe(observer)

            Then("Requesting the table snapshot succeeds")
            table.localSnapshot shouldBe empty

            When("Starting the table")
            table.start()

            When("The observer unsubscribes")
            subscription.unsubscribe()

            Then("Requesting the table snapshot succeeds")
            table.localSnapshot shouldBe empty

            When("Stopping the table")
            table.stop()

            Then("The table is stopped")
            table.isStopped shouldBe true
        }

        scenario("Table handles multiple subscriptions") {
            Given("A state table with mock directory")
            val table = mockTable()

            And("A table observers")
            val observer = new TestObserver[StateTable.Update[String, String]]

            When("The observer subscribes to the table first time")
            val subscription1 = table.observable.subscribe(observer)

            Then("Requesting the table snapshot succeeds")
            table.localSnapshot shouldBe empty

            When("The observer subscribes to the table second time")
            val subscription2 = table.observable.subscribe(observer)

            And("The first subscription unsubscribes")
            subscription1.unsubscribe()

            Then("Requesting the table snapshot succeeds")
            table.localSnapshot shouldBe empty

            When("The second subscription unsubscribes")
            subscription2.unsubscribe()

            Then("The table is stopped")
            table.isStopped shouldBe true
        }
    }

    feature("Table handles storage connection") {
        scenario("Table is closed when connection is lost") {
            Given("A connection observable")
            val connection = PublishSubject.create[ConnectionState]

            And("A state table")
            val table = mockTable(connection)

            When("Starting the table")
            table.start()

            And("Emitting a connection lost")
            connection onNext ConnectionState.LOST

            Then("The table is stopped")
            table.isStopped shouldBe true
        }

        scenario("Connection loss notifies subscribers") {
            Given("A connection observable")
            val connection = PublishSubject.create[ConnectionState]

            And("A state table")
            val table = mockTable(connection)

            And("A table observers")
            val observer = new TestObserver[StateTable.Update[String, String]]

            When("Starting the table")
            table.start()

            And("The observer subscribes")
            val subscription = table.observable.subscribe(observer)

            And("Emitting a connection lost")
            connection onNext ConnectionState.LOST

            Then("The observer should receive an error")
            observer.getOnErrorEvents should have size 1
            observer.getOnErrorEvents.get(0).getClass shouldBe
                classOf[ConnectionLossException]

            And("The observer should be unsubscribed")
            subscription.isUnsubscribed shouldBe true
        }

        scenario("Table handles add on disconnected table") {
            Given("A connection observable")
            val connection = PublishSubject.create[ConnectionState]

            And("A state table")
            val table = mockTable(connection)
            table.start()

            When("The connection is suspended")
            connection onNext ConnectionState.SUSPENDED

            Then("Adding an entry should fail")
            table.add("key0", "value0")

            table.localSnapshot shouldBe empty

            When("The connection is reconnected")
            connection onNext ConnectionState.RECONNECTED

            Then("Adding an entry should succeed")
            table.add("key0", "value0")

            table.localSnapshot shouldBe Map("key0" -> "value0")
        }
    }

    feature("Table supports basic operations") {
        scenario("Table no ops if not started") {
            Given("A state table with mock directory")
            val table = mockTable()

            Then("Adding an entry does nothing")
            table.add("key0", "value0")

            And("Removing a key returnes null")
            table.remove("key0") shouldBe null

            And("Removing an entry returns false")
            table.remove("key0", "value0") shouldBe false

            And("Verifying contains key returns false")
            table.containsLocal("key0") shouldBe false

            And("Verifying contains entry returns false")
            table.containsLocal("key0", "value0") shouldBe false

            And("Getting a key returns null")
            table.getLocal("key0") shouldBe null

            And("Getting by value returns nothing")
            table.getLocalByValue("value0") shouldBe Set.empty

            And("Getting the snapshot returns nothing")
            table.localSnapshot shouldBe Map.empty
        }

        scenario("Table adds and removes entries") {
            Given("A state table with ZooKeeper directory")
            val (table, path) = zkTable()
            table.start()

            When("Adding a new entry")
            table.add("key0", "value0")

            Then("The table should contain the entry")
            eventually { table.containsLocal("key0") shouldBe true }
            table.containsLocal("key0", "value0") shouldBe true
            table.getLocal("key0") shouldBe "value0"
            table.getLocalByValue("value0") shouldBe Set("key0")
            table.localSnapshot shouldBe Map("key0" -> "value0")

            And("The store should contain the entry")
            curator.getChildren.forPath(s"$path") should contain only
                "key0,value0,0000000000"

            When("Adding a new entry")
            table.add("key1", "value1")

            Then("The table should contain the entry")
            eventually { table.containsLocal("key1") shouldBe true }
            table.containsLocal("key1", "value1") shouldBe true
            table.getLocal("key1") shouldBe "value1"
            table.getLocalByValue("value1") shouldBe Set("key1")
            table.localSnapshot shouldBe Map("key0" -> "value0",
                                             "key1" -> "value1")

            And("The store should contain the entries")
            curator.getChildren.forPath(s"$path") should contain allOf
                ("key0,value0,0000000000", "key1,value1,0000000001")


            When("Removing an key")
            table.remove("key0")

            Then("The table should not contain the entry")
            eventually { table.containsLocal("key0") shouldBe false }
            table.containsLocal("key0", "value0") shouldBe false
            table.getLocal("key0") shouldBe null
            table.getLocalByValue("value0") shouldBe Set.empty
            table.localSnapshot shouldBe Map("key1" -> "value1")

            And("The store should contain the entries")
            curator.getChildren.forPath(s"$path") should contain only
                "key1,value1,0000000001"

            When("Removing a non-matching entry")
            table.remove("key1", "value0")

            Then("The snapshot should not change")
            table.localSnapshot shouldBe Map("key1" -> "value1")

            And("The store should contain the entries")
            curator.getChildren.forPath(s"$path") should contain only
                "key1,value1,0000000001"

            When("Removing a matching entry")
            table.remove("key1", "value1")

            Then("The table should not contain the entry")
            eventually { table.containsLocal("key1") shouldBe false }
            table.containsLocal("key1", "value1") shouldBe false
            table.getLocal("key0") shouldBe null
            table.getLocalByValue("value0") shouldBe Set.empty
            table.localSnapshot shouldBe empty

            And("The store should contain the entries")
            curator.getChildren.forPath(s"$path") shouldBe empty

            table.stop()
        }

        scenario("Table detects duplicate entries on add") {
            Given("A state table with ZooKeeper directory")
            val (table, path) = zkTable()
            table.start()

            When("Adding the same entry twice")
            table.add("key0", "value0")
            table.add("key0", "value0")

            Then("The table should contain the entry")
            eventually { table.containsLocal("key0") shouldBe true }

            And("The store should contain one node")
            curator.getChildren.forPath(s"$path") should contain only
                "key0,value0,0000000000"

            table.stop()
        }

        scenario("Table detects duplicate entries in cache") {
            Given("A state table with ZooKeeper directory")
            val (table, path) = zkTable()
            table.start()

            When("Adding an entry")
            table.add("key0", "value0")

            Then("The table should contain the entry")
            eventually { table.containsLocal("key0") shouldBe true }

            When("Adding the same entry and a new entry for control")
            table.add("key0", "value0")
            table.add("key1", "value1")

            Then("The table should contain the control entry")
            eventually { table.containsLocal("key1") shouldBe true }

            And("The store should contain one node for the first entry")
            curator.getChildren.forPath(s"$path") should contain allOf
                ("key0,value0,0000000000", "key1,value1,0000000001")

            table.stop()
        }

        scenario("Table allows duplicate entries after deletion") {
            Given("A state table with ZooKeeper directory")
            val (table, path) = zkTable()
            table.start()

            When("Adding an entry")
            table.add("key0", "value0")

            Then("The table should contain the entry")
            eventually { table.containsLocal("key0") shouldBe true }

            When("Removing the entry and re-adding the entry")
            table.remove("key0", "value0")
            table.add("key0", "value0")

            And("Adding a second control entry")
            table.add("key1", "value1")

            Then("The table should contain both entries")
            eventually { table.containsLocal("key1") shouldBe true }
            table.containsLocal("key0", "value0")

            And("The store should contain nodes for both entries")
            curator.getChildren.forPath(s"$path") should contain allOf
                ("key0,value0,0000000001", "key1,value1,0000000002")

            table.stop()
        }

        scenario("Table removes owned entries") {
            Given("A state table with ZooKeeper directory")
            val (table, path) = zkTable()
            table.start()

            When("Adding an entry")
            table.add("key0", "value0")

            Then("The table should contain the entry")
            eventually { table.containsLocal("key0") shouldBe true }

            And("The storage should contain the entry")
            curator.checkExists().forPath(s"$path/key0,value0,0000000000") should not be null

            When("Adding a new entry for the same key")
            table.add("key0", "value1")

            Then("The table should contain the entry")
            eventually { table.containsLocal("key0", "value1") shouldBe true }
            table.localSnapshot shouldBe Map("key0" -> "value1")

            And("The storage should contain the latest entry")
            curator.checkExists().forPath(s"$path/key0,value0,0000000000") shouldBe null
            curator.checkExists().forPath(s"$path/key0,value1,0000000001") should not be null
        }

        scenario("Table does not remove not owned entries") {
            Given("A state table with ZooKeeper directory")
            val (table, path) = zkTable()
            table.start()

            When("Adding third party entries")
            curator.create().forPath(s"$path/key0,value0,0000000000")

            Then("The table should contain the entry")
            eventually {
                table.localSnapshot shouldBe Map("key0" -> "value0")
            }

            And("Removing the entry should return null")
            table.remove("key0", "value0") shouldBe false

            And("Removing the key should return null")
            table.remove("key0") shouldBe null

            And("The storage should contain the entry")
            curator.checkExists().forPath(s"$path/key0,value0,0000000000") should not be null

            table.stop()
        }

        scenario("Table handles add of the same entry after remove") {
            Given("A state table with ZooKeeper directory")
            val (table, path) = zkTable()
            table.start()

            When("Adding an entry")
            table.add("key0", "value0")

            Then("The table should contain the entry")
            eventually { table.containsLocal("key0") shouldBe true }

            And("The storage should contain the entry")
            curator.checkExists().forPath(s"$path/key0,value0,0000000000") should not be null

            When("Removing and re-adding the entry")
            table.remove("key0")
            table.add("key0", "value0")

            Then("The storage should contain a new entry")
            eventually {
                curator.checkExists().forPath(s"$path/key0,value0,0000000001") should not be null
            }

            And("The table should contain the entry")
            eventually { table.containsLocal("key0") shouldBe true }
        }
    }

    feature("Table merges entries") {
        scenario("Table loads existing entries") {
            Given("A state table with ZooKeeper directory")
            val (table, path) = zkTable()
            table.start()

            When("Adding third party entries")
            curator.create().forPath(s"$path/key0,value0,0000000000")
            curator.create().forPath(s"$path/key1,value1,0000000001")
            curator.create().forPath(s"$path/key2,value2,0000000002")

            Then("The table should contain all entries")
            eventually {
                table.localSnapshot shouldBe Map(
                    "key0" -> "value0","key1" -> "value1","key2" -> "value2")
            }

            table.stop()
        }

        scenario("Learned value takes precedence over persistent entry") {
            Given("A state table with ZooKeeper directory")
            val (table, path) = zkTable()
            table.start()

            When("Adding a persistent entry")
            table.addPersistent("key0", "value0")

            Then("The table should contain the learned entry")
            eventually { table.containsLocal("key0", "value0") shouldBe true }

            When("Adding a learned entry")
            table.add("key0", "value1")

            Then("The table should contain the learned entry")
            eventually { table.containsLocal("key0", "value1") shouldBe true }

            And("The store should contain nodes for both entries")
            curator.getChildren.forPath(s"$path") should contain allOf
                (s"key0,value0,${Int.MaxValue}", "key0,value1,0000000001")

            table.stop()
        }

        scenario("Learned entry takes precedence over previous version") {
            Given("A state table with ZooKeeper directory")
            val (table, path) = zkTable()
            table.start()

            When("Adding an existing learned entry")
            curator.create().forPath(s"$path/key0,value0,0000000000")

            Then("The table should contain the entry")
            eventually { table.containsLocal("key0", "value0") shouldBe true }

            When("Adding a learned entry")
            table.add("key0", "value1")

            Then("The table should contain the learned entry")
            eventually { table.containsLocal("key0", "value1") shouldBe true }

            And("The store should contain nodes for both entries")
            curator.getChildren.forPath(s"$path") should contain allOf
                (s"key0,value0,0000000000", "key0,value1,0000000001")

            table.stop()
        }

        scenario("Table loads learned entries with newer version") {
            Given("A state table with ZooKeeper directory")
            val (table, path) = zkTable()
            table.start()

            And("Adding a learned entry")
            table.add("key0", "value0")

            Then("The table should contain the learned entry")
            eventually { table.containsLocal("key0", "value0") shouldBe true }
            curator.checkExists().forPath(s"$path/key0,value0,0000000000") should not be null

            When("Adding a new learned entry")
            curator.create().forPath(s"$path/key0,value1,0000000001")

            Then("The table should load the new entry")
            eventually { table.containsLocal("key0", "value1") shouldBe true }

            And("The table should delete the obsolete entry")
            eventually {
                curator.checkExists().forPath(s"$path/key0,value0,0000000000") shouldBe null
            }

            table.stop()
        }

        scenario("Table loads learned entries over persistent entries") {
            Given("A state table with ZooKeeper directory")
            val (table, path) = zkTable()
            table.start()

            And("Adding a learned entry")
            table.addPersistent("key0", "value0")

            Then("The table should contain the learned entry")
            eventually { table.containsLocal("key0", "value0") shouldBe true }
            curator.checkExists().forPath(s"$path/key0,value0,${Int.MaxValue}") should not be null

            When("Adding a new learned entry")
            curator.create().forPath(s"$path/key0,value1,0000000001")

            Then("The table should load the new entry")
            eventually { table.containsLocal("key0", "value1") shouldBe true }

            table.stop()
        }

        scenario("Table does not delete not owned versions") {
            Given("A state table with ZooKeeper directory")
            val (table, path) = zkTable()
            table.start()

            And("Adding a learned entry")
            curator.create().forPath(s"$path/key0,value0,0000000000")

            Then("The table should contain the learned entry")
            eventually { table.containsLocal("key0", "value0") shouldBe true }
            curator.checkExists().forPath(s"$path/key0,value0,0000000000") should not be null

            When("Adding a new learned entry")
            curator.create().forPath(s"$path/key0,value1,0000000001")

            Then("The table should load the new entry")
            eventually { table.containsLocal("key0", "value1") shouldBe true }

            And("The table should not delete the obsolete entry")
            eventually {
                curator.checkExists().forPath(s"$path/key0,value0,0000000000") should not be null
            }

            table.stop()
        }

        scenario("Table handles incorrect entries") {
            Given("A state table with ZooKeeper directory")
            val (table, path) = zkTable()
            table.start()

            When("Adding an incorrect entry")
            curator.create().forPath(s"$path/no-good-entry")

            And("Adding a second control entry")
            table.add("key0", "value0")

            Then("The table should contain the good entry")
            eventually {
                table.localSnapshot shouldBe Map("key0" -> "value0")
            }

            table.stop()
        }

        scenario("Table handles invalid entries") {
            Given("A state table with ZooKeeper directory")
            val (table, path) = zkTable()
            table.start()

            When("Adding an incorrect entry")
            curator.create().forPath(s"$path/key0,value0,invalid-version")

            And("Adding a second control entry")
            table.add("key0", "value0")

            Then("The table should contain the good entry")
            eventually {
                table.localSnapshot shouldBe Map("key0" -> "value0")
            }

            table.stop()
        }

        scenario("Table allows duplicates for non-owned entries") {
            Given("A state table with ZooKeeper directory")
            val (table, path) = zkTable()
            table.start()

            When("Adding an existing learned entry")
            curator.create().forPath(s"$path/key0,value0,0000000000")

            Then("The table should contain the learned entry")
            eventually { table.containsLocal("key0", "value0") shouldBe true }

            When("The table adds a duplicate entry")
            table.add("key0", "value0")

            And("The table should add a new entry")
            eventually {
                curator.checkExists().forPath(s"$path/key0,value0,0000000001") should not be null
            }

            And("The table should contain the learned entry")
            table.containsLocal("key0", "value0") shouldBe true
        }
    }

    feature("Table handles subscriptions") {
        scenario("Subscribers receive local writes") {
            Given("A state table with ZooKeeper directory")
            val (table, _) = zkTable()

            And("A table observer")
            val observer = new TestAwaitableObserver[StateTable.Update[String, String]]

            When("The observer subscribes to the table")
            val subscription = table.observable.subscribe(observer)

            And("Adding an entry to the table")
            table.add("key0", "value0")

            Then("The observer should receive the update")
            observer.awaitOnNext(1, timeout)
            observer.getOnNextEvents.get(0) shouldBe Update[String, String](
                "key0", null, "value0")

            When("Adding another entry to the table")
            table.add("key1", "value1")

            Then("The observer should receive the update")
            observer.awaitOnNext(2, timeout)
            observer.getOnNextEvents.get(1) shouldBe Update[String, String](
                "key1", null, "value1")

            When("Updating an entry")
            table.add("key1", "value2")

            Then("The observer should receive the update")
            observer.awaitOnNext(3, timeout)
            observer.getOnNextEvents.get(2) shouldBe Update[String, String](
                "key1", "value1", "value2")

            When("Removing an entry")
            table.remove("key0", "value0")

            Then("The observer should receive the update")
            observer.awaitOnNext(4, timeout)
            observer.getOnNextEvents.get(3) shouldBe Update[String, String](
                "key0", "value0", null)

            When("The observer unsubscribes")
            subscription.unsubscribe()

            Then("The table should be stopped")
            table.isStopped shouldBe true
        }

        scenario("Subscribers receive remote writes") {
            Given("A state table with ZooKeeper directory")
            val (table, path) = zkTable()

            And("A table observer")
            val observer = new TestAwaitableObserver[StateTable.Update[String, String]]

            When("The observer subscribes to the table")
            val subscription = table.observable.subscribe(observer)

            And("Adding an entry to the table")
            curator.create().forPath(s"$path/key0,value0,0000000000")

            Then("The observer should receive the update")
            observer.awaitOnNext(1, timeout)
            observer.getOnNextEvents.get(0) shouldBe Update[String, String](
                "key0", null, "value0")

            When("Adding another entry to the table")
            curator.create().forPath(s"$path/key1,value1,0000000001")

            Then("The observer should receive the update")
            observer.awaitOnNext(2, timeout)
            observer.getOnNextEvents.get(1) shouldBe Update[String, String](
                "key1", null, "value1")

            When("Updating an entry")
            curator.create().forPath(s"$path/key1,value2,0000000002")

            Then("The observer should receive the update")
            observer.awaitOnNext(3, timeout)
            observer.getOnNextEvents.get(2) shouldBe Update[String, String](
                "key1", "value1", "value2")

            When("Removing an entry")
            curator.delete().forPath(s"$path/key0,value0,0000000000")

            Then("The observer should receive the update")
            observer.awaitOnNext(4, timeout)
            observer.getOnNextEvents.get(3) shouldBe Update[String, String](
                "key0", "value0", null)

            When("The observer unsubscribes")
            subscription.unsubscribe()

            Then("The table should be stopped")
            table.isStopped shouldBe true
        }

        scenario("Subscribers receive the table snapshot") {
            Given("A state table with ZooKeeper directory")
            val (table, _) = zkTable()
            table.start()

            And("A table observer")
            val observer1 = new TestAwaitableObserver[StateTable.Update[String, String]]

            When("Adding several entries")
            table.add("key0", "value0")
            table.add("key1", "value1")
            table.add("key2", "value2")

            And("The observer subscribes")
            val subscription1 = table.observable.subscribe(observer1)

            Then("The observer receives the current entries")
            observer1.awaitOnNext(3, timeout)

            When("Adding a new entry")
            table.add("key3", "value3")

            Then("The observer receives the new entry")
            observer1.awaitOnNext(4, timeout)
            observer1.getOnNextEvents.get(3) shouldBe Update[String, String](
                "key3", null, "value3")

            Given("A second observer")
            val observer2 = new TestAwaitableObserver[StateTable.Update[String, String]]

            When("The observer subscribes")
            val subscription2 = table.observable.subscribe(observer2)

            Then("The observer receives the current entries")
            observer2.awaitOnNext(4, timeout)

            When("Deleting an entry")
            table.remove("key0", "value0")

            Then("Both observers receive the update")
            observer1.awaitOnNext(5, timeout)
            observer1.getOnNextEvents.get(4) shouldBe Update[String, String](
                "key0", "value0", null)
            observer2.awaitOnNext(5, timeout)
            observer2.getOnNextEvents.get(4) shouldBe Update[String, String](
                "key0", "value0", null)

            When("The first observer unsubscribes")
            subscription1.unsubscribe()

            And("Removing a second entry")
            table.remove("key1", "value1")

            Then("The second observer receives the update")
            observer2.awaitOnNext(6, timeout)
            observer2.getOnNextEvents.get(5) shouldBe Update[String, String](
                "key1", "value1", null)

            And("The first observer does not receive the update")
            observer1.getOnNextEvents should have size 5

            When("The second observer unsubscribes")
            subscription2.unsubscribe()

            Then("The table is still active")
            table.localSnapshot shouldBe Map("key2" -> "value2",
                                             "key3" -> "value3")

            When("Stopping the table")
            table.stop()

            Then("The table should be stopped")
            table.isStopped shouldBe true
        }

        scenario("Subscriptions complete on delete") {
            Given("A state table with ZooKeeper directory")
            val (table, path) = zkTable()

            And("A table observer")
            val observer = new TestAwaitableObserver[StateTable.Update[String, String]]

            When("The observer subscribes to the table")
            val subscription = table.observable.subscribe(observer)

            And("Deleting the table")
            curator.delete().forPath(path)

            Then("The observer should receive an error")
            observer.awaitCompletion(timeout)
            observer.getOnCompletedEvents should have size 1

            And("The observer should be unsubscribed")
            subscription.isUnsubscribed shouldBe true

            And("The table should be stopped")
            table.isStopped shouldBe true
        }


        scenario("Table for non-existing path") {
            Given("A state table with ZooKeeper directory")
            val (table, path) = zkTable(create = false)

            And("A table observer")
            val observer = new TestAwaitableObserver[StateTable.Update[String, String]]

            When("The observer subscribes to the table")
            val subscription = table.observable.subscribe(observer)

            Then("The observer should receive an error")
            observer.awaitCompletion(timeout)
            observer.getOnCompletedEvents should have size 1

            And("The observer should be unsubscribed")
            subscription.isUnsubscribed shouldBe true

            And("The table should be stopped")
            table.isStopped shouldBe true
        }
    }
}
