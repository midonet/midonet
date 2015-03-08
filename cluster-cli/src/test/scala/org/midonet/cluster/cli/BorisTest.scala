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

package org.midonet.cluster.cli

import java.util.UUID
import java.util.concurrent.TimeUnit

import scala.collection.JavaConversions._
import scala.collection.concurrent.TrieMap
import scala.concurrent.Await
import scala.concurrent.duration.Duration

import com.google.protobuf.Descriptors.Descriptor
import com.google.protobuf.DynamicMessage
import org.apache.curator.framework.CuratorFrameworkFactory
import org.apache.curator.retry.RetryNTimes
import org.apache.curator.test.TestingServer
import org.scalatest.{FlatSpec, BeforeAndAfterAll, FeatureSpec, Matchers}

import org.midonet.cluster.cli.Boris.ResultCode
import org.midonet.cluster.data.storage.ZookeeperObjectMapper
import org.midonet.cluster.models.Commons.IPVersion
import org.midonet.cluster.models.{Commons, Topology}
import org.midonet.cluster.models.Topology.{Network, Rule, Vtep}
import org.midonet.cluster.util.UUIDUtil
import org.midonet.cluster.util.UUIDUtil.{toProto, fromProto}
import org.midonet.packets.IPv4Addr

class BorisTest extends FeatureSpec with Matchers {

    class SaneProto(val d: Descriptor) {
        val fields = d.getFields
        val fieldNames = d.getFields.map { _.getName }
        def builder = DynamicMessage.newBuilder(d)
    }

    val types = new TrieMap[String, SaneProto]
    Topology.getDescriptor.getMessageTypes.foreach { mt =>
        types += (mt.getName -> new SaneProto(mt))
    }

    feature("Boris parses Topology protobufs") {
        scenario("An invalid type fails") {
            intercept[IllegalStateException] {
                Boris.parse("Fake create fake fail").build
            }.getMessage shouldBe "Invalid type: Fake"
        }

        scenario("A network with an invalid field fails") {
            intercept[IllegalStateException] {
                Boris.parse("Network create fake fail").build
            }.getMessage shouldBe "Invalid property name: fake"
        }

        scenario("Primitives are parsed correctly") {
            val n = Boris.parse(s"Network create name test admin_state_up true " +
                                s"tunnel_key 633")
                         .build.asInstanceOf[Network]
            n.getAdminStateUp shouldBe true
            n.getName shouldBe "test"
            n.getTunnelKey shouldBe 633
        }

        scenario("UUIDs are parsed correctly") {
            val inFilter = UUID.randomUUID()
            val n = Boris.parse(s"Network create name test " +
                                s"inbound_filter_id $inFilter")
                .build.asInstanceOf[Network]
            n.getName shouldBe "test"
            n.getInboundFilterId shouldBe toProto(inFilter)
        }

        scenario("IP addresses are parsed correctly") {
            val ip = IPv4Addr.random
            val v = Boris.parse(s"Vtep create management_ip $ip")
                         .build.asInstanceOf[Vtep]
            v.getManagementIp.getAddress shouldBe ip.toString
            v.getManagementIp.getVersion shouldBe IPVersion.V4
        }

        scenario("Enums are parsed correctly") {
            val r = Boris.parse(s"Rule create conjunction_inv false " +
                                s"action REJECT")
                         .build.asInstanceOf[Rule]
            r.getConjunctionInv shouldBe false
            r.getAction shouldBe Rule.Action.REJECT
        }

        scenario("Int32Ranges are parsed correctly") {
            val r = Boris.parse(s"Rule create tp_src -10,103 tp_dst 5,44")
                         .build.asInstanceOf[Rule]
            r.getTpSrc.getStart shouldBe -10
            r.getTpSrc.getEnd shouldBe 103
            r.getTpDst.getStart shouldBe 5
            r.getTpDst.getEnd shouldBe 44
        }

        scenario("Complex nested types are parsed correctly") {
            val jumpToId = toProto(UUID.randomUUID())
            val r = Boris.parse(s"Rule create jump_rule_data jump_to " +
                                s"${fromProto(jumpToId)} jump_chain_name " +
                                s"mychain")
                         .build.asInstanceOf[Rule]
            r.getJumpRuleData.getJumpChainName shouldBe "mychain"
            r.getJumpRuleData.getJumpTo shouldBe jumpToId
        }
    }
}

class BorisPersistence extends FlatSpec
                       with Matchers
                       with BeforeAndAfterAll {

    val zkPort: Int = (Math.random() * 40000).toInt + 10000
    val zk = new TestingServer(zkPort)
    val root = s"/boris_test${UUID.randomUUID()}"
    val retry = new RetryNTimes(2, 1000)
    val curator = CuratorFrameworkFactory.newClient(s"localhost:$zkPort", retry)
    val zoom = new ZookeeperObjectMapper(root, curator)
    val atMost = Duration(2, TimeUnit.SECONDS)

    override def beforeAll(): Unit = {
        zk.start()
        curator.start()
        curator.blockUntilConnected()

        curator.create().creatingParentsIfNeeded().forPath(s"$root/1")
        curator.checkExists().forPath(s"$root/1") shouldNot be (null)

        zoom.registerClass(classOf[Network])
        zoom.build()
    }

    override def afterAll(): Unit = {
        curator.close()
        zk.close()
    }

    var n1: Network = _
    var n2: Network = _

    "Boris" should "CREATE new entities" in {
        Boris.execute("Network create name test1 admin_state_up false", zoom)
        Boris.execute("Network create name test2 admin_state_up true", zoom)
        val networks = Await.result(zoom.getAll(classOf[Network]), atMost)
        networks should have size 2
        n1 = networks.find(_.getName == "test1").orNull
        n2 = networks.find(_.getName == "test2").orNull
        n1.getAdminStateUp shouldBe false
        n2.getAdminStateUp shouldBe true
    }

    "Boris" should "GET entities" in {
        val r = Boris.execute(s"Network get id ${fromProto(n1.getId)}", zoom)
        r.entities should have size 1
        r.entities(0).asInstanceOf[Network].getId shouldBe n1.getId
        r.code shouldBe ResultCode.OK
    }

    "Boris" should "LIST entities" in {
        val r = Boris.execute("Network list", zoom)
        r.entities should have size 2
        r.code shouldBe ResultCode.OK
    }

    "Boris" should "UPDATE entities" in {
        Boris.execute(s"Network update id ${fromProto(n1.getId)} name meh " +
                      s"admin_state_up true", zoom)
        Await.result(zoom.getAll(classOf[Network]), atMost) should have size 2
        val network = Await.result(zoom.get(classOf[Network], n1.getId), atMost)
        network.getName shouldBe "meh"
        network.getAdminStateUp shouldBe true
    }

    "Boris" should "DELETE entities" in {
        Boris.execute(s"Network delete id ${fromProto(n1.getId)}", zoom)
        Await.result(zoom.getAll(classOf[Network]), atMost) should have size 1
        Boris.execute(s"Network delete id ${fromProto(n2.getId)}", zoom)
        Await.result(zoom.getAll(classOf[Network]), atMost) shouldBe empty
    }

}
