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

package org.midonet.cluster.services.rest_api.neutron

import java.util.UUID

import scala.collection.JavaConversions._
import scala.concurrent.duration._

import com.codahale.metrics.MetricRegistry
import com.typesafe.config.ConfigFactory

import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.ZoomConvert.toProto
import org.midonet.cluster.data.storage.{NotFoundException, ObjectExistsException}
import org.midonet.cluster.models.Neutron.{NeutronNetwork, NeutronPort, NeutronRouter, NeutronRouterInterface, NeutronSubnet, SecurityGroup => NeutronSecurityGroup}
import org.midonet.cluster.models.{Commons, Topology}
import org.midonet.cluster.rest_api.neutron.models._
import org.midonet.cluster.rest_api.{BadRequestHttpException, ConflictHttpException, NotFoundHttpException}
import org.midonet.cluster.services.c3po.NeutronTranslatorManager
import org.midonet.cluster.services.rest_api.neutron.plugin.NeutronZoomPlugin
import org.midonet.cluster.services.rest_api.resources.MidonetResource.ResourceContext
import org.midonet.cluster.services.{MidonetBackend, MidonetBackendService}
import org.midonet.cluster.storage.MidonetBackendConfig
import org.midonet.cluster.util.UUIDUtil.{toProto => toPuuid}
import org.midonet.cluster.util.{CuratorTestFramework, IPSubnetUtil, SequenceDispenser}
import org.midonet.cluster.{ClusterConfig, RestApiConfig}
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
        val backendConfig = new MidonetBackendConfig(ConfigFactory.parseString(s"""
           |zookeeper.zookeeper_hosts : "${zk.getConnectString}"
           |zookeeper.root_key : "$zkRoot"
           |zookeeper.transaction_attempts : 5
           |zookeeper.lock_timeout : 30s
           |state_proxy.enabled : false
        """.stripMargin)
        )
        val apiConfig = new RestApiConfig(ConfigFactory.parseString(s"""
            |cluster.rest_api.nsdb_lock_timeout : 30s
        """.stripMargin)
        )
        val clusterConfig = ClusterConfig.forTests(ConfigFactory.empty())
        MidonetBackend.isCluster = true
        backend = new MidonetBackendService(backendConfig, curator, curator,
                                            new MetricRegistry, None)
        backend.startAsync().awaitRunning()

        val resContext = ResourceContext(apiConfig,
                                         backend,
                                         executionContext = null,
                                         uriInfo = null,
                                         validator = null,
                                         seqDispenser = null)
        val sequenceDispenser = new SequenceDispenser(curator, backendConfig)
        val manager = new NeutronTranslatorManager(clusterConfig,
                                                   backend,
                                                   sequenceDispenser)
        plugin = new NeutronZoomPlugin(resContext, manager)
    }

    override def teardown(): Unit = {
        backend.stopAsync().awaitTerminated()
    }

    feature("The Plugin should be able to CRUD") {

        def exists(clazz: Class[_], id: UUID) = {
            backend.store.exists(clazz, id).await() shouldBe true
        }

        def doesNotExist(clazz: Class[_], id: UUID) = {
            backend.store.exists(clazz, id).await() shouldBe false
        }

        scenario("The plugin handles a Network") {
            val nwId = UUID.randomUUID()
            val nw = new Network(nwId, null, "original-nw", false)
            plugin.createNetwork(nw) shouldBe nw
            plugin.getNetwork(nwId) shouldBe nw

            backend.store.get(classOf[Topology.Network], nw.id)
                         .await(timeout).getName shouldBe nw.name

            val nwRenamed = new Network(nwId, null, "renamed-nw", false)
            plugin.updateNetwork(nwId, nwRenamed) shouldBe nwRenamed
            plugin.getNetwork(nwId) shouldBe nwRenamed

            backend.store.get(classOf[Topology.Network], nwId)
                   .await(timeout).getName shouldBe nwRenamed.name

            plugin.deleteNetwork(nwId)

            intercept[NotFoundHttpException] {
                plugin.getNetwork(nwId)
            }

            backend.store.exists(classOf[Topology.Network], nwId)
                   .await(timeout) shouldBe false
        }

        scenario("The plugin handles a security group and rule") {
            val sgId = UUID.randomUUID()
            val sgTenantId = "tenant"
            val sgName = "sg1"
            val sgDescription = "stargate 1"
            val sg = new SecurityGroup(sgId, sgTenantId, sgName, sgDescription,
                                       List())

            plugin.createSecurityGroup(sg)
            exists(classOf[Topology.IPAddrGroup], sgId)

            val createdSg = plugin.getSecurityGroup(sgId)
            createdSg.id should equal (sgId)
            createdSg.tenantId should equal (sgTenantId)
            createdSg.name should equal (sgName)
            createdSg.description should equal (sgDescription)

            val sgrId = UUID.randomUUID()
            val sgrDirection = RuleDirection.EGRESS
            val sgrEthertpe = RuleEthertype.IPv4
            val sgrProtocol = RuleProtocol.TCP
            val sgr = new SecurityGroupRule(sgrId, sgId, sgrDirection,
                                            sgrEthertpe, sgrProtocol)

            plugin.createSecurityGroupRule(sgr)
            exists(classOf[Topology.Rule], sgrId)

            val createdSgr = plugin.getSecurityGroupRule(sgrId)
            createdSgr.securityGroupId should equal (sgId)
            createdSgr.id should equal (sgrId)
            createdSgr.direction should equal (sgrDirection)
            createdSgr.ethertype should equal (sgrEthertpe)
            createdSgr.protocol should equal (sgrProtocol)

            plugin.deleteSecurityGroupRule(sgrId)
            doesNotExist(classOf[Topology.Rule], sgrId)

            a [NotFoundHttpException] should be thrownBy
                plugin.getSecurityGroupRule(sgrId)

            plugin.deleteSecurityGroup(sgId)
            doesNotExist(classOf[Topology.IPAddrGroup], sgId)

            a [NotFoundHttpException] should be thrownBy
              plugin.getSecurityGroup(sgId)
        }

        scenario("The plugin gracefully (no 5xx) handles ill-formed requests") {
            val nwId = UUID.randomUUID()
            val nw = new Network(nwId, null, "nw", false)
            plugin.createNetwork(nw)

            // Try to create subnet with ill-formed CIDR.
            val snId = UUID.randomUUID()
            val subnet = new Subnet(snId, nwId, "tenant", "subnet",
                                    "10.0.1.0/100", 4, "10.0.1.0", List(),
                                    List(), List(), true)
            a [BadRequestHttpException] should be thrownBy
                plugin.createSubnet(subnet)
        }

        scenario("The plugin gracefully (no 5xx) handles invalid references") {
            val portId = UUID.randomUUID()
            val nwId = UUID.randomUUID()
            val port = new Port(portId, nwId, "tenant", "port",
                                "ab:ab:ab:ab:ab:ab", List(),
                                DeviceOwner.COMPUTE, "device-id", List())
            val ex = the [NotFoundHttpException] thrownBy
                     plugin.createPort(port)
            ex.getCause shouldBe a [NotFoundException]
        }

        scenario("The plugin gracefully (no 5xx) handles a duplicate create") {
            val nwId = UUID.randomUUID()
            val nw = new Network(nwId, "tenant", "network", false)
            plugin.createNetwork(nw)
            val ex = the [ConflictHttpException] thrownBy
                     plugin.createNetwork(nw)
            ex.getCause shouldBe a [ObjectExistsException]
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
        nPort.hostId = "host1"
        val bindingProfile = new PortBindingProfile()
        bindingProfile.interfaceName = "interface1"
        nPort.bindingProfile = bindingProfile
        nPort.securityEnabled = false  // Default: true
        val allowedPair = new PortAllowedAddressPair()
        allowedPair.ipAddress = "10.0.0.3"
        allowedPair.macAddress = "0a:0a:0a:0a:0a:0a"
        nPort.allowedAddrPairs = List(allowedPair)
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
        protoPort.getHostId shouldBe nPort.hostId
        protoPort.getProfile.getInterfaceName shouldBe
                nPort.bindingProfile.interfaceName
        protoPort.getPortSecurityEnabled shouldBe nPort.securityEnabled
        protoPort.getAllowedAddressPairsCount shouldBe 1
        val protoPair = protoPort.getAllowedAddressPairs(0)
        protoPair.getIpAddress.getAddress shouldBe allowedPair.ipAddress
        protoPair.getMacAddress shouldBe allowedPair.macAddress
        protoPort.getExtraDhcpOptsCount shouldBe 1
        protoPort.getExtraDhcpOpts(0).getOptName shouldBe "opt1"
        protoPort.getExtraDhcpOpts(0).getOptValue shouldBe "val1"
    }

    feature("ZoomConvert correctly converts Network to protobuf.") {
        val pojoNetwork = new Network()
        pojoNetwork.id = UUID.randomUUID()
        pojoNetwork.name = "network1"
        pojoNetwork.status = "network status"
        pojoNetwork.shared = true
        pojoNetwork.tenantId = "tenant ID"
        pojoNetwork.adminStateUp = true
        pojoNetwork.external = true
        pojoNetwork.networkType = NetworkType.FLAT

        val protoNetwork = toProto(pojoNetwork, classOf[NeutronNetwork])
        protoNetwork.getId shouldBe toPuuid(pojoNetwork.id)
        protoNetwork.getName shouldBe pojoNetwork.name
        protoNetwork.getStatus shouldBe pojoNetwork.status
        protoNetwork.getShared shouldBe pojoNetwork.shared
        protoNetwork.getTenantId shouldBe pojoNetwork.tenantId
        protoNetwork.getAdminStateUp shouldBe pojoNetwork.adminStateUp
        protoNetwork.getExternal shouldBe pojoNetwork.external
        protoNetwork.getNetworkType shouldBe NeutronNetwork.NetworkType.FLAT
    }

    feature("ZoomConvert correctly converts Subnet to protobuf.") {
        val pojoSubnet = new Subnet()
        pojoSubnet.id = UUID.randomUUID()
        pojoSubnet.name = "subnet1"
        pojoSubnet.ipVersion = 4
        pojoSubnet.networkId = UUID.randomUUID()
        pojoSubnet.cidr = "10.10.10.0/24"
        pojoSubnet.gatewayIp = "10.10.10.1"
        pojoSubnet.allocationPools =
            List(new IPAllocationPool("10.10.10.100", "10.10.10.199"))
        pojoSubnet.hostRoutes =
            List(new Route("10.10.20.0/24", "10.10.20.1"))
        pojoSubnet.dnsNameservers = List("10.10.10.3")
        pojoSubnet.tenantId = "tenant ID"
        pojoSubnet.enableDhcp = true
        pojoSubnet.shared = true

        val protoSubnet = toProto(pojoSubnet, classOf[NeutronSubnet])
        protoSubnet.getId shouldBe toPuuid(pojoSubnet.id)
        protoSubnet.getName shouldBe pojoSubnet.name
        protoSubnet.getIpVersion shouldBe 4
        protoSubnet.getNetworkId shouldBe toPuuid(pojoSubnet.networkId)
        protoSubnet.getCidr shouldBe IPSubnetUtil.toProto(pojoSubnet.cidr)
        protoSubnet.getGatewayIp.getAddress shouldBe pojoSubnet.gatewayIp
        protoSubnet.getAllocationPoolsCount shouldBe 1
        val ipAlloc = protoSubnet.getAllocationPools(0)
        ipAlloc.getStart.getAddress shouldBe
                pojoSubnet.allocationPools(0).firstIp
        ipAlloc.getEnd.getAddress shouldBe
                pojoSubnet.allocationPools(0).lastIp
        protoSubnet.getHostRoutesCount shouldBe 1
        val route = protoSubnet.getHostRoutes(0)
        route.getDestination.getAddress shouldBe "10.10.20.0"
        route.getDestination.getPrefixLength shouldBe 24
        route.getNexthop.getAddress shouldBe pojoSubnet.hostRoutes(0).nexthop
        protoSubnet.getDnsNameserversCount shouldBe 1
        protoSubnet.getDnsNameservers(0).getAddress shouldBe
                pojoSubnet.dnsNameservers(0)
        protoSubnet.getTenantId shouldBe pojoSubnet.tenantId
        protoSubnet.getEnableDhcp shouldBe pojoSubnet.enableDhcp
        protoSubnet.getShared shouldBe pojoSubnet.shared
    }

    feature("ZoomConvert correctly converts Router to protobuf.") {
        val pojoRouter = new Router()
        pojoRouter.id = UUID.randomUUID()
        pojoRouter.name = "router1"
        pojoRouter.status = "router1 status"
        pojoRouter.tenantId = "tenant ID"
        pojoRouter.adminStateUp = true
        pojoRouter.gwPortId = UUID.randomUUID()
        pojoRouter.externalGatewayInfo =
            new ExternalGatewayInfo(UUID.randomUUID(), true)

        val protoRouter = toProto(pojoRouter, classOf[NeutronRouter])
        protoRouter.getId shouldBe toPuuid(pojoRouter.id)
        protoRouter.getName shouldBe pojoRouter.name
        protoRouter.getStatus shouldBe pojoRouter.status
        protoRouter.getTenantId shouldBe pojoRouter.tenantId
        protoRouter.getAdminStateUp shouldBe pojoRouter.adminStateUp
        protoRouter.getGwPortId shouldBe toPuuid(pojoRouter.gwPortId)
        protoRouter.getExternalGatewayInfo.getNetworkId shouldBe
                toPuuid(pojoRouter.externalGatewayInfo.networkId)
    }

    feature("ZoomConvert correctly converts RouterInterface to protobuf.") {
        val pojoRouterIfc = new RouterInterface()
        pojoRouterIfc.id = UUID.randomUUID()
        pojoRouterIfc.tenantId = "tenant ID"
        pojoRouterIfc.portId = UUID.randomUUID()
        pojoRouterIfc.subnetId = UUID.randomUUID()

        val protoRouterIfc = toProto(pojoRouterIfc,
                                     classOf[NeutronRouterInterface])
        protoRouterIfc.getId shouldBe toPuuid(pojoRouterIfc.id)
        protoRouterIfc.getTenantId shouldBe pojoRouterIfc.tenantId
        protoRouterIfc.getPortId shouldBe toPuuid(pojoRouterIfc.portId)
        protoRouterIfc.getSubnetId shouldBe toPuuid(pojoRouterIfc.subnetId)
    }

    feature("ZoomConvert correctly converts SecurityGroup and " +
            "SecurityGroupRule to protobuf.") {
        val pojoSecurityGroup = new SecurityGroup()
        pojoSecurityGroup.id = UUID.randomUUID()
        pojoSecurityGroup.name = "tenant1"
        pojoSecurityGroup.description = "sg desc"
        pojoSecurityGroup.tenantId = "tenant ID"
        val sgRule1 = new SecurityGroupRule()
        sgRule1.id = UUID.randomUUID()
        sgRule1.securityGroupId = UUID.randomUUID()
        sgRule1.remoteGroupId = UUID.randomUUID()
        sgRule1.direction = RuleDirection.INGRESS
        sgRule1.protocol = RuleProtocol.UDP
        sgRule1.portRangeMin = 1000
        sgRule1.portRangeMax = 2000
        sgRule1.ethertype = RuleEthertype.IPv4
        sgRule1.remoteIpPrefix = "10.10.0.0"
        sgRule1.tenantId = "tenant1"
        pojoSecurityGroup.securityGroupRules = List(sgRule1)

        val protoSecurityGroup = toProto(pojoSecurityGroup,
                                         classOf[NeutronSecurityGroup])
        protoSecurityGroup.getId shouldBe toPuuid(pojoSecurityGroup.id)
        protoSecurityGroup.getName shouldBe pojoSecurityGroup.name
        protoSecurityGroup.getDescription shouldBe pojoSecurityGroup.description
        protoSecurityGroup.getTenantId shouldBe pojoSecurityGroup.tenantId
        protoSecurityGroup.getSecurityGroupRulesCount shouldBe 1
        val protoSgRule1 = protoSecurityGroup.getSecurityGroupRules(0)
        protoSgRule1.getId shouldBe toPuuid(sgRule1.id)
        protoSgRule1.getSecurityGroupId shouldBe toPuuid(
                sgRule1.securityGroupId)
        protoSgRule1.getRemoteGroupId shouldBe toPuuid(sgRule1.remoteGroupId)
        protoSgRule1.getDirection shouldBe Commons.RuleDirection.INGRESS
        protoSgRule1.getProtocol shouldBe Commons.Protocol.UDP
        protoSgRule1.getPortRangeMin shouldBe sgRule1.portRangeMin
        protoSgRule1.getPortRangeMax shouldBe sgRule1.portRangeMax
        protoSgRule1.getEthertype shouldBe Commons.EtherType.IPV4
        protoSgRule1.getRemoteIpPrefix shouldBe sgRule1.remoteIpPrefix
        protoSgRule1.getTenantId shouldBe sgRule1.tenantId
    }
}
