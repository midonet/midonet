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

package org.midonet.cluster.storage

import java.util.{Formatter, UUID}

import scala.concurrent.duration._

import com.codahale.metrics.MetricRegistry
import com.google.protobuf.ByteString

import org.apache.zookeeper.data.Stat
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FlatSpec, GivenWhenThen, Matchers}

import rx.Observable

import org.midonet.cluster.backend.Directory
import org.midonet.cluster.backend.zookeeper.{ZkConnection, ZkDirectory}
import org.midonet.cluster.data.storage.StateTable.{Key, Update}
import org.midonet.cluster.data.storage.StateTableEncoder.Fip64Encoder
import org.midonet.cluster.data.storage.StateTableEncoder.Fip64Encoder.DefaultValue
import org.midonet.cluster.data.storage.metrics.StorageMetrics
import org.midonet.cluster.data.storage.model.{Fip64Entry => Entry}
import org.midonet.cluster.rpc.State.KeyValue
import org.midonet.cluster.rpc.State.ProxyResponse.Notify
import org.midonet.cluster.services.state.client.StateTableClient.ConnectionState.{ConnectionState => ProxyConnectionState}
import org.midonet.cluster.services.state.client.{StateSubscriptionKey, StateTableClient}
import org.midonet.cluster.util.CuratorTestFramework
import org.midonet.packets.{IPv4Addr, IPv4Subnet, IPv6Addr, IPv6Subnet}
import org.midonet.util.MidonetEventually
import org.midonet.util.concurrent._
import org.midonet.util.eventloop.CallingThreadReactor
import org.midonet.util.reactivex.TestAwaitableObserver

