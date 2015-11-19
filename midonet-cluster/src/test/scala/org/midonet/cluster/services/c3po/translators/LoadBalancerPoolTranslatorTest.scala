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
import org.midonet.cluster.models.Neutron.NeutronLoadBalancerPool
import org.midonet.cluster.models.Topology.Pool.{PoolHealthMonitorMappingStatus => MappingStatus}
import org.midonet.cluster.models.Topology.{LoadBalancer, Pool}
import org.midonet.cluster.services.c3po.C3POStorageManager._
import org.midonet.cluster.util.UUIDUtil

class LoadBalancerPoolTranslatorTestBase extends TranslatorTestBase
                                         with LoadBalancerManager {
    protected var translator: LoadBalancerPoolTranslator = _

    protected val poolId = UUIDUtil.toProtoFromProtoStr("msb: 1 lsb: 1")
    protected val routerId = UUIDUtil.toProtoFromProtoStr("msb: 2 lsb: 1")
    protected val healthMonitorId =
        UUIDUtil.toProtoFromProtoStr("msb: 3 lsb: 1")
    protected val lbId = loadBalancerId(routerId)

    protected def neutronPoolProtoStr(adminStateUp: Boolean = true,
                                      healthMonitorId: UUID = null) = {
        val protoStr = s"""
            id { $poolId }
            router_id { $routerId }
            admin_state_up: $adminStateUp
            """
        if (healthMonitorId == null) protoStr
        else protoStr + s"health_monitors { $healthMonitorId }"
    }
    protected def neutronPool(adminStateUp: Boolean = true,
                              healthMonitorId: UUID = null) =
        nLoadBalancerPoolFromTxt(neutronPoolProtoStr(
                adminStateUp, healthMonitorId))

    protected val lb = mLoadBalancerFromTxt(s"""
        id { $lbId }
        admin_state_up: true
        router_id { $routerId }
        """)

    protected def midoPoolProtoStr(adminStateUp: Boolean = true,
                                   healthMonitorId: UUID = null,
                                   mappingStatus: MappingStatus = null)
    : String = {
        val bldr = new StringBuilder
        bldr ++= s"""
            id { $poolId }
            admin_state_up: $adminStateUp
            load_balancer_id { $lbId }
            """
        if (healthMonitorId != null)
            bldr ++= s"health_monitor_id { $healthMonitorId }\n"
        if (mappingStatus != null)
            bldr ++= s"mapping_status: $mappingStatus\n"
        bldr.toString()
    }
    protected def midoPool(adminStateUp: Boolean = true,
                           healthMonitorId: UUID = null) =
        mPoolFromTxt(midoPoolProtoStr(adminStateUp, healthMonitorId))

    protected val poolNoHm = neutronPool()
    protected val poolWithHm = neutronPool(healthMonitorId = healthMonitorId)
    protected val mPoolNoHm = midoPool()
    protected val mPoolWithHm = midoPool(healthMonitorId = healthMonitorId)
    protected val poolNoRouterId = nLoadBalancerPoolFromTxt(s"""
        id { $poolId }
        admin_state_up: true
        """)
}

/**
 * Tests a Neutron Load Balancer Pool CREATE translation.
 */
@RunWith(classOf[JUnitRunner])
class LoadBalancerPoolTranslatorCreateTest
        extends LoadBalancerPoolTranslatorTestBase {
    before {
        initMockStorage()
        translator = new LoadBalancerPoolTranslator(storage)
    }

    "Creation of a Pool" should "create an LB if it does not exists." in {
        bind(lbId, null, classOf[LoadBalancer])
        val midoOps = translator.translate(Create(poolNoHm))

        midoOps should contain inOrderOnly (Create(lb), Create(mPoolNoHm))
    }

    it should "create just a Pool if an LB already exists." in {
        bind(lbId, lb)
        val midoOps = translator.translate(Create(poolNoHm))

        midoOps should contain only Create(mPoolNoHm)
    }

    "Creation of a Pool with a Health Monitor ID" should "fail" in {
        bind(lbId, lb)
        val ex = the [TranslationException] thrownBy
                 translator.translate(Create(poolWithHm))
        ex.getMessage should include ("A health monitor may be associated")
    }

    "Creation of a Pool without Router ID specified" should "throw an " +
    "IllegalArgumentException." in {
        bind(lbId, null, classOf[LoadBalancer])
        val te = intercept[TranslationException] {
            translator.translate(Create(poolNoRouterId))
        }

        te.getCause match {
            case null => fail("Expected an IllegalArgumentException.")
            case iae: IllegalArgumentException if iae.getMessage != null =>
                iae.getMessage.startsWith("No router ID") shouldBe true
            case e => fail("Expected an IllegalArgumentException.", e)
        }
    }
}

/**
 * Tests a Neutron Load Balancer Pool UPDATE translation.
 */
@RunWith(classOf[JUnitRunner])
class LoadBalancerPoolTranslatorUpdateTest
        extends LoadBalancerPoolTranslatorTestBase {
    before {
        initMockStorage()
        translator = new LoadBalancerPoolTranslator(storage)
    }

    "UPDATE of a Pool with no Health Monitor ID" should "not add a Health " +
    "Monitor ID to the MidoNet Pool." in {
        bind(poolId, mPoolNoHm)
        val midoOps = translator.translate(Update(poolNoHm))

        midoOps should contain only Update(mPoolNoHm)
    }

    private val poolDown = neutronPool(adminStateUp = false,
                                         healthMonitorId = healthMonitorId)
    private val mPoolDown = midoPool(adminStateUp = false,
                                       healthMonitorId = healthMonitorId)

    "Pool UPDATE, setting admin state down" should "produce a corresponding " +
    "UPDATE" in {
        bind(poolId, mPoolWithHm)
        val midoOps = translator.translate(Update(poolDown))

        midoOps should contain only Update(mPoolDown)
    }
}

/**
 * Tests a Neutron Load Balancer Pool Delete translation.
 */
@RunWith(classOf[JUnitRunner])
class LoadBalancerPoolTranslatorDeleteTest
        extends LoadBalancerPoolTranslatorTestBase {
    before {
        initMockStorage()
        translator = new LoadBalancerPoolTranslator(storage)
    }

    "Pool Delete" should "delete the corresponding MidoNet Pool." in {
        val midoOps = translator.translate(
                Delete(classOf[NeutronLoadBalancerPool], poolId))
        midoOps should contain only Delete(classOf[Pool], poolId)
    }
}
