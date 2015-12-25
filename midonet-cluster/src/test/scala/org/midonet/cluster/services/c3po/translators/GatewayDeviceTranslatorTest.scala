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

import org.midonet.cluster.models.ModelsUtil._
import org.midonet.cluster.models.Neutron.GatewayDevice
import org.midonet.cluster.services.c3po.C3POStorageManager._
import org.midonet.cluster.util.UUIDUtil.randomUuidProto
import org.midonet.cluster.util.IPAddressUtil

class GatewayDeviceTranslatorTestBase extends TranslatorTestBase
                                              with OpMatchers {
    protected var translator: GatewayDeviceTranslator = _

    protected val stockGwDeviceId = randomUuidProto
    protected val stockResourceId = randomUuidProto
    protected val stockRmId = randomUuidProto
    protected val stockRmId2 = randomUuidProto
    protected val stockManagementIp = IPAddressUtil.toProto("1.1.1.3")
    protected val stockTunnelIp = IPAddressUtil.toProto("1.1.1.2")
    protected val stockVtepAddr = IPAddressUtil.toProto("1.1.1.1")
    protected val stockVtepAddr2 = IPAddressUtil.toProto("1.1.1.4")

    protected val stockGwDevice = gatewayDeviceFromTxt(s"""
        id { $stockGwDeviceId }
        type: ROUTER_VTEP
        resource_id { $stockResourceId }
        tunnel_ips { $stockTunnelIp }
        management_ip { $stockManagementIp }
        management_port: 80
        management_protocol: OVSDB
        remote_mac_entries {
            id { $stockRmId }
            vtep_address { $stockVtepAddr }
            mac_address: "aa:bb:cc:dd:ee:ff"
            segmentation_id: 100
        }
        remote_mac_entries {
            id { $stockRmId2 }
            vtep_address { $stockVtepAddr2 }
            mac_address: "aa:bb:cc:dd:ee:ff"
            segmentation_id: 100
        }
        """)

    protected val stockGwDevice1rm = gatewayDeviceFromTxt(s"""
        id { $stockGwDeviceId }
        type: ROUTER_VTEP
        resource_id { $stockResourceId }
        tunnel_ips { $stockTunnelIp }
        management_ip { $stockManagementIp }
        management_port: 80
        management_protocol: OVSDB
        remote_mac_entries {
            id { $stockRmId }
            vtep_address { $stockVtepAddr }
            mac_address: "aa:bb:cc:dd:ee:ff"
            segmentation_id: 100
        }
        """)
}

@RunWith(classOf[JUnitRunner])
class GatewayDeviceTranslatorCreateTest extends GatewayDeviceTranslatorTestBase {
    before {
        initMockStorage()
        translator = new GatewayDeviceTranslator(storage, stateTableStorage)
    }

    "Create a gateway device with 2 remote mac entries" should
    "create both entries" in {
        bind(stockGwDeviceId, stockGwDevice)
        val midoOps = translator.translate(Create(stockGwDevice))
        midoOps.size shouldBe 3
    }

    "Create a gateway device with 1 remote mac entry" should
    "create a single entry" in {
        bind(stockGwDeviceId, stockGwDevice)
        val midoOps = translator.translate(Create(stockGwDevice))
        midoOps.size shouldBe 2
    }
}

@RunWith(classOf[JUnitRunner])
class GatewayDeviceTranslatorDeleteTest extends GatewayDeviceTranslatorTestBase {
    before {
        initMockStorage()
        translator = new GatewayDeviceTranslator(storage, stateTableStorage)
    }

    "Deletes" should "be idempotent" in {
        bind(stockGwDeviceId, stockGwDevice)
        val midoOps = translator.translate(Delete(classOf[GatewayDevice], stockGwDeviceId))
        midoOps.size shouldBe 3
    }
}
