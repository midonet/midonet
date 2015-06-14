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

package org.midonet.cluster.services.rest_api.neutron

import java.util.UUID

import scala.collection.JavaConversions._
import scala.concurrent.duration._

import com.typesafe.config.ConfigFactory

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest._

import org.midonet.cluster.data.ZoomConvert.{fromProto, toProto}
import org.midonet.cluster.data.storage.NotFoundException
import org.midonet.cluster.models.Neutron.NeutronPort
import org.midonet.cluster.models.Topology
import org.midonet.cluster.rest_api.NotFoundHttpException
import org.midonet.cluster.rest_api.neutron.models.{DeviceOwner, ExtraDhcpOpt, IPAllocation, Network, Port}
import org.midonet.cluster.services.rest_api.neutron.plugin.NeutronZoomPlugin
import org.midonet.cluster.services.{MidonetBackend, MidonetBackendService}
import org.midonet.cluster.storage.MidonetBackendConfig
import org.midonet.cluster.util.CuratorTestFramework
import org.midonet.cluster.util.UUIDUtil.{toProto => toPuuid}
import org.midonet.util.MidonetEventually
import org.midonet.util.concurrent.toFutureOps

@RunWith(classOf[JUnitRunner])
class NeutronZoomPluginTest extends FeatureSpec
                                   with BeforeAndAfter
                                   with ShouldMatchers
                                   with GivenWhenThen
                                   with CuratorTestFramework
                                   with MidonetEventually {

    var backend: MidonetBackend = _
    var plugin: NeutronZoomPlugin = _
    var timeout = 5.seconds

    override def setup() {
        val cfg = new MidonetBackendConfig(ConfigFactory.parseString(s"""
           |zookeeper.zookeeper_hosts : "${zk.getConnectString}"
           |zookeeper.root_key : "$ZK_ROOT"
        """.stripMargin)
        )
        backend = new MidonetBackendService(cfg, curator)
        backend.setupBindings()
        plugin = new NeutronZoomPlugin(backend, cfg)
    }

    feature("The Plugin should be able to CRUD") {
        scenario("The plugin handles a Network") {
            val n = new Network
            n.id = UUID.randomUUID()
            n.name = UUID.randomUUID().toString
            n.external = false
            val n1 = plugin.createNetwork(n)
            n1 shouldBe n
            n1 shouldBe plugin.getNetwork(n.id)

            backend.store.get(classOf[Topology.Network], n.id)
                         .await(timeout).getName shouldBe n.name

            n.name = UUID.randomUUID().toString
            val n2 = plugin.updateNetwork(n.id, n)
            n2 shouldBe n
            n2 shouldNot be (n1)

            backend.store.get(classOf[Topology.Network], n.id)
                   .await(timeout).getName shouldBe n.name

            plugin.deleteNetwork(n.id)

            intercept[NotFoundHttpException] {
                plugin.getNetwork(n.id)
            }

            backend.store.exists(classOf[Topology.Network], n.id)
                   .await(timeout) shouldBe false
        }
    }

    feature("ZoomConvert correctly converts Port to protobuf.") {
        val nPort = new Port()
        nPort.id = UUID.randomUUID()
        nPort.networkId = UUID.randomUUID()
        nPort.tenantId = "tenant ID"
        nPort.name = "port0"
        nPort.macAddress = "01:01:01:01:01:01"
        nPort.adminStateUp = true
        nPort.fixedIps = List(new IPAllocation("10.0.0.1", UUID.randomUUID()))
        nPort.deviceId = UUID.randomUUID().toString
        nPort.deviceOwner = DeviceOwner.DHCP
        nPort.status = "port status"
        nPort.securityGroups = List(UUID.randomUUID(), UUID.randomUUID())
        nPort.extraDhcpOpts = List(new ExtraDhcpOpt("opt1", "val1"))

        val protoPort = toProto(nPort, classOf[NeutronPort])
        protoPort.getId shouldBe toPuuid(nPort.id)
        protoPort.getNetworkId shouldBe toPuuid(nPort.networkId)
        protoPort.getTenantId shouldBe nPort.tenantId
        protoPort.getName shouldBe nPort.name
        protoPort.getMacAddress shouldBe nPort.macAddress
        protoPort.getAdminStateUp shouldBe nPort.adminStateUp
        protoPort.getFixedIpsCount shouldBe 1
        protoPort.getFixedIps(0).getIpAddress.getAddress shouldBe "10.0.0.1"
        protoPort.getDeviceId shouldBe nPort.deviceId
        protoPort.getDeviceOwner shouldBe NeutronPort.DeviceOwner.DHCP
        protoPort.getStatus shouldBe nPort.status
        protoPort.getSecurityGroupsCount shouldBe 2
        protoPort.getSecurityGroups(0) shouldBe toPuuid(nPort.securityGroups(0))
        protoPort.getSecurityGroups(1) shouldBe toPuuid(nPort.securityGroups(1))
        protoPort.getExtraDhcpOptsCount shouldBe 1
        protoPort.getExtraDhcpOpts(0).getOptName shouldBe "opt1"
        protoPort.getExtraDhcpOpts(0).getOptValue shouldBe "val1"
    }
}
