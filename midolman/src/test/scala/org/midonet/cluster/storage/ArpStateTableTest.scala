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

package org.midonet.cluster.storage

import java.util.Formatter

import scala.concurrent.duration._

import org.apache.zookeeper.data.Stat
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FlatSpec, GivenWhenThen, Matchers}

import org.midonet.cluster.backend.Directory
import org.midonet.cluster.backend.zookeeper.{ZkConnection, ZkConnectionAwareWatcher, ZkDirectory, ZookeeperConnectionWatcher}
import org.midonet.cluster.data.storage.StateTable.Update
import org.midonet.cluster.util.CuratorTestFramework
import org.midonet.midolman.state.ArpCacheEntry
import org.midonet.packets.{IPv4Addr, MAC}
import org.midonet.util.MidonetEventually
import org.midonet.util.eventloop.CallingThreadReactor
import org.midonet.util.reactivex.TestAwaitableObserver

@RunWith(classOf[JUnitRunner])
class ArpStateTableTest extends FlatSpec with GivenWhenThen with Matchers
                        with CuratorTestFramework with MidonetEventually {

    private var connection: ZkConnection = _
    private var directory: Directory = _
    private val reactor = new CallingThreadReactor
    private var zkConnWatcher: ZkConnectionAwareWatcher = _
    private val timeout = 5 seconds

    protected override def setup(): Unit = {
        connection = new CuratorZkConnection(curator, reactor)
        directory = new ZkDirectory(connection, zkRoot, reactor)
        zkConnWatcher = new ZookeeperConnectionWatcher
    }

    private def getPath(address: IPv4Addr, entry: ArpCacheEntry, version: Int)
    : String = {
        val formatter = new Formatter()
        formatter.format("%s/%s,%s,%010d", zkRoot, address.toString,
                         entry.encode(), Int.box(version))
        formatter.toString
    }

    private def getPersistentPath(address: IPv4Addr, entry: ArpCacheEntry): String = {
        getPath(address, entry, Int.MaxValue)
    }

    private def hasNode(address: IPv4Addr, entry: ArpCacheEntry, version: Int): Boolean = {
        val stat = new Stat
        val data = curator.getData.storingStatIn(stat)
            .forPath(getPath(address, entry, version))
        stat.getEphemeralOwner != 0
    }

    private def hasPersistentNode(address: IPv4Addr, entry: ArpCacheEntry): Boolean = {
        val stat = new Stat
        val data = curator.getData.storingStatIn(stat)
            .forPath(getPersistentPath(address, entry))
        stat.getEphemeralOwner == 0
    }

    private def randomArpEntry(): ArpCacheEntry = {
        new ArpCacheEntry(MAC.random(), 0L, 0L, 0L)
    }

    "State table" should "support ephemeral CRUD operations for single entry" in {
        Given("A state table")
        val table = new ArpStateTable(directory, zkConnWatcher)

        And("An observer to the table")
        val obs = new TestAwaitableObserver[Update[IPv4Addr, ArpCacheEntry]]
        table.observable subscribe obs

        When("Adding a IP-entry pair to the table")
        val ip1 = IPv4Addr.random
        val entry1 = randomArpEntry()
        table.add(ip1, entry1)

        Then("The observer should receive the update")
        obs.awaitOnNext(1, timeout)
        obs.getOnNextEvents.get(0) shouldBe Update(ip1, null, entry1)

        And("The table should contain the value")
        table.getLocal(ip1) shouldBe entry1

        And("ZooKeeper contains the node")
        hasNode(ip1, entry1, 0) shouldBe true

        And("The table snapshot should have all entries")
        table.localSnapshot shouldBe Map(ip1 -> entry1)

        When("Updating the IP-entry pair")
        val entry2 = randomArpEntry()
        table.add(ip1, entry2)

        Then("The observer should receive the update")
        obs.awaitOnNext(2, timeout)
        obs.getOnNextEvents.get(1) shouldBe Update(ip1, entry1, entry2)

        And("The table should contain the value")
        table.getLocal(ip1) shouldBe entry2

        And("ZooKeeper contains the node")
        hasNode(ip1, entry2, 1) shouldBe true

        And("The table snapshot should have all entries")
        table.localSnapshot shouldBe Map(ip1 -> entry2)

        When("Deleting the IP-entry pair")
        eventually { table.remove(ip1) shouldBe entry2 }

        Then("The observer should receive the update")
        obs.awaitOnNext(3, timeout)
        obs.getOnNextEvents.get(2) shouldBe Update(ip1, entry2, null)

        And("The table should not contain the value")
        table.getLocal(ip1) shouldBe null

        And("The table snapshot should be empty")
        table.localSnapshot shouldBe empty
    }

    "State table" should "support ephemeral CRUD operations for multiple entries" in {
        Given("A state table")
        val table = new ArpStateTable(directory, zkConnWatcher)

        And("An observer to the table")
        val obs = new TestAwaitableObserver[Update[IPv4Addr, ArpCacheEntry]]
        table.observable subscribe obs

        When("Adding a IP-entry pair to the table")
        val ip1 = IPv4Addr.random
        val entry1 = randomArpEntry()
        table.add(ip1, entry1)

        Then("The observer should receive the update")
        obs.awaitOnNext(1, timeout)
        obs.getOnNextEvents.get(0) shouldBe Update(ip1, null, entry1)

        And("The table should contain the value")
        table.getLocal(ip1) shouldBe entry1

        And("ZooKeeper contains the node")
        hasNode(ip1, entry1, 0) shouldBe true

        And("The table snapshot should have all entries")
        table.localSnapshot shouldBe Map(ip1 -> entry1)

        When("Adding another IP-entry pair to the table")
        val ip2 = IPv4Addr.random
        val entry2 = randomArpEntry()
        table.add(ip2, entry2)

        Then("The observer should receive the update")
        obs.awaitOnNext(2, timeout)
        obs.getOnNextEvents.get(1) shouldBe Update(ip2, null, entry2)

        And("The table should contain the value")
        table.getLocal(ip2) shouldBe entry2

        And("ZooKeeper contains the node")
        hasNode(ip2, entry2, 1) shouldBe true

        And("The table snapshot should have all entries")
        table.localSnapshot shouldBe Map(ip1 -> entry1, ip2 -> entry2)

        When("Adding another IP-entry pair to the table")
        val ip3 = IPv4Addr.random
        val entry3 = randomArpEntry()
        table.add(ip3, entry3)

        Then("The observer should receive the update")
        obs.awaitOnNext(3, timeout)
        obs.getOnNextEvents.get(2) shouldBe Update(ip3, null, entry3)

        And("The table should contain the value")
        table.getLocal(ip3) shouldBe entry3

        And("ZooKeeper contains the node")
        hasNode(ip3, entry3, 2) shouldBe true

        And("The table snapshot should have all entries")
        table.localSnapshot shouldBe Map(ip1 -> entry1, ip2 -> entry2, ip3 -> entry3)

        When("Updating the first IP-entry pair")
        val entry4 = randomArpEntry()
        table.add(ip1, entry4)

        Then("The observer should receive the update")
        obs.awaitOnNext(4, timeout)
        obs.getOnNextEvents.get(3) shouldBe Update(ip1, entry1, entry4)

        And("The table should contain the value")
        table.getLocal(ip1) shouldBe entry4

        And("ZooKeeper contains the node")
        hasNode(ip1, entry4, 3) shouldBe true

        And("The table snapshot should have all entries")
        table.localSnapshot shouldBe Map(ip1 -> entry4, ip2 -> entry2, ip3 -> entry3)

        When("Deleting the secindIP-ArpCacheEntry pair")
        eventually { table.remove(ip2) shouldBe entry2 }

        Then("The observer should receive the update")
        obs.awaitOnNext(5, timeout)
        obs.getOnNextEvents.get(4) shouldBe Update(ip2, entry2, null)

        And("The table should not contain the value")
        table.localSnapshot shouldBe Map(ip1 -> entry4, ip3 -> entry3)
    }


    "State table" should "support persistent operations" in {
        Given("A state table")
        val table = new ArpStateTable(directory, zkConnWatcher)

        And("An observer to the table")
        val obs = new TestAwaitableObserver[Update[IPv4Addr, ArpCacheEntry]]
        table.observable subscribe obs

        When("Adding a IP-entry pair to the table")
        val ip1 = IPv4Addr.random
        val entry1 = randomArpEntry()
        table.addPersistent(ip1, entry1)

        Then("The observer should receive the update")
        obs.awaitOnNext(1, timeout)
        obs.getOnNextEvents.get(0) shouldBe Update(ip1, null, entry1)

        And("The table should contain the value")
        table.getLocal(ip1) shouldBe entry1

        And("ZooKeeper contains the node")
        hasPersistentNode(ip1, entry1) shouldBe true

        And("The table snapshot should have all entries")
        table.localSnapshot shouldBe Map(ip1 -> entry1)

        // TODO: Updating the persistent entry does not notify the updates,
        // TODO: because in the underlying implementation the version number
        // TODO: does not change.

        When("Deleting the first IP-ArpCacheEntry pair")
        table.removePersistent(ip1, entry1) shouldBe true

        Then("The observer should receive the update")
        obs.awaitOnNext(2, timeout)
        obs.getOnNextEvents.get(1) shouldBe Update(ip1, entry1, null)

        And("The table should not contain the value")
        table.getLocal(ip1) shouldBe null

        And("The table snapshot should be empty")
        table.localSnapshot shouldBe empty
    }

    "State table" should "support get by value" in {
        Given("A state table")
        val table = new ArpStateTable(directory, zkConnWatcher)
        table.start()

        When("Adding three IP-entry pair to the table")
        val ip1 = IPv4Addr.random
        val ip2 = IPv4Addr.random
        val ip3 = IPv4Addr.random
        val ip4 = IPv4Addr.random
        val entry1 = randomArpEntry()
        val entry2 = randomArpEntry()
        table.add(ip1, entry1)
        table.add(ip2, entry2)
        table.add(ip3, entry2)
        table.add(ip4, entry2)

        Then("The table should contain all values")
        eventually {
            println(table.localSnapshot)
            table.localSnapshot shouldBe Map(ip1 -> entry1, ip2 -> entry2,
                                             ip3 -> entry2, ip4 -> entry2)
        }

        And("The table should return the keys by value")
        table.getLocalByValue(entry1) shouldBe Set(ip1)
        table.getLocalByValue(entry2) shouldBe Set(ip2, ip3, ip4)
    }
}
