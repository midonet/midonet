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

import com.codahale.metrics.MetricRegistry
import com.google.protobuf.ByteString

import ch.qos.logback.classic.{Level, Logger}
import org.apache.curator.framework.state.ConnectionState
import org.apache.curator.utils.ZKPaths
import org.apache.zookeeper.{CreateMode, KeeperException}
import org.apache.zookeeper.KeeperException.ConnectionLossException
import org.apache.zookeeper.data.Stat
import org.junit.runner.RunWith
import org.scalatest.{FeatureSpec, GivenWhenThen, Matchers}
import org.scalatest.junit.JUnitRunner

import rx.{Observable, Observer, Subscriber}
import rx.observers.TestObserver
import rx.subjects.{BehaviorSubject, PublishSubject}

import org.midonet.cluster.backend.zookeeper.ZkDirectory
import org.midonet.cluster.backend.{Directory, DirectoryCallback, MockDirectory}
import org.midonet.cluster.data.storage.StateTable.{Key, Update}
import org.midonet.cluster.data.storage.metrics.StorageMetrics
import org.midonet.cluster.rpc.State.KeyValue
import org.midonet.cluster.rpc.State.ProxyResponse.Notify
import org.midonet.cluster.services.state.client.{StateSubscriptionKey, StateTableClient}
import org.midonet.cluster.services.state.client.StateTableClient.{ConnectionState => ProxyConnectionState}
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
                        override val connection: Observable[ConnectionState],
                        override val metrics: StorageMetrics =
                            new StorageMetrics(new MetricRegistry))
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
        protected override def decodeKey(kv: KeyValue): String =
            kv.getDataVariable.toStringUtf8
        protected override def decodeValue(kv: KeyValue): String =
            kv.getDataVariable.toStringUtf8
    }

    private class TestableProxyClient extends StateTableClient {
        val updates = PublishSubject.create[Notify.Update]
        val state = BehaviorSubject.create[ProxyConnectionState.ConnectionState]
        override def stop(): Boolean = false
        override def observable(table: StateSubscriptionKey): Observable[Notify.Update] =
            updates
        override def connection: Observable[ProxyConnectionState.ConnectionState] =
            state
        override def start(): Unit = { }
    }
    private object Proxy extends TestableProxyClient

    private val reactor = new CallingThreadReactor
    private val timeout = 5 seconds

    private def mockTable(connection: Observable[ConnectionState] =
                              Observable.never(),
                          proxy: StateTableClient = Proxy)
    : (ScalableStateTable[String, String], Directory) = {
        val directory = new MockDirectory()
        (new Table(UUID.randomUUID(), directory, proxy, connection), directory)
    }

    private def zkTable(create: Boolean = true,
                        connection: Observable[ConnectionState] =
                            Observable.never(),
                        proxy: StateTableClient = Proxy)
    : (ScalableStateTable[String, String], String) = {
        val id = UUID.randomUUID()
        val path = s"$zkRoot/$id"
        if (create) {
            ZKPaths.mkdirs(curator.getZookeeperClient.getZooKeeper, path)
        }
        val directory = new ZkDirectory(curator.getZookeeperClient.getZooKeeper,
                                        path, reactor)
        (new Table(id, directory, proxy, connection), path)
    }

    private def snapshot(client: TestableProxyClient,
                         begin: Boolean, end: Boolean, version: Long,
                         entries: Map[String, (String, Int)]): Unit = {
        val update = Notify.Update.newBuilder()
            .setType(Notify.Update.Type.SNAPSHOT)
            .setBegin(begin)
            .setEnd(end)
            .setCurrentVersion(version)
        for ((key, (value, version)) <- entries) {
            update.addEntries(Notify.Entry.newBuilder()
                  .setKey(KeyValue.newBuilder().setDataVariable(
                      ByteString.copyFromUtf8(key)))
                  .setValue(KeyValue.newBuilder().setDataVariable(
                      ByteString.copyFromUtf8(value)))
                  .setVersion(version))
        }
        client.updates onNext update.build()
    }

    private def diff(client: TestableProxyClient, version: Long,
                     entries: Seq[(String, String, Int)]): Unit = {
        val update = Notify.Update.newBuilder()
            .setType(Notify.Update.Type.RELATIVE)
            .setCurrentVersion(version)
        for ((key, value, version) <- entries) {
            val entry = Notify.Entry.newBuilder()
                .setKey(KeyValue.newBuilder().setDataVariable(
                    ByteString.copyFromUtf8(key)))
            if (value ne null) {
                entry.setValue(KeyValue.newBuilder().setDataVariable(
                    ByteString.copyFromUtf8(value)))
            }
            entry.setVersion(version)
            update.addEntries(entry)
        }
        client.updates onNext update.build()
    }

    feature("Test table lifecycle") {
        scenario("Table starts and stops and counts subscriptions") {
            Given("A state table with mock directory")
            val (table, _) = mockTable()

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
            val (table, _) = mockTable()

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
            val (table, _) = mockTable()

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
            val (table, _) = mockTable()

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
            val (table, _) = mockTable(connection)

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
            val (table, _) = mockTable(connection)

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
            val (table, _) = mockTable(connection)
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

        scenario("Table should synchronize after connected") {
            Given("A connection observable")
            val connection = PublishSubject.create[ConnectionState]

            And("A state table with ZooKeeper directory")
            val (table, path) = zkTable(create = true, connection)
            table.start()

            When("The connection is suspended")
            connection onNext ConnectionState.SUSPENDED

            And("Adding a learned entry")
            curator.create().forPath(s"$path/key0,value0,0000000000")

            And("The connection is connected")
            connection onNext ConnectionState.CONNECTED

            Then("The table should contain the entry")
            eventually { table.localSnapshot shouldBe Map("key0" -> "value0") }

            When("The connection is suspended")
            connection onNext ConnectionState.SUSPENDED

            And("Adding a learned entry")
            curator.create().forPath(s"$path/key1,value1,0000000001")

            And("The connection is connected")
            connection onNext ConnectionState.RECONNECTED

            Then("The table should contain the entry")
            eventually { table.localSnapshot shouldBe Map("key0" -> "value0",
                                                          "key1" -> "value1") }

            table.stop()
        }

        scenario("Table should stop on connection completion") {
            Given("A connection observable")
            val connection = PublishSubject.create[ConnectionState]

            And("A state table")
            val (table, _) = mockTable(connection)
            table.start()

            When("The connection completes")
            connection.onCompleted()

            Then("The table is stopped")
            table.isStopped shouldBe true
        }

        scenario("Table should stop on connection error") {
            Given("A connection observable")
            val connection = PublishSubject.create[ConnectionState]

            And("A state table")
            val (table, _) = mockTable(connection)
            table.start()

            When("The connection emits an error")
            connection onError new Exception()

            Then("The table is stopped")
            table.isStopped shouldBe true
        }
    }

    feature("Table supports basic operations") {
        scenario("Table no ops if not started") {
            Given("A state table with mock directory")
            val (table, _) = mockTable()

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

            table.stop()
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

        scenario("Table does not remove non-existing entries") {
            Given("A state table with ZooKeeper directory")
            val (table, path) = zkTable()
            table.start()

            Then("Deleting a non-existing entry returns null")
            table.remove("key0") shouldBe null
            table.remove("key0", "value0") shouldBe false

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

            table.stop()
        }

        scenario("Table enqueues deletion operations") {
            Given("A connection observable")
            val connection = PublishSubject.create[ConnectionState]

            And("A state table with ZooKeeper directory")
            val (table, path) = zkTable(create = true, connection)
            table.start()

            When("Adding an entry")
            table.add("key0", "value0")

            Then("The table should contain the entry")
            eventually { table.containsLocal("key0", "value0") shouldBe true }

            And("The entry should exist in storage")
            curator.checkExists().forPath(s"$path/key0,value0,0000000000") should not be null

            When("The table disconnects and then removes a value")
            connection onNext ConnectionState.SUSPENDED
            table.remove("key0", "value0")

            And("Reconnects")
            connection onNext ConnectionState.RECONNECTED

            Then("The old entry should be deleted")
            eventually {
                curator.checkExists().forPath(s"$path/key0,value0,0000000000") shouldBe null
            }

            table.stop()
        }

        scenario("Table handles error on add") {
            Given("A state table with ZooKeeper directory")
            val (table, _) = zkTable(create = false)
            table.start()

            Then("Adding an entry for a non-existing table should not fail")
            table.add("key0", "value0")

            table.stop()
        }

        scenario("Table handles error on delete") {
            Given("A state table with ZooKeeper directory")
            val (table, path) = zkTable()
            table.start()

            When("Adding an entry")
            table.add("key0", "value0")

            Then("The table should contain the entry")
            eventually { table.containsLocal("key0", "value0") shouldBe true }

            When("Deleting the table")
            ZKPaths.deleteChildren(curator.getZookeeperClient.getZooKeeper,
                                   path, true)

            Then("Adding an entry for a non-existing table should not fail")
            table.remove("key0", "value0")

            And("The table should clear the entry")
            eventually { table.containsLocal("key0", "value0") shouldBe false }

            table.stop()
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

            table.stop()
        }

        scenario("Adding a persistent entry does not overwrite learned entry") {
            Given("A state table with ZooKeeper directory")
            val (table, _) = zkTable()
            table.start()

            When("Adding a learned entry")
            table.add("key0", "value0")

            Then("The table should contain the learned entry")
            eventually { table.containsLocal("key0", "value0") shouldBe true }

            When("Adding a persistent entry")
            table.addPersistent("key0", "value1")

            And("Adding a second control entry")
            table.add("key1", "value1")

            Then("The table should contain the control entry")
            eventually { table.containsLocal("key1", "value1") shouldBe true }

            And("The original learned entry")
            table.containsLocal("key0", "value0") shouldBe true

            table.stop()
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
            eventually { subscription.isUnsubscribed shouldBe true }

            And("The table should be stopped")
            eventually { table.isStopped shouldBe true }
        }


        scenario("Table for non-existing path") {
            Given("A state table with ZooKeeper directory")
            val (table, _) = zkTable(create = false)

            And("A table observer")
            val observer = new TestAwaitableObserver[StateTable.Update[String, String]]

            When("The observer subscribes to the table")
            val subscription = table.observable.subscribe(observer)

            Then("The observer should receive an error")
            observer.awaitCompletion(timeout)
            observer.getOnCompletedEvents should have size 1

            And("The observer should be unsubscribed")
            eventually { subscription.isUnsubscribed shouldBe true }

            And("The table should be stopped")
            eventually { table.isStopped shouldBe true }
        }

        scenario("Table handles throwing subscriber during onNext") {
            Given("A state table with ZooKeeper directory")
            val (table, _) = zkTable()

            And("Adding an entry to the table")
            table.start()
            table.add("key0", "value0")

            And("A subscriber that throws during onNext")
            val badObserver = new Subscriber[StateTable.Update[String, String]] {
                override def onNext(u: StateTable.Update[String, String]): Unit = {
                    throw new Exception()
                }
                override def onCompleted(): Unit = { }
                override def onError(e: Throwable): Unit = { }
            }

            And("A good observer")
            val observer = new TestAwaitableObserver[StateTable.Update[String, String]]

            When("The good observer subscribes to the table")
            val sub1 = table.observable.subscribe(observer)

            Then("The observer should receive the update")
            observer.awaitOnNext(1, timeout)
            observer.getOnNextEvents.get(0) shouldBe Update[String, String](
                "key0", null, "value0")

            When("The bad observer subscribes to the table")
            val sub2 = table.observable.subscribe(badObserver)

            Then("The bad observer should be unsubscribed")
            sub1.isUnsubscribed shouldBe false
            sub2.isUnsubscribed shouldBe true

            table.stop()
        }

        scenario("Table handles throwing subscriber during onCompleted") {
            Given("A connection observable")
            val connection = PublishSubject.create[ConnectionState]

            And("A state table")
            val (table, _) = mockTable(connection)
            table.start()

            And("Adding an entry to the table")
            table.start()

            And("A subscriber that throws during onCompleted")
            val badObserver = new Observer[StateTable.Update[String, String]] {
                override def onNext(u: StateTable.Update[String, String]): Unit = { }
                override def onCompleted(): Unit = {
                    throw new Exception()
                }
                override def onError(e: Throwable): Unit = { }
            }

            When("The bad observer subscribes to the table")
            table.observable.subscribe(badObserver)

            Then("Completing the connection observable should not throw")
            connection.onCompleted()

            And("The table should be stopped")
            table.isStopped shouldBe true
        }

        scenario("Table handles throwing subscriber during onError") {
            Given("A connection observable")
            val connection = PublishSubject.create[ConnectionState]

            And("A state table")
            val (table, _) = mockTable(connection)
            table.start()

            And("Adding an entry to the table")
            table.start()

            And("A subscriber that throws during onError")
            val badObserver = new Observer[StateTable.Update[String, String]] {
                override def onNext(u: StateTable.Update[String, String]): Unit = { }
                override def onCompleted(): Unit = { }
                override def onError(e: Throwable): Unit = {
                    throw new Exception(e)
                }
            }

            When("The bad observer subscribes to the table")
            table.observable.subscribe(badObserver)

            Then("Completing the connection should not throw")
            connection onNext ConnectionState.LOST

            And("The table should be stopped")
            table.isStopped shouldBe true
        }
    }

    feature("Table uses state proxy") {
        scenario("Table subscribes to state client") {
            Given("A proxy client")
            val proxy = new TestableProxyClient

            And("A state table with mock backend")
            val (table, _) = mockTable(proxy = proxy)
            table.start()

            And("The client has no observers")
            proxy.updates.hasObservers shouldBe false

            When("The proxy notifies as connected")
            proxy.state onNext ProxyConnectionState.Connected

            Then("The table should subscribe to the client")
            proxy.updates.hasObservers shouldBe true

            When("The proxy notifies as disconnected")
            proxy.state onNext ProxyConnectionState.Disconnected

            Then("The table should unsubscribe")
            proxy.updates.hasObservers shouldBe false

            table.stop()
        }

        scenario("Table should fallback to storage when proxy disconnected") {
            Given("A proxy client")
            val proxy = new TestableProxyClient

            And("A state table with mock backend")
            val (table, directory) = mockTable(proxy = proxy)
            table.start()

            When("Adding an entry to the table")
            directory.add("/key0,value0,0000000000", null, CreateMode.EPHEMERAL)

            Then("The table should contain the entry")
            table.localSnapshot shouldBe Map("key0" -> "value0")

            When("The proxy client becomes connected")
            proxy.state onNext ProxyConnectionState.Connected

            And("Adding an entry to the table")
            directory.add("/key1,value1,0000000001", null, CreateMode.EPHEMERAL)

            Then("The table should not contain the entry")
            table.localSnapshot shouldBe Map("key0" -> "value0")

            When("The proxy client becomes disconnected")
            proxy.state onNext ProxyConnectionState.Disconnected

            Then("The table should not contain the entry")
            table.localSnapshot shouldBe Map("key0" -> "value0",
                                             "key1" -> "value1")
        }

        scenario("Table updates with snapshot") {
            Given("A connected proxy client")
            val proxy = new TestableProxyClient
            proxy.state onNext ProxyConnectionState.Connected

            And("A state table with mock backend")
            val (table, _) = mockTable(proxy = proxy)
            table.start()

            When("The proxy publishes a snapshot")
            snapshot(proxy, begin = true, end = true, 0L,
                     Map("key0" -> ("value0", 0)))

            Then("The table should contain the entry")
            table.localSnapshot shouldBe Map("key0" -> "value0")
        }

        scenario("Table ignores update with lower version") {
            Given("A connected proxy client")
            val proxy = new TestableProxyClient
            proxy.state onNext ProxyConnectionState.Connected

            And("A state table with mock backend")
            val (table, _) = mockTable(proxy = proxy)
            table.start()

            When("The proxy publishes a snapshot")
            snapshot(proxy, begin = true, end = true, 1L,
                     Map("key0" -> ("value0", 0)))

            Then("The table should contain the entry")
            table.localSnapshot shouldBe Map("key0" -> "value0")

            When("The proxy publishes an older snapshot")
            snapshot(proxy, begin = true, end = true, 0L,
                     Map("key1" -> ("value1", 0)))

            Then("The table should not contain the entry")
            table.localSnapshot shouldBe Map("key0" -> "value0")
        }

        scenario("Table ignores non-begin snapshot") {
            Given("A connected proxy client")
            val proxy = new TestableProxyClient
            proxy.state onNext ProxyConnectionState.Connected

            And("A state table with mock backend")
            val (table, _) = mockTable(proxy = proxy)
            table.start()

            When("The proxy publishes a snapshot")
            snapshot(proxy, begin = false, end = false, 1L,
                     Map("key0" -> ("value0", 0)))

            Then("The table should be empty")
            table.localSnapshot shouldBe empty
        }

        scenario("Table does not update the cache until end is true") {
            Given("A connected proxy client")
            val proxy = new TestableProxyClient
            proxy.state onNext ProxyConnectionState.Connected

            And("A state table with mock backend")
            val (table, _) = mockTable(proxy = proxy)
            table.start()

            When("The proxy publishes the snapshot begin")
            snapshot(proxy, begin = true, end = false, 1L,
                     Map("key0" -> ("value0", 0)))

            Then("The table cache should be empty")
            table.localSnapshot shouldBe empty

            When("The proxy publishes the snapshot middle")
            snapshot(proxy, begin = false, end = false, 1L,
                     Map("key1" -> ("value1", 1)))

            Then("The table cache should be empty")
            table.localSnapshot shouldBe empty

            When("The proxy publishes the snapshot end")
            snapshot(proxy, begin = false, end = true, 1L,
                     Map("key2" -> ("value2", 2)))

            Then("The table cache should contain all entries")
            table.localSnapshot shouldBe Map("key0" -> "value0",
                                             "key1" -> "value1",
                                             "key2" -> "value2")
        }

        scenario("Table handles relative update") {
            Given("A connected proxy client")
            val proxy = new TestableProxyClient
            proxy.state onNext ProxyConnectionState.Connected

            And("A state table with mock backend")
            val (table, _) = mockTable(proxy = proxy)
            table.start()

            When("The proxy publishes a diff that adds an entry")
            diff(proxy, 1L, Seq(("key0", "value0", 0)))

            Then("The table contains the entry")
            table.localSnapshot shouldBe Map("key0" -> "value0")

            When("The proxy publishes another diff for the same key")
            diff(proxy, 1L, Seq(("key0", "value1", 1)))

            Then("The table contains the entry")
            table.localSnapshot shouldBe Map("key0" -> "value1")

            When("The proxy publishes a diff that removes an entry")
            diff(proxy, 1L, Seq(("key0", null, 1)))

            Then("The table snapshot should be empty")
            table.localSnapshot shouldBe empty
        }

        scenario("Table handles multiple relative updates") {
            Given("A connected proxy client")
            val proxy = new TestableProxyClient
            proxy.state onNext ProxyConnectionState.Connected

            And("A state table with mock backend")
            val (table, _) = mockTable(proxy = proxy)
            table.start()

            When("The proxy publishes a diff that adds an entry")
            diff(proxy, 1L, Seq(("key0", "value0", 0)))

            Then("The table contains the entry")
            table.localSnapshot shouldBe Map("key0" -> "value0")

            And("The proxy publishes a diff that adds another entry")
            diff(proxy, 1L, Seq(("key1", "value1", 1)))

            Then("The table contains the entry")
            table.localSnapshot shouldBe Map("key0" -> "value0",
                                             "key1" -> "value1")
        }

        scenario("Table removes owned entry for different value") {
            Given("A connected proxy client")
            val proxy = new TestableProxyClient
            proxy.state onNext ProxyConnectionState.Connected

            And("A state table with mock backend")
            val (table, directory) = mockTable(proxy = proxy)
            table.start()

            When("The table adds an entry")
            table.add("key0", "value0")

            Then("The table contains the entry")
            table.localSnapshot shouldBe Map("key0" -> "value0")

            And("The directory contains the entry")
            directory.exists("/key0,value0,0000000000") shouldBe true

            When("The proxy publishes a diff for another entry")
            diff(proxy, 1L, Seq(("key0", "value1", 1)))

            Then("The table contains the entry")
            table.localSnapshot shouldBe Map("key0" -> "value1")

            And("The directory does not contains the entry")
            directory.exists("/key0,value0,0000000000") shouldBe false
        }

        scenario("Table removes owned entry for different version") {
            Given("A connected proxy client")
            val proxy = new TestableProxyClient
            proxy.state onNext ProxyConnectionState.Connected

            And("A state table with mock backend")
            val (table, directory) = mockTable(proxy = proxy)
            table.start()

            When("The table adds an entry")
            table.add("key0", "value0")

            Then("The table contains the entry")
            table.localSnapshot shouldBe Map("key0" -> "value0")

            And("The directory contains the entry")
            directory.exists("/key0,value0,0000000000") shouldBe true

            When("The proxy publishes a diff for another entry")
            diff(proxy, 1L, Seq(("key0", "value0", 1)))

            Then("The table contains the entry")
            table.localSnapshot shouldBe Map("key0" -> "value0")

            And("The directory does not contains the entry")
            directory.exists("/key0,value0,0000000000") shouldBe false
        }

        scenario("Table removes owned entry for deleted version") {
            Given("A connected proxy client")
            val proxy = new TestableProxyClient
            proxy.state onNext ProxyConnectionState.Connected

            And("A state table with mock backend")
            val (table, directory) = mockTable(proxy = proxy)
            table.start()

            When("The table adds an entry")
            table.add("key0", "value0")

            Then("The table contains the entry")
            table.localSnapshot shouldBe Map("key0" -> "value0")

            And("The directory contains the entry")
            directory.exists("/key0,value0,0000000000") shouldBe true

            When("The proxy publishes a diff for another entry")
            diff(proxy, 1L, Seq(("key0", null, 1)))

            Then("The table contains the entry")
            table.localSnapshot shouldBe empty

            And("The directory does not contains the entry")
            directory.exists("/key0,value0,0000000000") shouldBe false
        }

        scenario("Table ignores inconsistent relative update") {
            Given("A connected proxy client")
            val proxy = new TestableProxyClient
            proxy.state onNext ProxyConnectionState.Connected

            And("A state table with mock backend")
            val (table, _) = mockTable(proxy = proxy)
            table.start()

            When("The table adds an entry")
            table.add("key0", "value0")

            And("The proxy publishes a diff that removes a non-existing entry")
            diff(proxy, 1L, Seq(("key1", null, 1)))

            Then("The table contains the entry")
            table.localSnapshot shouldBe Map("key0" -> "value0")
        }

        scenario("Table handles missing update type") {
            Given("A connected proxy client")
            val proxy = new TestableProxyClient
            proxy.state onNext ProxyConnectionState.Connected

            And("A state table with mock backend")
            val (table, _) = mockTable(proxy = proxy)
            table.start()

            Then("The table handles an unknown update")
            proxy.updates onNext Notify.Update.newBuilder().build()
        }

        scenario("Table handles missing update version") {
            Given("A connected proxy client")
            val proxy = new TestableProxyClient
            proxy.state onNext ProxyConnectionState.Connected

            And("A state table with mock backend")
            val (table, _) = mockTable(proxy = proxy)
            table.start()

            Then("The table handles an unknown update")
            proxy.updates onNext Notify.Update.newBuilder()
                .setType(Notify.Update.Type.RELATIVE).build()
        }

        scenario("Table ignores stale entries in updates") {
            Given("A connected proxy client")
            val proxy = new TestableProxyClient
            proxy.state onNext ProxyConnectionState.Connected

            And("A state table with mock backend")
            val (table, directory) = mockTable(proxy = proxy)
            table.start()

            When("The table adds an entry")
            table.add("key0", "value0")

            Then("The table contains the entry")
            table.localSnapshot shouldBe Map("key0" -> "value0")

            And("The directory contains the entry")
            directory.exists("/key0,value0,0000000000") shouldBe true

            When("The table updates the entry")
            table.add("key0", "value1")

            Then("The table contains the updated entry")
            table.localSnapshot shouldBe Map("key0" -> "value1")

            And("The directory contains the entry")
            directory.exists("/key0,value1,0000000001") shouldBe true

            When("The proxy publishes a diff for the old value")
            diff(proxy, 1L, Seq(("key0", "value0", 0)))

            Then("The table keeps the newer value")
            table.localSnapshot shouldBe Map("key0" -> "value1")

            And("The directory does not contains the old entry")
            directory.exists("/key0,value0,0000000000") shouldBe false

            And("The directory does contains the new entry")
            directory.exists("/key0,value1,0000000001") shouldBe true
        }

        scenario("Table ignores stale entries in snapshots") {
            Given("A connected proxy client")
            val proxy = new TestableProxyClient
            proxy.state onNext ProxyConnectionState.Connected

            And("A state table with mock backend")
            val (table, directory) = mockTable(proxy = proxy)
            table.start()

            When("The table adds an entry")
            table.add("key0", "value0")

            Then("The table contains the entry")
            table.localSnapshot shouldBe Map("key0" -> "value0")

            And("The directory contains the entry")
            directory.exists("/key0,value0,0000000000") shouldBe true

            When("The table updates the entry")
            table.add("key0", "value1")

            Then("The table contains the updated entry")
            table.localSnapshot shouldBe Map("key0" -> "value1")

            And("The directory contains the entry")
            directory.exists("/key0,value1,0000000001") shouldBe true

            When("The proxy publishes a diff for the old value")
            snapshot(proxy, true, true, 1L, Map(("key0" -> ("value0", 0))))

            Then("The table keeps the newer value")
            table.localSnapshot shouldBe Map("key0" -> "value1")

            And("The directory does not contains the old entry")
            directory.exists("/key0,value0,0000000000") shouldBe false

            And("The directory does contains the new entry")
            directory.exists("/key0,value1,0000000001") shouldBe true
        }

        scenario("Table doesn't send duplicate remove requests") {
            Given("A connected proxy client")
            val proxy = new TestableProxyClient
            proxy.state onNext ProxyConnectionState.Connected

            And("A state table with mock backend and special directory")

            val directory = new MockDirectory {

                var deletesSent = 0
                val dummyCallback = new DirectoryCallback[Void] {
                    override def onSuccess(data: Void,
                                           stat: Stat,
                                           context: scala.Any): Unit = { }

                    override def onError(e: KeeperException,
                                         context: scala.Any): Unit = { }
                }

                override def asyncDelete(relativePath: String,
                                         version: Int,
                                         callback: DirectoryCallback[Void],
                                         context: scala.Any): Unit = {
                    deletesSent += 1
                    super.asyncDelete(relativePath,
                                      version,
                                      dummyCallback,
                                      context)
                }
            }

            val table = new Table(UUID.randomUUID(), directory, proxy,
                                  Observable.never())

            table.start()

            When("The table adds an entry")
            table.add("key0", "value0")

            Then("The table contains the entry")
            table.localSnapshot shouldBe Map("key0" -> "value0")

            And("The directory contains the entry")
            directory.exists("/key0,value0,0000000000") shouldBe true

            When("The table removes the entry but the confirmation has not arrived")
            table.remove("key0", "value0") shouldBe true
            directory.deletesSent shouldBe 1

            Then("The table still contains the updated entry")
            table.localSnapshot shouldBe Map("key0" -> "value0")

            And("The directory has already removed the entry")
            directory.exists("/key0,value0,0000000000") shouldBe false

            When("The proxy publishes a diff for the removed entry")
            diff(proxy, 1L, Seq(("key0", null, 1)))

            Then("The table does not schedule another removal")
            directory.deletesSent shouldBe 1
        }

    }

    feature("Table publishes ready notifications") {
        scenario("Table starts on ready subscriptions") {
            Given("A state table with mock directory")
            val (table, _) = mockTable()

            And("A ready observer")
            val observer1 = new TestObserver[StateTable.Key]()

            Then("The table should not be ready")
            table.isReady shouldBe false

            When("The observer subscribes to the ready observable")
            table.ready.subscribe(observer1)

            Then("The observer should receive a ready notification")
            observer1.getOnNextEvents should contain only table.tableKey

            And("The table should be ready")
            table.isReady shouldBe true

            When("A new ready observer subscribes")
            val observer2 = new TestObserver[StateTable.Key]()
            table.ready.subscribe(observer2)

            Then("The first observer should not see any new notifications")
            observer1.getOnNextEvents should contain only table.tableKey

            And("The second observer should receive a ready notification")
            observer2.getOnNextEvents should contain only table.tableKey
        }

        scenario("Table does not complete on unsubscribe") {
            Given("A state table with mock directory")
            val (table, _) = mockTable()

            And("A ready observer")
            val observer = new TestObserver[StateTable.Key]()

            Then("The table should not be stopped")
            table.isStopped shouldBe true

            When("The observer subscribes to the ready observable")
            val subscription = table.ready.subscribe(observer)

            Then("The table should be running")
            table.isStopped shouldBe false

            When("The ready observer unsubscribes")
            subscription.unsubscribe()

            Then("The table should be running")
            table.isStopped shouldBe false
        }

        scenario("Ready observables should complete on stop") {
            Given("A state table with mock directory")
            val (table, _) = mockTable()

            And("A ready observer")
            val observer = new TestObserver[StateTable.Key]()

            When("The observer subscribes to the ready observable")
            val subscription = table.ready.subscribe(observer)

            And("The table stops")
            table.stop()

            Then("The observer should receive onCompleted")
            observer.getOnCompletedEvents should have size 1

            And("The observer should be unsubscribed")
            subscription.isUnsubscribed shouldBe true
        }
    }
}
