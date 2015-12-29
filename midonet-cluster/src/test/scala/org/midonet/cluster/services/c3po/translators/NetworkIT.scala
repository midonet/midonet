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
package org.midonet.cluster.services.c3po.translators

import java.util.UUID

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.C3POMinionTestBase
import org.midonet.cluster.data.neutron.NeutronResourceType.{Network => NetworkType}
import org.midonet.cluster.data.storage.CreateNodeOp
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.packets.MAC
import org.midonet.packets.util.AddressConversions._
import org.midonet.util.concurrent.toFutureOps

/**
 * An integration tests for Neutron Importer / C3PO CRUD operations on Network
 * objects.
 */
@RunWith(classOf[JUnitRunner])
class NetworkIT extends C3POMinionTestBase {
    /* Set up legacy Data Client for testing Replicated Map. */
    override protected val useLegacyDataClient = true

    "C3PO" should "execute CRUD on the Network data and its associated state " +
    "table node children" in {
        val network1Uuid = UUID.randomUUID()

        // Initially the Storage is empty.
        storage.exists(classOf[Network], network1Uuid).await() shouldBe false

        // Create a private Network
        val network1Name = "private-network"
        val network1Json = networkJson(network1Uuid, "tenant1", network1Name)
        insertCreateTask(2, NetworkType, network1Json, network1Uuid)
        val network1 = eventually(
            storage.get(classOf[Network], network1Uuid).await())

        network1.getId shouldBe toProto(network1Uuid)
        network1.getName shouldBe network1Name
        network1.getAdminStateUp shouldBe true
        eventually(getLastProcessedIdFromTable shouldBe Some(2))

        // Test the replicated map nodes, etc.
        eventually(checkReplMaps(network1Uuid, shouldExist = true))

        // Below we'll test the integration between ZOOM with ReplicatedMap:
        // Add an ARP entry node under the ARP replicated map node by
        // directly calling CreateNodeOp on Storage. The root directory for the
        // replicated ARP table has been verified to exist above, so ZOOM with
        // CreateNodeOp here will just add a child node encoding IP/MAC.
        val ipAddr1 = "10.0.0.1"
        val mac1 = "01:01:01:01:01:01"
        val ipMacPath1 = arpEntryPath(network1Uuid, ipAddr1, mac1)
        storage.multi(Seq(CreateNodeOp(ipMacPath1, null)))
        curator.checkExists.forPath(ipMacPath1) shouldNot be(null)

        // Create a legacy ReplicatedMap for the ARP table.
        val arpTable = stateTableStorage.bridgeArpTable(network1Uuid)
        arpTable shouldNot be(null)
        arpTable.start()
        eventually {
            // The ARP table should pick up the pre-seeded MAC.
            arpTable.getLocal(ipAddr1) shouldBe MAC.fromString(mac1)
        }

        // Test adding a new ARP entry to the already started Replicated Map.
        val ipAddr2 = "10.10.10.20"
        val mac2 = "02:02:02:02:02:02"
        val ipMacPath2 = arpEntryPath(network1Uuid, ipAddr2, mac2)
        storage.multi(Seq(CreateNodeOp(ipMacPath2, null)))
        curator.checkExists.forPath(ipMacPath2) shouldNot be(null)
        eventually {
            // The ARP table should pick up the new mac.
            arpTable.getLocal(ipAddr2) shouldBe MAC.fromString(mac2)
        }
        arpTable.stop()

        // Creates Network 2 and updates Network 1
        val network2Uuid = UUID.randomUUID()
        val network2Name = "corporate-network"
        val network2Json = networkJson(network2Uuid, "tenant1", network2Name)

        // Create a public Network
        val network1Name2 = "public-network"
        val network1Json2 = networkJson(network1Uuid, "tenant1", network1Name2,
                                       external = true, adminStateUp = false)

        insertCreateTask(3, NetworkType, network2Json, network2Uuid)
        insertUpdateTask(4, NetworkType, network1Json2, network1Uuid)

        val network2 = eventually(
            storage.get(classOf[Network], network2Uuid).await())
        network2.getId shouldBe toProto(network2Uuid)
        network2.getName shouldBe "corporate-network"
        eventually(checkReplMaps(network2Uuid, shouldExist = true))

        eventually {
            val network1a = storage.get(classOf[Network], network1Uuid).await()
            network1a.getId shouldBe toProto(network1Uuid)
            network1a.getName shouldBe "public-network"
            network1a.getAdminStateUp shouldBe false
            getLastProcessedIdFromTable shouldBe Some(4)
        }

        // Deletes Network 1
        insertDeleteTask(5, NetworkType, network1Uuid)
        eventually {
            storage.exists(classOf[Network], network1Uuid).await() shouldBe false
            getLastProcessedIdFromTable shouldBe Some(5)
            checkReplMaps(network1Uuid, shouldExist = false)
        }

        eventually {
            storage.exists(classOf[Network], network2Uuid).await() shouldBe true
        }
    }
}