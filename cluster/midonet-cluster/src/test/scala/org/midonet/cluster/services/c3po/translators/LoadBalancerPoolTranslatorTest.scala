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
import org.midonet.cluster.models.Topology.LoadBalancer
import org.midonet.cluster.services.c3po.{midonet, neutron}
import org.midonet.cluster.util.UUIDUtil

class LoadBalancerPoolTranslatorTestBase extends FlatSpec with BeforeAndAfter
                                                      with Matchers {
    protected var storage: ReadOnlyStorage = _
    protected var translator: LoadBalancerPoolTranslator = _

    protected val poolId = UUIDUtil.toProtoFromProtoStr("msb: 1 lsb: 1")
    protected val routerId = UUIDUtil.toProtoFromProtoStr("msb: 2 lsb: 1")
    protected val healthMonitorId =
        UUIDUtil.toProtoFromProtoStr("msb: 3 lsb: 1")
    protected val lbId = routerId

    protected def neutronPoolProtoStr(healthMonitorId: UUID = null) = {
        val protoStr = s"""
            id { $poolId }
            router_id { $routerId }
            admin_state_up: true
            """
        if (healthMonitorId == null) protoStr
        else protoStr + s"health_monitor_ids { $healthMonitorId }"
    }
    protected def neutronPool(healthMonitorId: UUID = null) =
        nLoadBalancerPoolFromTxt(neutronPoolProtoStr(healthMonitorId))

    protected val poolNoRouterId = nLoadBalancerPoolFromTxt(s"""
        id { $poolId }
        admin_state_up: true
        """)

    protected val lb = mLoadBalancerFromTxt(s"""
        id { $routerId }
        admin_state_up: true
        router_id { $routerId }
        """)

    protected def midoPoolProtoStr(healthMonitorId: UUID = null) = {
       val protoStr = s"""
           id { $poolId }
           admin_state_up: true
           load_balancer_id { $routerId }
           """
        if (healthMonitorId == null) protoStr
        else protoStr + s"""health_monitor_id { $healthMonitorId }
                            mapping_status: PENDING_CREATE"""
    }
    protected def midoPool(healthMonitorId: UUID = null) =
        mPoolFromTxt(midoPoolProtoStr(healthMonitorId))

    protected def bindLb(id: UUID, lb: LoadBalancer) {
        val lbExists = lb != null
        when(storage.exists(classOf[LoadBalancer], id))
            .thenReturn(Promise.successful(lbExists).future)

        if (lbExists)
            when(storage.get(classOf[LoadBalancer], id))
                .thenReturn(Promise.successful(lb).future)
    }
}

/**
 * Tests a Neutron Floating IP Create translation.
 */
@RunWith(classOf[JUnitRunner])
class LoadBalancerPoolTranslatorCreateTest
        extends LoadBalancerPoolTranslatorTestBase {
    before {
        storage = mock(classOf[ReadOnlyStorage])
        translator = new LoadBalancerPoolTranslator(storage)
    }

    val poolNoHm = neutronPool()
    val poolWithHm = neutronPool(healthMonitorId)
    val mPoolNoHm = midoPool()
    val mPoolWithHm = midoPool(healthMonitorId)

    "Creation of a Pool" should "create an LB if it does not exists." in {
        bindLb(lbId, null)
        val midoOps = translator.translate(neutron.Create(poolNoHm))

        midoOps should contain inOrderOnly (
                midonet.Create(lb), midonet.Create(mPoolNoHm))
    }

    it should "create just a Pool if an LB already exists." in {
        bindLb(lbId, lb)
        val midoOps = translator.translate(neutron.Create(poolNoHm))

        midoOps should contain only (midonet.Create(mPoolNoHm))
    }

    "Creation of a Pool with a Health Monitor ID" should "set the Pool " +
    "Health Monitor Mapping Status to PENDING CREATE." in {
        bindLb(lbId, lb)
        val midoOps = translator.translate(neutron.Create(poolWithHm))

        midoOps should contain only (midonet.Create(mPoolWithHm))
    }

    "Creation of a Pool without Router ID specified" should "throw an " +
    "IllegalArgumentException." in {
        bindLb(lbId, null)
        val te = intercept[TranslationException] {
            translator.translate(neutron.Create(poolNoRouterId))
        }

        te.getCause match {
            case null => fail("Expected an IllegalArgumentException.")
            case iae: IllegalArgumentException if iae.getMessage != null =>
                iae.getMessage startsWith("No router ID") shouldBe true
            case e => fail("Expected an IllegalArgumentException.", e)
        }
    }
}