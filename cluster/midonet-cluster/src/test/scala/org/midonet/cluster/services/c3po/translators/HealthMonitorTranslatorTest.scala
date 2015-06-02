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

import org.midonet.cluster.models.Commons.LBStatus.INACTIVE
import org.midonet.cluster.models.ModelsUtil._
import org.midonet.cluster.models.Neutron.NeutronHealthMonitor
import org.midonet.cluster.models.Topology.HealthMonitor
import org.midonet.cluster.services.c3po.{midonet, neutron}
import org.midonet.cluster.util.UUIDUtil

class HealthMonitorTranslatorTestBase extends TranslatorTestBase {
	protected var translator = new HealthMonitorTranslator()

    protected val hmId = UUIDUtil.toProtoFromProtoStr("msb: 1 lsb: 1")
    private val healthMonitorCommonFlds = s"""
            id { $hmId }
            admin_state_up: true
            delay: 1
            max_retries: 10
            timeout: 30
            """
    protected val neutronHealthMonitor =
            nHealthMonitorFromTxt(healthMonitorCommonFlds)
    protected val midoHealthMonitor =
            mHealthMonitorFromTxt(healthMonitorCommonFlds)
    protected val neutronHealthMonitorWithPool = nHealthMonitorFromTxt(
            healthMonitorCommonFlds + s"""
            pools {
                pool_id { msb: 2 lsb: 1}
            }
            """)
}

/**
 * Tests Neutron Health Monitor Create translation.
 */
@RunWith(classOf[JUnitRunner])
class HealthMonitorTranslatorCreateTest
        extends HealthMonitorTranslatorTestBase {

    val nHealthMonitor = neutronHealthMonitor
    val mHealthMonitor = midoHealthMonitor

    "Neutron Health Monitor CREATE" should "create a Midonet Health " +
    "Monitor." in {
        val midoOps = translator.translate(neutron.Create(nHealthMonitor))

        midoOps should contain only (midonet.Create(mHealthMonitor))
    }

    "CREATE for Neutron Health Monitor with Pools associated" should "fail" in {
        val te = intercept[TranslationException] {
            translator.translate(neutron.Create(neutronHealthMonitorWithPool))
        }

        te.getCause match {
            case null => fail("Expected an IllegalArgumentException.")
            case iae: IllegalArgumentException if iae.getMessage != null =>
                iae.getMessage startsWith("Load Balancer Pool") shouldBe true
            case e => fail("Expected an IllegalArgumentException.", e)
        }
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
            nHealthMonitorFromTxt(updatedHealthMonitorCommonFlds + """
                pools {
                    pool_id { msb: 2 lsb: 1 }
                    status: "status"
                    status_description: "desc"
                }
                """)
    private val mHealthMonitor =
            mHealthMonitorFromTxt(updatedHealthMonitorCommonFlds)

    "Neutron Health Monitor UPDATE" should "update a Midonet Health Monitor " +
    "except for its status." in {
        val midoOps = translator.translate(neutron.Update(nHealthMonitor))

        midoOps should contain only (midonet.Update(
                mHealthMonitor, HealthMonitorUpdateValidator))
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
        val midoOps = translator.translate(
                neutron.Delete(classOf[NeutronHealthMonitor], hmId))

        midoOps should contain only (
                midonet.Delete(classOf[HealthMonitor], hmId))
    }
}