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

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.ModelsUtil._
import org.midonet.cluster.models.Neutron.NeutronVIP
import org.midonet.cluster.models.Topology.{Pool, Vip}
import org.midonet.cluster.services.c3po.C3POStorageManager.{Create, Delete, Update}
import org.midonet.cluster.services.c3po.midonet.{CreateNode, DeleteNode}
import org.midonet.cluster.util.UUIDUtil.fromProto
import org.midonet.cluster.util.{IPAddressUtil, UUIDUtil}

class VipTranslatorTestBase extends TranslatorTestBase
                            with LoadBalancerManager {
    protected var translator: VipTranslator = _

    protected val vipId = UUIDUtil.toProtoFromProtoStr("msb: 1 lsb: 1")
    protected val poolId = UUIDUtil.toProtoFromProtoStr("msb: 2 lsb: 1")
    protected val subnetId = UUIDUtil.toProtoFromProtoStr("msb: 3 lsb: 1")
    protected val portId = UUIDUtil.toProtoFromProtoStr("msb: 4 lsb: 1")
    protected val routerId = UUIDUtil.toProtoFromProtoStr("msb: 5 lsb: 1")
    protected val gwPortId = UUIDUtil.toProtoFromProtoStr("msb: 6 lsb: 1")
    protected val networkId = UUIDUtil.toProtoFromProtoStr("msb: 7 lsb: 1")
    protected val lbId = loadBalancerId(routerId)

    protected val vipIpAddr = "10.10.10.1"
    protected val gwPortMac = "ab:cd:ef:01:23:45"

    protected val vipCommonFlds = s"""
        id { $vipId }
        admin_state_up: true
        address { ${IPAddressUtil.toProto(vipIpAddr)} }
        protocol_port: 1234
        """
    protected def neutronVip(sourceIpSessionPersistence: Boolean = false,
                             poolId: UUID = null) = nVIPFromTxt(s"""
        $vipCommonFlds
        port_id { $portId }
        subnet_id { $subnetId }
        name: "myvip"
        description: "vip desc"
        protocol: "vip-proto"
        connection_limit: 10
        status: "vip status"
        status_description: "vip status desc"
        """ +
        { if (sourceIpSessionPersistence) s"""
        session_persistence {
            type: SOURCE_IP
            cookie_name: "vip cookie"
        }
        """ else "" } +
        { if (poolId != null) s"""
        pool_id { $poolId }
        """ else "" }
        )
    protected def midoVip(sourceIpSessionPersistence: Boolean = false,
                          poolId: UUID = null,
                          lbId: UUID = null,
                          gwPortId: UUID = null) = mVIPFromTxt(
        vipCommonFlds + { if (sourceIpSessionPersistence) s"""
        session_persistence: SOURCE_IP
        """ else "" } + { if (poolId != null) s"""
        pool_id { $poolId }
        """ else "" } + { if (gwPortId != null) s"""
        gateway_port_id { $gwPortId }
        """ else "" }
        )
    protected def midoPool(poolId: UUID, loadBalancerId: UUID) = {
        val poolBldr = Pool.newBuilder().setId(poolId)
        if (loadBalancerId != null) poolBldr.setLoadBalancerId(loadBalancerId)
        poolBldr.build
    }

    protected def neutronPool = nLoadBalancerPoolFromTxt(s"""
        id { $poolId }
        router_id: { $routerId }
        admin_state_up: true
        subnet_id: { $subnetId }
        """)

    protected def neutronRouter(gwPortId: UUID) = nRouterFromTxt(s"""
        id { $routerId }
        admin_state_up: true
        """ + { if (gwPortId != null) s"""
        gw_port_id { $gwPortId }
        """ else "" })

    protected val neutronRouterGwPort = nPortFromTxt(s"""
        id { $gwPortId }
        network_id { $networkId }
        mac_address: "$gwPortMac"
        """)

    protected val neutronSubnet = nSubnetFromTxt(s"""
        id { $subnetId }
        network_id { $networkId }
        """)

    protected def neutronNetwork(external: Boolean = false) = nNetworkFromTxt(
        s"""
        id { $networkId }
        external: ${ if (external) "true" else "false" }
        """)
}

/**
 * Tests Neutron VIP Create translation.
 */