@RunWith(classOf[JUnitRunner])
class Fip64StateTableTest extends FlatSpec with GivenWhenThen with Matchers
                          with CuratorTestFramework with MidonetEventually {

    private var connection: ZkConnection = _
    private var directory: Directory = _
    private val reactor = new CallingThreadReactor
    private val timeout = 5 seconds
    private val tableKey = Key(classOf[Object], null, classOf[String],
                               classOf[String], "arp_table", Seq.empty)
    private val proxy = new StateTableClient {
        override def stop(): Boolean = false
        override def observable(table: StateSubscriptionKey): Observable[Notify.Update] =
            Observable.never()
        override def connection: Observable[ProxyConnectionState] =
            Observable.never()
        override def start(): Unit = { }
    }

    private val metrics = new StorageMetrics(new MetricRegistry)

    protected override def setup(): Unit = {
        connection = new CuratorZkConnection(curator, reactor)
        directory = new ZkDirectory(connection, zkRoot, reactor)
    }

    private def getPath(entry: Entry, version: Int): String = {
        val formatter = new Formatter()
        formatter.format("%s/%s,0,%010d", zkRoot, entry.encode, Int.box(version))
        formatter.toString
    }

    private def getPersistentPath(entry: Entry): String = {
        getPath(entry, Int.MaxValue)
    }

    private def hasNode(entry: Entry, version: Int): Boolean = {
        val stat = new Stat
        curator.getData.storingStatIn(stat)
            .forPath(getPath(entry, version))
        stat.getEphemeralOwner != 0
    }

    private def hasPersistentNode(entry: Entry): Boolean = {
        val stat = new Stat
        curator.getData.storingStatIn(stat)
            .forPath(getPersistentPath(entry))
        stat.getEphemeralOwner == 0
    }

    private def randomEntry(): Entry = {
        new Entry(new IPv4Subnet(IPv4Addr.random, 24),
                  new IPv6Subnet(IPv6Addr.random, 64),
                  IPv4Subnet.fromCidr("192.168.0.0/16"),
                  UUID.randomUUID())
    }

    "State table" should "support ephemeral CRUD operations for single entry" in {
        Given("A state table")
        val table = new Fip64StateTable(tableKey, directory, proxy,
                                        Observable.never(), metrics)

        And("An observer to the table")
        val obs = new TestAwaitableObserver[Update[Entry, AnyRef]]
        table.observable subscribe obs

        When("Adding an entry to the table")
        val entry1 = randomEntry()
        table.add(entry1, DefaultValue)

        Then("The observer should receive the update")
        obs.awaitOnNext(1, timeout)
        obs.getOnNextEvents.get(0) shouldBe Update(entry1, null, DefaultValue)

        And("The table should contain the value")
        table.getLocal(entry1) shouldBe DefaultValue

        And("ZooKeeper contains the node")
        hasNode(entry1, 0) shouldBe true

        And("The table snapshot should have all entries")
        table.localSnapshot shouldBe Map(entry1 -> DefaultValue)

        When("Deleting the entry")
        eventually { table.remove(entry1) shouldBe DefaultValue }

        Then("The observer should receive the update")
        obs.awaitOnNext(2, timeout)
        obs.getOnNextEvents.get(1) shouldBe Update(entry1, DefaultValue, null)

        And("The table should not contain the value")
        table.getLocal(entry1) shouldBe null

        And("The table snapshot should be empty")
        table.localSnapshot shouldBe empty
    }

    "State table" should "support ephemeral CRUD operations for multiple entries" in {
        Given("A state table")
        val table = new Fip64StateTable(tableKey, directory, proxy,
                                       Observable.never(), metrics)

        And("An observer to the table")
        val obs = new TestAwaitableObserver[Update[Entry, AnyRef]]
        table.observable subscribe obs

        When("Adding an entry to the table")
        val entry1 = randomEntry()
        table.add(entry1, DefaultValue)

        Then("The observer should receive the update")
        obs.awaitOnNext(1, timeout)
        obs.getOnNextEvents.get(0) shouldBe Update(entry1, null, DefaultValue)

        And("The table should contain the value")
        table.getLocal(entry1) shouldBe DefaultValue

        And("ZooKeeper contains the node")
        hasNode(entry1, 0) shouldBe true

        And("The table snapshot should have all entries")
        table.localSnapshot shouldBe Map(entry1 -> DefaultValue)

        When("Adding another entry to the table")
        val entry2 = randomEntry()
        table.add(entry2, DefaultValue)

        Then("The observer should receive the update")
        obs.awaitOnNext(2, timeout)
        obs.getOnNextEvents.get(1) shouldBe Update(entry2, null, DefaultValue)

        And("The table should contain the value")
        table.getLocal(entry2) shouldBe DefaultValue

        And("ZooKeeper contains the node")
        hasNode(entry2, 1) shouldBe true

        And("The table snapshot should have all entries")
        table.localSnapshot shouldBe Map(entry1 -> DefaultValue,
                                         entry2 -> DefaultValue)

        When("Adding another entry to the table")
        val entry3 = randomEntry()
        table.add(entry3, DefaultValue)

        Then("The observer should receive the update")
        obs.awaitOnNext(3, timeout)
        obs.getOnNextEvents.get(2) shouldBe Update(entry3, null, DefaultValue)

        And("The table should contain the value")
        table.getLocal(entry3) shouldBe DefaultValue

        And("ZooKeeper contains the node")
        hasNode(entry3, 2) shouldBe true

        And("The table snapshot should have all entries")
        table.localSnapshot shouldBe Map(entry1 -> DefaultValue,
                                         entry2 -> DefaultValue,
                                         entry3 -> DefaultValue)

        When("Deleting the second entry")
        eventually { table.remove(entry2) shouldBe DefaultValue }

        Then("The observer should receive the update")
        obs.awaitOnNext(4, timeout)
        obs.getOnNextEvents.get(3) shouldBe Update(entry2, DefaultValue, null)

        And("The table should not contain the value")
        table.localSnapshot shouldBe Map(entry1 -> DefaultValue,
                                         entry3 -> DefaultValue)
    }

    "State table" should "support persistent operations" in {
        Given("A state table")
        val table = new Fip64StateTable(tableKey, directory, proxy,
                                        Observable.never(), metrics)

        And("An observer to the table")
        val obs = new TestAwaitableObserver[Update[Entry, AnyRef]]
        table.observable subscribe obs

        When("Adding an entry to the table")
        val entry1 = randomEntry()
        table.addPersistent(entry1, DefaultValue).await(timeout)

        Then("The observer should receive the update")
        obs.awaitOnNext(1, timeout)
        obs.getOnNextEvents.get(0) shouldBe Update(entry1, null, DefaultValue)

        And("The table should contain the value")
        table.getLocal(entry1) shouldBe DefaultValue

        And("ZooKeeper contains the node")
        hasPersistentNode(entry1) shouldBe true

        And("The table snapshot should have all entries")
        table.localSnapshot shouldBe Map(entry1 -> DefaultValue)

        // TODO: Updating the persistent entry does not notify the updates,
        // TODO: because in the underlying implementation the version number
        // TODO: does not change.

        When("Deleting the entry")
        table.removePersistent(entry1, DefaultValue).await(timeout) shouldBe true

        Then("The observer should receive the update")
        obs.awaitOnNext(2, timeout)
        obs.getOnNextEvents.get(1) shouldBe Update(entry1, DefaultValue, null)

        And("The table should not contain the value")
        table.getLocal(entry1) shouldBe null

        And("The table snapshot should be empty")
        table.localSnapshot shouldBe empty
    }

    "State table" should "support get by value" in {
        Given("A state table")
        val table = new Fip64StateTable(tableKey, directory, proxy,
                                        Observable.never(), metrics)
        table.start()

        When("Adding four entries to the table")
        val entry1 = randomEntry()
        val entry2 = randomEntry()
        val entry3 = randomEntry()
        val entry4 = randomEntry()
        table.add(entry1, DefaultValue)
        table.add(entry2, DefaultValue)
        table.add(entry3, DefaultValue)
        table.add(entry4, DefaultValue)

        Then("The table should contain all values")
        eventually {
            println(table.localSnapshot)
            table.localSnapshot shouldBe Map(entry1 -> DefaultValue,
                                             entry2 -> DefaultValue,
                                             entry3 -> DefaultValue,
                                             entry4 -> DefaultValue)
        }

        And("The table should return the keys by value")
        table.getLocalByValue(DefaultValue) shouldBe Set(entry1, entry2, entry3,
                                                         entry4)
    }

    "Encoder" should "decode key and values" in {
        Given("A FIP64 encoder")
        val encoder = new Fip64Encoder {
            def keyOf(kv: KeyValue): Entry = decodeKey(kv)
            def valueOf(kv: KeyValue): AnyRef = decodeValue(kv)
        }

        And("An entry")
        val entry = randomEntry()
        val key = KeyValue.newBuilder().setDataVariable(
            ByteString.copyFromUtf8(entry.encode)).build()

        Then("The decoder decodes the key")
        encoder.keyOf(key) shouldBe entry

        And("The decoder decodes the value")
        encoder.valueOf(null) shouldBe DefaultValue
    }

}
