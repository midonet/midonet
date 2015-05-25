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

import scala.concurrent.Promise

import org.junit.runner.RunWith
import org.mockito.Mockito.{mock, when}
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FlatSpec, Matchers}

import org.midonet.cluster.data.storage.ReadOnlyStorage
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.ModelsUtil._
import org.midonet.cluster.models.Topology.Pool
import org.midonet.cluster.services.c3po.{midonet, neutron}
import org.midonet.cluster.util.IPAddressUtil
import org.midonet.cluster.util.UUIDUtil

class VipTranslatorTestBase extends FlatSpec with BeforeAndAfter
                                             with Matchers {
    protected var storage: ReadOnlyStorage = _
    protected var translator: VipTranslator = _

    protected val vipId = UUIDUtil.toProtoFromProtoStr("msb: 1 lsb: 1")
    protected val poolId = UUIDUtil.toProtoFromProtoStr("msb: 2 lsb: 1")
    protected val subnetId = UUIDUtil.toProtoFromProtoStr("msb: 3 lsb: 1")
    protected val portId = UUIDUtil.toProtoFromProtoStr("msb: 4 lsb: 1")
    protected val lbId = UUIDUtil.toProtoFromProtoStr("msb: 5 lsb: 1")
    private val vipCommonFlds = s"""
        id { $vipId }
        admin_state_up: true
        address { ${IPAddressUtil.toProto("10.10.10.1")} }
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
                          poolId: UUID = null, lbId: UUID = null) = mVIPFromTxt(
        vipCommonFlds + { if (sourceIpSessionPersistence) s"""
        session_persistence: SOURCE_IP
        """ else "" } + { if (lbId != null) s"""
        pool_id { $poolId }
        load_balancer_id { $lbId }
        """ else "" }
        )
    protected def midoPool(poolId: UUID, loadBalancerId: UUID) = {
        val poolBldr = Pool.newBuilder().setId(poolId)
        if (loadBalancerId != null) poolBldr.setLoadBalancerId(loadBalancerId)
        poolBldr.build
    }

    protected def bindPool(id: UUID, pool: Pool) {
        val poolExists = pool != null
        when(storage.exists(classOf[Pool], id))
            .thenReturn(Promise.successful(poolExists).future)

        if (poolExists)
            when(storage.get(classOf[Pool], id))
                .thenReturn(Promise.successful(pool).future)
    }
}

/**
 * Tests Neutron VIP Create translation.
 */
@RunWith(classOf[JUnitRunner])
class VipTranslatorCreateTest extends VipTranslatorTestBase {
    before {
        storage = mock(classOf[ReadOnlyStorage])
        translator = new VipTranslator(storage)
    }

    "Neutron VIP CREATE" should "create a Midonet VIP." in {
        val midoOps = translator.translate(neutron.Create(neutronVip()))

        midoOps should contain only (midonet.Create(midoVip()))
    }

    "Neutron VIP CREATE with source IP session persistence" should "create a " +
    "Midonet VIP with source IP session persistence." in {
        val midoOps = translator.translate(
                neutron.Create(neutronVip(sourceIpSessionPersistence = true)))

        midoOps should contain only (
                midonet.Create(midoVip(sourceIpSessionPersistence = true)))
    }

    "Neutron VIP CREATE with a Pool ID" should "associate the Mido VIP with " +
    "the corresponding Load Balancer." in {
        bindPool(poolId, midoPool(poolId, lbId))
        val midoOps = translator.translate(
                neutron.Create(neutronVip(poolId = poolId)))

        midoOps should contain only (
                midonet.Create(midoVip(poolId = poolId, lbId = lbId)))
    }
}