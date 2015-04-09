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

package org.midonet.brain.services.c3po.translators

import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FlatSpec, Matchers}

import org.midonet.brain.services.c3po.{midonet, neutron}
import org.midonet.cluster.data.storage.ReadOnlyStorage
import org.midonet.cluster.models.ModelsUtil._
import org.midonet.cluster.util.UUIDUtil.randomUuidProto

/**
 * Tests basic Neutron Subnet translation.
 */
@RunWith(classOf[JUnitRunner])
class SubnetTranslatorTest extends FlatSpec with BeforeAndAfter
                                            with Matchers {
    protected var storage: ReadOnlyStorage = _
    protected var translator: SubnetTranslator = _

    before {
        storage = mock(classOf[ReadOnlyStorage])
        translator = new SubnetTranslator(storage)
    }

    private val subnetId = randomUuidProto
    private val networkId = randomUuidProto
    private val tenantId = "tenant"
    private val nSubnet = nSubnetFromTxt(s"""
        id { $subnetId }
        network_id { $networkId }
        tenant_id: "tenant1"
        """)
    private val mDhcp = mDhcpFromTxt(s"""
        id { $subnetId }
        network_id { $networkId }
        """)

    "Basic subnet CREATE" should "produce an equivalent Dhcp Object" in {
        val midoOps = translator.translate(neutron.Create(nSubnet))
        midoOps should contain (midonet.Create(mDhcp))
    }
}