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

import org.apache.zookeeper.data.Stat
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FlatSpec, GivenWhenThen, Matchers}

import rx.Observable

import org.midonet.cluster.backend.Directory
import org.midonet.cluster.backend.zookeeper.{ZkConnection, ZkDirectory}
import org.midonet.cluster.data.storage.StateTable.{Key, Update}
import org.midonet.cluster.data.storage.metrics.StorageMetrics
import org.midonet.cluster.rpc.State.ProxyResponse.Notify
import org.midonet.cluster.services.state.client.StateTableClient.ConnectionState.{ConnectionState => ProxyConnectionState}
import org.midonet.cluster.services.state.client.{StateSubscriptionKey, StateTableClient}
import org.midonet.cluster.util.CuratorTestFramework
import org.midonet.packets.MAC
import org.midonet.util.MidonetEventually
import org.midonet.util.concurrent._
import org.midonet.util.eventloop.CallingThreadReactor
import org.midonet.util.reactivex.TestAwaitableObserver

@RunWith(classOf[JUnitRunner])
class MacIdStateTableTest extends FlatSpec with GivenWhenThen with Matchers
                          with CuratorTestFramework with MidonetEventually {

    private var connection: ZkConnection = _
    private var directory: Directory = _
    private val reactor = new CallingThreadReactor
    private val timeout = 5 seconds
    private val tableKey = Key(classOf[Object], null, classOf[String],
                               classOf[String], "mac_id_table", Seq.empty)
    private val proxy = new StateTableClient {
        override def stop(): Boolean = false
        override def observable(table: StateSubscriptionKey): Observable[Notify.Update] =
            Observable.never()
        override def connection: Observable[ProxyConnectionState] =
            Observable.never()
        override def start(): Unit = { }
    }

    val metrics = new StorageMetrics(new MetricRegistry)

    protected override def setup(): Unit = {
        connection = new CuratorZkConnection(curator, reactor)
        directory = new ZkDirectory(connection, zkRoot, reactor)
    }

    protected override def teardown(): Unit = {
    }

    private def getPath(mac: MAC, id: UUID, version: Int)
    : String = {
        val formatter = new Formatter()
        formatter.format("%s/%s,%s,%010d", zkRoot, mac.toString,
                         id.toString, Int.box(version))
        formatter.toString
    }

    private def getPersistentPath(mac: MAC, id: UUID): String = {
        getPath(mac, id, Int.MaxValue)
    }

    private def hasNode(mac: MAC, id: UUID, version: Int): Boolean = {
        val stat = new Stat
        val data = curator.getData.storingStatIn(stat)
            .forPath(getPath(mac, id, version))
        stat.getEphemeralOwner != 0
    }

    private def hasPersistentNode(mac: MAC, id: UUID): Boolean = {
        val stat = new Stat
        val data = curator.getData.storingStatIn(stat)
            .forPath(getPersistentPath(mac, id))
        stat.getEphemeralOwner == 0
    }

    "State table" should "support ephemeral CRUD operations for single entry" in {
        Given("A state table")
        val table = new MacIdStateTable(tableKey, directory, proxy,
                                        Observable.never(), metrics)

        And("An observer to the table")
        val obs = new TestAwaitableObserver[Update[MAC, UUID]]
        table.observable subscribe obs

        When("Adding a MAC-ID pair to the table")
        val id1 = UUID.randomUUID()
        val mac1 = MAC.random()
        table.add(mac1, id1)

        Then("The observer should receive the update")
        obs.awaitOnNext(1, timeout)
        obs.getOnNextEvents.get(0) shouldBe Update(mac1, null, id1)

        And("The table should contain the value")
        table.getLocal(mac1) shouldBe id1

        And("ZooKeeper contains the node")
        hasNode(mac1, id1, 0) shouldBe true

        And("The table snapshot should have all entries")
        table.localSnapshot shouldBe Map(mac1 -> id1)

        When("Updating the MAC-ID pair")
        val id2 = UUID.randomUUID()
        table.add(mac1, id2)

        Then("The observer should receive the update")
        obs.awaitOnNext(2, timeout)
        obs.getOnNextEvents.get(1) shouldBe Update(mac1, id1, id2)

        And("The table should contain the value")
        table.getLocal(mac1) shouldBe id2

        And("ZooKeeper contains the node")
        hasNode(mac1, id2, 1) shouldBe true

        And("The table snapshot should have all entries")
        table.localSnapshot shouldBe Map(mac1 -> id2)

        When("Deleting the MAC-ID pair")
        eventually { table.remove(mac1) shouldBe id2 }

        Then("The observer should receive the update")
        obs.awaitOnNext(3, timeout)
        obs.getOnNextEvents.get(2) shouldBe Update(mac1, id2, null)

        And("The table should not contain the value")
        table.getLocal(mac1) shouldBe null

        And("The table snapshot should be empty")
        table.localSnapshot shouldBe empty
    }

    "State table" should "support ephemeral CRUD operations for multiple entries" in {
        Given("A state table")
        val table = new MacIdStateTable(tableKey, directory, proxy,
                                        Observable.never(), metrics)

        And("An observer to the table")
        val obs = new TestAwaitableObserver[Update[MAC, UUID]]
        table.observable subscribe obs

        When("Adding a MAC-ID pair to the table")
        val id1 = UUID.randomUUID()
        val mac1 = MAC.random()
        table.add(mac1, id1)

        Then("The observer should receive the update")
        obs.awaitOnNext(1, timeout)
        obs.getOnNextEvents.get(0) shouldBe Update(mac1, null, id1)

        And("The table should contain the value")
        table.getLocal(mac1) shouldBe id1

        And("ZooKeeper contains the node")
        hasNode(mac1, id1, 0) shouldBe true

        And("The table snapshot should have all entries")
        table.localSnapshot shouldBe Map(mac1 -> id1)

        When("Adding another MAC-ID pair to the table")
        val id2 = UUID.randomUUID()
        val mac2 = MAC.random()
        table.add(mac2, id2)

        Then("The observer should receive the update")
        obs.awaitOnNext(2, timeout)
        obs.getOnNextEvents.get(1) shouldBe Update(mac2, null, id2)

        And("The table should contain the value")
        table.getLocal(mac2) shouldBe id2

        And("ZooKeeper contains the node")
        hasNode(mac2, id2, 1) shouldBe true

        And("The table snapshot should have all entries")
        table.localSnapshot shouldBe Map(mac1 -> id1, mac2 -> id2)

        When("Adding another MAC-ID pair to the table")
        val id3 = UUID.randomUUID()
        val mac3 = MAC.random()
        table.add(mac3, id3)

        Then("The observer should receive the update")
        obs.awaitOnNext(3, timeout)
        obs.getOnNextEvents.get(2) shouldBe Update(mac3, null, id3)

        And("The table should contain the value")
        table.getLocal(mac3) shouldBe id3

        And("ZooKeeper contains the node")
        hasNode(mac3, id3, 2) shouldBe true

        And("The table snapshot should have all entries")
        table.localSnapshot shouldBe Map(mac1 -> id1, mac2 -> id2, mac3 -> id3)

        When("Updating the first MAC-ID pair")
        val id4 = UUID.randomUUID()
        table.add(mac1, id4)

        Then("The observer should receive the update")
        obs.awaitOnNext(4, timeout)
        obs.getOnNextEvents.get(3) shouldBe Update(mac1, id1, id4)

        And("The table should contain the value")
        table.getLocal(mac1) shouldBe id4

        And("ZooKeeper contains the node")
        hasNode(mac1, id4, 3) shouldBe true

        And("The table snapshot should have all entries")
        table.localSnapshot shouldBe Map(mac1 -> id4, mac2 -> id2, mac3 -> id3)

        When("Deleting the second MAC-ID pair")
        eventually { table.remove(mac2) shouldBe id2 }

        Then("The observer should receive the update")
        obs.awaitOnNext(5, timeout)
        obs.getOnNextEvents.get(4) shouldBe Update(mac2, id2, null)

        And("The table should not contain the value")
        table.localSnapshot shouldBe Map(mac1 -> id4, mac3 -> id3)
    }

    "State table" should "support persistent operations" in {
        Given("A state table")
        val table = new MacIdStateTable(tableKey, directory, proxy,
                                        Observable.never(), metrics)

        And("An observer to the table")
        val obs = new TestAwaitableObserver[Update[MAC, UUID]]
        table.observable subscribe obs

        When("Adding a MAC-ID pair to the table")
        val id1 = UUID.randomUUID()
        val mac1 = MAC.random()
        table.addPersistent(mac1, id1).await(timeout)

        Then("The observer should receive the update")
        obs.awaitOnNext(1, timeout)
        obs.getOnNextEvents.get(0) shouldBe Update(mac1, null, id1)

        And("The table should contain the value")
        table.getLocal(mac1) shouldBe id1

        And("ZooKeeper contains the node")
        hasPersistentNode(mac1, id1) shouldBe true

        And("The table snapshot should have all entries")
        table.localSnapshot shouldBe Map(mac1 -> id1)

        // TODO: Updating the persistent entry does not notify the updates,
        // TODO: because in the underlying implementation the version number
        // TODO: does not change.

        When("Deleting the first MAC-ID pair")
        table.removePersistent(mac1, id1).await(timeout) shouldBe true

        Then("The observer should receive the update")
        obs.awaitOnNext(2, timeout)
        obs.getOnNextEvents.get(1) shouldBe Update(mac1, id1, null)

        And("The table should not contain the value")
        table.getLocal(mac1) shouldBe null

        And("The table snapshot should be empty")
        table.localSnapshot shouldBe empty
    }

    "State table" should "support get by value" in {
        Given("A state table")
        val table = new MacIdStateTable(tableKey, directory, proxy,
                                        Observable.never(), metrics)
        table.start()

        When("Adding three MAC-ID pair to the table")
        val mac1 = MAC.random
        val mac2 = MAC.random
        val mac3 = MAC.random
        val mac4 = MAC.random
        val id1 = UUID.randomUUID()
        val id2 = UUID.randomUUID()
        table.add(mac1, id1)
        table.add(mac2, id2)
        table.add(mac3, id2)
        table.add(mac4, id2)

        Then("The table should contain all values")
        eventually {
            println(table.localSnapshot)
            table.localSnapshot shouldBe Map(mac1 -> id1, mac2 -> id2,
                                             mac3 -> id2, mac4 -> id2)
        }

        And("The table should return the keys by value")
        table.getLocalByValue(id1) shouldBe Set(mac1)
        table.getLocalByValue(id2) shouldBe Set(mac2, mac3, mac4)
    }
}
