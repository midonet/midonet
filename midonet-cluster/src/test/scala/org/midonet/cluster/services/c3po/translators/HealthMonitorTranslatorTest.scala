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
import org.mockito.Mockito
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.storage.UpdateValidator
import org.midonet.cluster.models.ModelsUtil._
import org.midonet.cluster.models.Neutron.NeutronHealthMonitor
import org.midonet.cluster.models.Topology.HealthMonitor
import org.midonet.cluster.services.c3po.NeutronTranslatorManager._
import org.midonet.cluster.util.UUIDUtil

class HealthMonitorTranslatorTestBase extends TranslatorTestBase {
    protected var translator: HealthMonitorTranslator = _

    before {
        initMockStorage()
        translator = new HealthMonitorTranslator()
    }

    protected val hmId = UUIDUtil.toProtoFromProtoStr("msb: 1 lsb: 1")
    protected val poolId1 = UUIDUtil.toProtoFromProtoStr("msb: 1 lsb: 2")
    protected val poolId2 = UUIDUtil.toProtoFromProtoStr("msb: 1 lsb: 3")
    private val healthMonitorCommonFlds = s"""
            id { $hmId }
            admin_state_up: true
            delay: 1
            max_retries: 10
            timeout: 30
            """
    protected val neutronHealthMonitor =
            nHealthMonitorFromTxt(healthMonitorCommonFlds)
    protected val midoHealthMonitorNoPool =
        mHealthMonitorFromTxt(healthMonitorCommonFlds)
    protected val midoHealthMonitorWithPool = mHealthMonitorFromTxt(
        healthMonitorCommonFlds + s"""
            pool_ids { $poolId1 }
            """)
    protected val neutronHealthMonitorWithPool = nHealthMonitorFromTxt(
        healthMonitorCommonFlds + s"""
            pools {
                pool_id { $poolId1 }
            }
            """)
    protected val neutronHealthMonitorWithPools = nHealthMonitorFromTxt(
        healthMonitorCommonFlds + s"""
            pools {
                pool_id { $poolId1 }
            }
            pools {
                pool_id { $poolId2 }
            }
            """)
    protected val poolWithHmId1 = mPoolFromTxt(s"""
            id { $poolId1 }
            health_monitor_id: { $hmId }
            mapping_status: PENDING_CREATE
            """)
    protected val poolWithHmId2 = mPoolFromTxt(s"""
            id { $poolId2 }
            health_monitor_id: { $hmId }
            mapping_status: PENDING_CREATE
            """)
}

/**
 * Tests Neutron Health Monitor Create translation.
 */
@RunWith(classOf[JUnitRunner])
class HealthMonitorTranslatorCreateTest
        extends HealthMonitorTranslatorTestBase {

    val nHealthMonitor = neutronHealthMonitor

    "CREATE for Neutron Health Monitor with no pool associated" should
    "create a HealthMonitor" in {
        translator.translate(transaction, Create(neutronHealthMonitor))
        Mockito.verify(transaction).create(midoHealthMonitorNoPool)
    }

    "CREATE for Neutron Health Monitor with a pool associated" should
    "create a HealthMonitor" in {
        bind(poolId1, poolWithHmId1)
        translator.translate(transaction, Create(neutronHealthMonitorWithPool))
        val inOrder = Mockito.inOrder(transaction)
        inOrder.verify(transaction).create(midoHealthMonitorNoPool)
        inOrder.verify(transaction).update(poolWithHmId1)
    }

    "CREATE for Neutron Health Monitor with two pools associated" should
    "create a HealthMonitor" in {
        bind(poolId1, poolWithHmId1)
        bind(poolId2, poolWithHmId2)
        translator.translate(transaction, Create(neutronHealthMonitorWithPools))
        val inOrder = Mockito.inOrder(transaction)
        inOrder.verify(transaction).create(midoHealthMonitorNoPool)
        inOrder.verify(transaction).update(poolWithHmId1)
        inOrder.verify(transaction).update(poolWithHmId2)
    }
}

/**
 * Tests Neutron Health Monitor Update translation.
 */
@RunWith(classOf[JUnitRunner])
class HealthMonitorTranslatorUpdateTest
        extends HealthMonitorTranslatorTestBase {

    private val updatedHealthMonitorCommonFlds = s"""
            id { $hmId }
            admin_state_up: false
            delay: 2
            max_retries: 20
            timeout: 60
            """
    private val nHealthMonitor =
            nHealthMonitorFromTxt(updatedHealthMonitorCommonFlds + s"""
                pools {
                    pool_id { $poolId1 }
                    status: "status"
                    status_description: "desc"
                }
                """)
    private val mHealthMonitor =
            mHealthMonitorFromTxt(updatedHealthMonitorCommonFlds + s"""
                pool_ids { $poolId1 }
            """)

    "Neutron Health Monitor UPDATE" should "update a Midonet Health Monitor " +
    "except for its status and pool IDs" in {
        translator.translate(transaction, Update(nHealthMonitor))
        val validator = HealthMonitorUpdateValidator
            .asInstanceOf[UpdateValidator[Object]]
        Mockito.verify(transaction).update(mHealthMonitor, validator)
    }
}

/**
 * Tests Neutron Health Monitor Delete translation.
 */
@RunWith(classOf[JUnitRunner])
class HealthMonitorTranslatorDeleteTest
        extends HealthMonitorTranslatorTestBase {

    "Neutron Health Monitor DELETE" should "delete the corresponding Midonet " +
    "Health Monitor." in {
        bind(hmId, neutronHealthMonitor)
        translator.translate(transaction, Delete(classOf[NeutronHealthMonitor],
                                                  hmId))
        Mockito.verify(transaction).delete(classOf[HealthMonitor], hmId, true)
    }
}