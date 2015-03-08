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
import scala.concurrent.Await
import scala.concurrent.duration.Duration

import org.apache.curator.framework.CuratorFrameworkFactory
import org.apache.curator.retry.RetryNTimes
import org.apache.curator.test.TestingServer
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}

import org.midonet.cluster.data.storage.FieldBinding.DeleteAction
import org.midonet.cluster.data.storage.ZookeeperObjectMapper
import org.midonet.cluster.models.Commons.IPVersion
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.util.UUIDUtil.{fromProto, toProto}
import org.midonet.packets.IPv4Addr


@RunWith(classOf[JUnitRunner])
class BorisTest extends FlatSpec with Matchers
                                 with BeforeAndAfterAll {

    val zkPort: Int = (Math.random() * 40000).toInt + 10000
    val zk = new TestingServer(zkPort)
    val root = s"/boris_test${UUID.randomUUID()}"
    val retry = new RetryNTimes(2, 1000)
    val curator = CuratorFrameworkFactory.newClient(s"localhost:$zkPort",
                                                    retry)
    val zoom = new ZookeeperObjectMapper(root, curator)
    val atMost = Duration(2, TimeUnit.SECONDS)

    var boris = new Boris(zoom)

    override def beforeAll(): Unit = {
        zk.start()
        curator.start()
        curator.blockUntilConnected()

        curator.create().creatingParentsIfNeeded().forPath(s"$root/1")
        curator.checkExists().forPath(s"$root/1") shouldNot be(null)

        // We use 2 entities for our tests
        // TODO: remove this, it should use the MN backend service
        zoom.registerClass(classOf[Network])
        zoom.registerClass(classOf[Port])
        zoom.registerClass(classOf[Router])
        zoom.declareBinding(classOf[Network], "port_ids", DeleteAction.ERROR,
                            classOf[Port], "network_id", DeleteAction.CLEAR)
        zoom.declareBinding(classOf[Router], "port_ids", DeleteAction.ERROR,
                            classOf[Port], "router_id", DeleteAction.CLEAR)
        zoom.build()
    }

    override def afterAll(): Unit = {
        curator.close()
        zk.close()
    }

    var n1: Network = _
    var n2: Network = _

    "Boris" should "parse IPv4Addr correctly" in {
        val ip = IPv4Addr.random
        val v = boris.parse(s"Vtep create management_ip $ip")
                     .build.asInstanceOf[Vtep]
        v.getManagementIp.getAddress shouldBe ip.toString
        v.getManagementIp.getVersion shouldBe IPVersion.V4
    }

    "Boris" should "parse Primitives correctly" in {
        val n = boris.parse(s"Network create name test admin_state_up " +
                            s"true tunnel_key 633")
                     .build.asInstanceOf[Network]
        n.getAdminStateUp shouldBe true
        n.getName shouldBe "test"
        n.getTunnelKey shouldBe 633
    }

    "Boris" should "parse enums correctly" in {
        val r = boris.parse(s"Rule create conjunction_inv false " +
                           s"action REJECT")
                     .build.asInstanceOf[Rule]
        r.getConjunctionInv shouldBe false
        r.getAction shouldBe Rule.Action.REJECT
    }

    "Boris" should "parse Int32Ranges correctly" in {
        val r = boris.parse(s"Rule create tp_src -10,103 tp_dst 5,44")
                     .build.asInstanceOf[Rule]
        r.getTpSrc.getStart shouldBe -10
        r.getTpSrc.getEnd shouldBe 103
        r.getTpDst.getStart shouldBe 5
        r.getTpDst.getEnd shouldBe 44
    }

    "Boris" should "fail when operating on fake types" in {
        intercept[IllegalStateException] {
            boris.execute("Fake create name test1").get
        }.getMessage shouldBe "Invalid type: Fake"
    }

    "Boris" should "fail when a legit type is used with a bad field" in {
        intercept[IllegalStateException] {
            boris.parse("Network create fake fail").build
        }.getMessage shouldBe "Invalid property name: fake"
    }

    "Boris" should "CREATE new entities" in {
        boris.execute("Network create name test1 admin_state_up false").get
        boris.execute("Network create name test2 admin_state_up true").get
        val networks = Await.result(zoom.getAll(classOf[Network]), atMost)
        networks should have size 2
        n1 = networks.find(_.getName == "test1").orNull
        n2 = networks.find(_.getName == "test2").orNull
        n1.getAdminStateUp shouldBe false
        n2.getAdminStateUp shouldBe true
    }

    "Boris" should "GET existing entities" in {
        val r = boris.execute(s"Network get id ${fromProto(n1.getId)}").get
        r.entities should have size 1
        r.entities(0).asInstanceOf[Network].getId shouldBe n1.getId
    }

    "Boris" should "LIST existing entities" in {
        val r = boris.execute("Network list").get
        r.entities should have size 2
    }

    "Boris" should "UPDATE existing entities" in {
        boris.execute(s"Network update id ${fromProto(n1.getId)} name meh " +
                      s"admin_state_up true")
        Await.result(zoom.getAll(classOf[Network]), atMost) should have size 2
        val network = Await.result(zoom.get(classOf[Network], n1.getId), atMost)
        network.getName shouldBe "meh"
        network.getAdminStateUp shouldBe true
    }

    "Boris" should "DELETE existing entities" in {
        boris.execute(s"Network delete id ${fromProto(n1.getId)}")
        Await.result(zoom.getAll(classOf[Network]), atMost) should have size 1
        boris.execute(s"Network delete id ${fromProto(n2.getId)}")
        Await.result(zoom.getAll(classOf[Network]), atMost) shouldBe empty
    }

    "Boris" should "make use of ZOOMs referential integrity" in {
        val resN = boris.execute("Network create name test3 admin_state_up " +
                                 "false").get
        val nId = resN.entities.head.asInstanceOf[Network].getId
        val resP = boris.execute(s"Port create network_id ${fromProto(nId)}")
        val p = resP.get.entities.head.asInstanceOf[Port]
        p.getNetworkId shouldBe nId
        val n = Await.result(zoom.get(classOf[Network], fromProto(nId)),atMost)
        n.getPortIdsList should have size 1
        n.getPortIdsList.head shouldBe p.getId

        boris.execute(s"Network delete id ${fromProto(nId)}")
             .isFailure shouldBe true

        boris.execute(s"Port delete id ${fromProto(p.getId)}")
             .isSuccess shouldBe true

        boris.execute(s"Network delete id ${fromProto(nId)}")
             .isSuccess shouldBe true
    }

    "Boris" should "add / remove entries to a repeated field" in {
        val route1 = UUID.randomUUID()
        val route2 = UUID.randomUUID()
        val r1 = boris.execute("Router create name testrouter").get
        val router1 = r1.entities(0).asInstanceOf[Router]
        router1.getName shouldBe "testrouter"
        router1.getRouteIdsList shouldBe empty
        val r2 = boris.execute(s"Router add id ${fromProto(router1.getId)} " +
                               s"route_ids ${route1.toString}").get
        val router2 = r2.entities(0).asInstanceOf[Router]
        router2.getRouteIdsList should have size 1
        router2.getRouteIds(0) shouldBe toProto(route1)
        val r3 = boris.execute(s"Router add id ${fromProto(router1.getId)} " +
                               s"route_ids ${route2.toString}").get
        val router3 = r3.entities(0).asInstanceOf[Router]
        router3.getRouteIdsList should have size 2
        router3.getRouteIds(0) shouldBe toProto(route1)
        router3.getRouteIds(1) shouldBe toProto(route2)

        val r4 = boris.execute(s"Router sub id ${fromProto(router1.getId)} " +
                               s"route_ids ${route2.toString}").get
        val router4 = r4.entities(0).asInstanceOf[Router]
        router4 shouldBe router2
    }
}
