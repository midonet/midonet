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

import com.codahale.metrics.MetricRegistry

import org.junit.Test
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{Matchers, Suite}

import org.midonet.cluster.data.storage.metrics.StorageMetrics
import org.midonet.cluster.util.MidonetBackendTest

@RunWith(classOf[JUnitRunner])
class ZoomNodeTests extends Suite with MidonetBackendTest with Matchers {
    private var zom: ZookeeperObjectMapper = _

    override protected val zkRoot = "/maps"

    private val Bridge1Path = "/maps/bridge/1"

    override protected def setup(): Unit = {
        zom = new ZookeeperObjectMapper(config, "host", curator, curator,
                                        stateTables, reactor,
                                        new StorageMetrics(new MetricRegistry))
        zom.build()
    }

    @Test
    def testCreateNode(): Unit = {
        zom.multi(List(CreateNodeOp(Bridge1Path, null)))
        zom.getNodeValue(Bridge1Path) should be(null)
    }

    @Test
    def testCreateChildNodes(): Unit = {
        createNodes(Map(Bridge1Path -> null,
                        Bridge1Path + "/key1" -> "val1",
                        Bridge1Path + "/key2" -> null))
        val keys = zom.getNodeChildren(Bridge1Path)
        keys should contain only("key1", "key2")
        zom.getNodeValue(Bridge1Path + "/key1") shouldBe "val1"
        zom.getNodeValue(Bridge1Path + "/key2") shouldBe null
    }

    @Test
    def testUpdateNodeValues(): Unit = {
        createNodes(Map(Bridge1Path -> null,
                        Bridge1Path + "/key1" -> "val1",
                        Bridge1Path + "/key2" -> null))
        zom.multi(List(UpdateNodeOp(Bridge1Path + "/key1", null),
                       UpdateNodeOp(Bridge1Path + "/key2", "val2")))
        zom.getNodeValue(Bridge1Path + "/key1") shouldBe null
        zom.getNodeValue(Bridge1Path + "/key2") shouldBe "val2"
    }

    @Test
    def testDeleteEntries(): Unit = {
        createNodes(Map(Bridge1Path -> null,
                        Bridge1Path + "/key1" -> "val1",
                        Bridge1Path + "/key2" -> null))
        zom.multi(List(DeleteNodeOp(Bridge1Path + "/key1")))
        zom.getNodeChildren(Bridge1Path) should contain only "key2"
    }

    @Test
    def testRecursiveDelete(): Unit = {
        createNodes(Map(Bridge1Path -> null,
                        Bridge1Path + "/key1" -> "val1",
                        Bridge1Path + "/key2" -> null))
        zom.multi(List(DeleteNodeOp(Bridge1Path)))
        curator.checkExists().forPath(Bridge1Path) shouldBe null
    }

    @Test
    def testCreateExistingNode(): Unit = {
        createNodes(Map(Bridge1Path -> null))
        val ex = the [StorageNodeExistsException] thrownBy
                 createNodes(Map(Bridge1Path -> null))
        ex.path shouldBe Bridge1Path
    }

    @Test
    def testUpdateNonExistingNode(): Unit = {
        createNodes(Map(Bridge1Path + "/key" -> "value"))
        val ex = the [StorageNodeNotFoundException] thrownBy
                 zom.multi(List(UpdateNodeOp(Bridge1Path + "/key2", "val2")))
        ex.path shouldBe Bridge1Path + "/key2"
    }

    @Test
    def testBridgeMaps(): Unit = {
        createNodes(Map(Bridge1Path + "/ARP/1.2.3.4,mac1" -> null,
                        Bridge1Path + "/ARP/1.2.3.5,mac2" -> null,
                        Bridge1Path + "/mac_ports/mac1,port1" -> null,
                        Bridge1Path + "/mac_ports/mac2,port2" -> null,
                        Bridge1Path + "/mac_ports/vlans/1/mac1,port3" -> null,
                        Bridge1Path + "/mac_ports/vlans/1/mac2,port4" -> null))
        zom.multi(List(DeleteNodeOp(Bridge1Path + "/ARP/1.2.3.5,mac2"),
                       CreateNodeOp(Bridge1Path + "/ARP/1.2.3.5,mac3", null)))
        zom.getNodeChildren(Bridge1Path + "/ARP") should contain only
            ("1.2.3.4,mac1", "1.2.3.5,mac3")

        zom.multi(List(DeleteNodeOp(Bridge1Path + "/mac_ports/vlans/1")))
        zom.getNodeChildren(Bridge1Path + "/mac_ports/vlans") shouldBe empty

        createNodes(Map(Bridge1Path + "/mac_ports/vlans/2/mac5,port5" -> null,
                        Bridge1Path + "/mac_ports/vlans/2/mac6,port6" -> null))
        zom.getNodeChildren(Bridge1Path + "/mac_ports/vlans") should
            contain only "2"
        zom.getNodeChildren(Bridge1Path + "/mac_ports/vlans/2") should
            contain only("mac5,port5", "mac6,port6")

        zom.multi(List(DeleteNodeOp(Bridge1Path)))
        zom.getNodeChildren("/maps/bridge") shouldBe empty
    }

    private def createNodes(nodes: Map[String, String]): Unit = {
        zom.multi(nodes.toList.map{case (k, v) => CreateNodeOp(k, v)})
    }

}