@RunWith(classOf[JUnitRunner])
class VipTranslatorCreateTest extends VipTranslatorTestBase
                              with LoadBalancerManager {
    before {
        initMockStorage()
        translator = new VipTranslator(storage, pathBldr)
    }

    private def bindVipNetwork(external: Boolean = false) {
        bind(subnetId, neutronSubnet)
        bind(networkId, neutronNetwork(external))
    }

    private def bindLb(gwPortId: UUID = gwPortId) {
        bind(poolId, midoPool(poolId, lbId))
        bind(poolId, neutronPool)
        bind(routerId, neutronRouter(gwPortId))
        bind(gwPortId, neutronRouterGwPort)
    }

    "Neutron VIP CREATE" should "create a Midonet VIP." in {
        bindLb()
        bindVipNetwork(external = false)
        val midoOps = translator.translate(Create(neutronVip()))

        midoOps should contain only Create(midoVip())
    }

    it should "set MidoNet VIP source IP session persistence as specified." in {
        bindLb()
        bindVipNetwork(external = false)
        val midoOps = translator.translate(
                Create(neutronVip(sourceIpSessionPersistence = true)))

        midoOps should contain only
                Create(midoVip(sourceIpSessionPersistence = true))
    }

    it should "associate Mido VIP with the LB Pool as specified." in {
        bindLb()
        bindVipNetwork(external = false)
        val midoOps = translator.translate(
                Create(neutronVip(poolId = poolId)))

        midoOps should contain only
                Create(midoVip(poolId = poolId, lbId = lbId))
    }

    it should "add an ARP entry when it is associated with a Pool and is on " +
    "an external network" in {
        bindLb()
        bindVipNetwork(external = true)
        val midoOps = translator.translate(
                Create(neutronVip(poolId = poolId)))

        midoOps should contain (Create(
                midoVip(poolId = poolId, lbId = lbId, gwPortId = gwPortId)))
        midoOps should contain (CreateNode(
                arpEntryPath(networkId, vipIpAddr, gwPortMac), null))
    }

    it should "not add an ARP entry when it is associated with a Pool but is " +
    "NOT on an external network" in {
        bindLb()
        bindVipNetwork(external = false)
        val midoOps = translator.translate(
                Create(neutronVip(poolId = poolId)))

        midoOps should contain only
                Create(midoVip(poolId = poolId, lbId = lbId))
    }

    it should "not add an ARP entry when the tenant Router does not have a " +
    "gateway Port." in {
        bindLb(gwPortId = null)
        bindVipNetwork(external = true)
        val midoOps = translator.translate(
                Create(neutronVip(poolId = poolId)))

        midoOps should contain only
                Create(midoVip(poolId = poolId, lbId = lbId))
    }
}

/**
 * Tests Neutron VIP Update translation.
 */
@RunWith(classOf[JUnitRunner])
class VipTranslatorUpdateTest extends VipTranslatorTestBase {
    before {
        initMockStorage()
        translator = new VipTranslator(storage, pathBldr)
        bind(pool2Id, midoPool(pool2Id, lb2Id))
        bind(vipId, neutronVip(poolId = poolId))
        bind(vipId, midoVip(sourceIpSessionPersistence = true,
                            poolId = poolId,
                            gwPortId = gwPortId))
    }

    protected val pool2Id = UUIDUtil.toProtoFromProtoStr("msb: 2 lsb: 2")
    protected val lb2Id = UUIDUtil.toProtoFromProtoStr("msb: 5 lsb: 2")
    private val commonUpdatedFlds = s"""
        id { $vipId }
        admin_state_up: false
        address { ${IPAddressUtil.toProto("10.10.10.1")} }
        protocol_port: 8888
        pool_id { $pool2Id }
       """
    protected val updatedNeutronVip = nVIPFromTxt(s"""
        $commonUpdatedFlds
        session_persistence {
            type: SOURCE_IP
            cookie_name: "vip cookie"
        }
        """)
    protected val updatedMidoVip = mVIPFromTxt(s"""
        $commonUpdatedFlds
        session_persistence: SOURCE_IP
        pool_id { $pool2Id }
        """)

    "Neutron VIP Update" should "update a Midonet VIP." in {
        val midoOps = translator.translate(Update(updatedNeutronVip))

        midoOps should contain only Update(updatedMidoVip,
                                                   VipUpdateValidator)
    }

    protected val vipWithDifferentIp = nVIPFromTxt(s"""
        id { $vipId }
        address { ${IPAddressUtil.toProto("10.10.10.8")} }
        """)

    it should "throws an exception when the VIP's IP address is changed" in {
        val te = intercept[TranslationException] {
            translator.translate(Update(vipWithDifferentIp))
        }
        te.getCause should not be null
        te.getCause match {
            case iae: IllegalArgumentException if iae.getMessage != null =>
                iae.getMessage startsWith "VIP IP" shouldBe true
            case e => fail("Expected an IllegalArgumentException.", e)
        }
    }
}

/**
 * Tests Neutron VIP Delete translation.
 */
@RunWith(classOf[JUnitRunner])
class VipTranslatorDeleteTest extends VipTranslatorTestBase {
    before {
        initMockStorage()
        translator = new VipTranslator(storage, pathBldr)
    }

    "Neutron VIP Delete" should "delete a Midonet VIP." in {
        bind(vipId, midoVip())
        val midoOps = translator.translate(
                Delete(classOf[NeutronVIP], vipId))

        midoOps should contain only
                Delete(classOf[Vip], vipId)
    }

    it should "also delete an ARP entry when VIP is associated with a " +
    "gateway port" in {
        bind(vipId, midoVip(gwPortId = gwPortId))
        bind(gwPortId, neutronRouterGwPort)
        val midoOps = translator.translate(
                Delete(classOf[NeutronVIP], vipId))

        midoOps should contain (DeleteNode(
                arpEntryPath(networkId, vipIpAddr, gwPortMac)))
    }
}
