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

import scala.concurrent.duration._

import org.scalatest.{Matchers, GivenWhenThen, FlatSpec}

import org.midonet.cluster.backend.Directory
import org.midonet.cluster.backend.zookeeper.{ZkConnectionAwareWatcher, ZkConnection, ZkDirectory}
import org.midonet.cluster.data.storage.StateTable.Update
import org.midonet.cluster.util.CuratorTestFramework
import org.midonet.midolman.state.ZookeeperConnectionWatcher
import org.midonet.packets.{MAC, IPv4Addr}
import org.midonet.util.MidonetEventually
import org.midonet.util.eventloop.CallingThreadReactor
import org.midonet.util.reactivex. TestAwaitableObserver

class Ip4MacStateTableTest extends FlatSpec with GivenWhenThen with Matchers
                           with CuratorTestFramework with MidonetEventually {

    private var connection: ZkConnection = _
    private var directory: Directory = _
    private val reactor = new CallingThreadReactor
    private var zkConnWatcher: ZkConnectionAwareWatcher = _
    private val timeout = 5 seconds

    protected override def setup(): Unit = {
        connection = new CuratorZkConnection(curator, reactor)
        directory = new ZkDirectory(connection, zkRoot, null, reactor)
        zkConnWatcher = new ZookeeperConnectionWatcher
    }

    "State table" should "support ephemeral CRUD operations for single entry" in {
        Given("A state table")
        val table = new Ip4MacStateTable(directory, zkConnWatcher)

        And("An observer to the table")
        val obs = new TestAwaitableObserver[Update[IPv4Addr, MAC]]
        table.observable subscribe obs

        When("Adding a IP-MAC pair to the table")
        val ip1 = IPv4Addr.random
        val mac1 = MAC.random()
        table.add(ip1, mac1)

        Then("The observer should receive the update")
        obs.awaitOnNext(1, timeout)
        obs.getOnNextEvents.get(0) shouldBe Update(ip1, null, mac1)

        And("The table should contain the value")
        table.get(ip1) shouldBe mac1

        And("The table snapshot should have all entries")
        table.snapshot shouldBe Map(ip1 -> mac1)

        When("Updating the IP-MAC pair")
        val mac2 = MAC.random()
        table.add(ip1, mac2)

        Then("The observer should receive the update")
        obs.awaitOnNext(2, timeout)
        obs.getOnNextEvents.get(1) shouldBe Update(ip1, mac1, mac2)

        And("The table should contain the value")
        table.get(ip1) shouldBe mac2

        And("The table snapshot should have all entries")
        table.snapshot shouldBe Map(ip1 -> mac2)

        When("Deleting the IP-MAC pair")
        eventually { table.remove(ip1) shouldBe mac2 }

        Then("The observer should receive the update")
        obs.awaitOnNext(3, timeout)
        obs.getOnNextEvents.get(2) shouldBe Update(ip1, mac2, null)

        And("The table should not contain the value")
        table.get(ip1) shouldBe null

        And("The table snapshot should be empty")
        table.snapshot shouldBe empty
    }

    "State table" should "support ephemeral CRUD operations for multiple entries" in {
        Given("A state table")
        val table = new Ip4MacStateTable(directory, zkConnWatcher)

        And("An observer to the table")
        val obs = new TestAwaitableObserver[Update[IPv4Addr, MAC]]
        table.observable subscribe obs

        When("Adding a IP-MAC pair to the table")
        val ip1 = IPv4Addr.random
        val mac1 = MAC.random()
        table.add(ip1, mac1)

        Then("The observer should receive the update")
        obs.awaitOnNext(1, timeout)
        obs.getOnNextEvents.get(0) shouldBe Update(ip1, null, mac1)

        And("The table should contain the value")
        table.get(ip1) shouldBe mac1

        And("The table snapshot should have all entries")
        table.snapshot shouldBe Map(ip1 -> mac1)

        When("Adding another IP-MAC pair to the table")
        val ip2 = IPv4Addr.random
        val mac2 = MAC.random()
        table.add(ip2, mac2)

        Then("The observer should receive the update")
        obs.awaitOnNext(2, timeout)
        obs.getOnNextEvents.get(1) shouldBe Update(ip2, null, mac2)

        And("The table should contain the value")
        table.get(ip2) shouldBe mac2

        And("The table snapshot should have all entries")
        table.snapshot shouldBe Map(ip1 -> mac1, ip2 -> mac2)

        When("Adding another IP-MAC pair to the table")
        val ip3 = IPv4Addr.random
        val mac3 = MAC.random()
        table.add(ip3, mac3)

        Then("The observer should receive the update")
        obs.awaitOnNext(3, timeout)
        obs.getOnNextEvents.get(2) shouldBe Update(ip3, null, mac3)

        And("The table should contain the value")
        table.get(ip3) shouldBe mac3

        And("The table snapshot should have all entries")
        table.snapshot shouldBe Map(ip1 -> mac1, ip2 -> mac2, ip3 -> mac3)

        When("Updating the first IP-MAC pair")
        val mac4 = MAC.random()
        table.add(ip1, mac4)

        Then("The observer should receive the update")
        obs.awaitOnNext(4, timeout)
        obs.getOnNextEvents.get(3) shouldBe Update(ip1, mac1, mac4)

        And("The table should contain the value")
        table.get(ip1) shouldBe mac4

        And("The table snapshot should have all entries")
        table.snapshot shouldBe Map(ip1 -> mac4, ip2 -> mac2, ip3 -> mac3)

        When("Deleting the secindIP-MAC pair")
        eventually { table.remove(ip2) shouldBe mac2 }

        Then("The observer should receive the update")
        obs.awaitOnNext(5, timeout)
        obs.getOnNextEvents.get(4) shouldBe Update(ip2, mac2, null)

        And("The table should not contain the value")
        table.snapshot shouldBe Map(ip1 -> mac4, ip3 -> mac3)
    }


    "State table" should "support persistent operations" in {
        Given("A state table")
        val table = new Ip4MacStateTable(directory, zkConnWatcher)

        And("An observer to the table")
        val obs = new TestAwaitableObserver[Update[IPv4Addr, MAC]]
        table.observable subscribe obs

        When("Adding a IP-MAC pair to the table")
        val ip1 = IPv4Addr.random
        val mac1 = MAC.random()
        table.addPersistent(ip1, mac1)

        Then("The observer should receive the update")
        obs.awaitOnNext(1, timeout)
        obs.getOnNextEvents.get(0) shouldBe Update(ip1, null, mac1)

        And("The table should contain the value")
        table.get(ip1) shouldBe mac1

        And("The table snapshot should have all entries")
        table.snapshot shouldBe Map(ip1 -> mac1)

        // TODO: Updating the persistent entry does not notify the updates,
        // TODO: because in the underlying implementation the version number
        // TODO: does not change.

        When("Deleting the first IP-MAC pair")
        table.removePersistent(ip1, mac1) shouldBe mac1

        Then("The observer should receive the update")
        obs.awaitOnNext(2, timeout)
        obs.getOnNextEvents.get(1) shouldBe Update(ip1, mac1, null)

        And("The table should not contain the value")
        table.get(ip1) shouldBe null

        And("The table snapshot should be empty")
        table.snapshot shouldBe empty
    }
}
