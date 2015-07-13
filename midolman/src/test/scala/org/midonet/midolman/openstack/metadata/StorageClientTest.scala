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

package org.midonet.midolman.openstack.metadata

import java.util.UUID
import scala.concurrent.Future

import org.junit.runner.RunWith
import org.scalatest.FeatureSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.MockStorage
import org.midonet.cluster.data.storage.NotFoundException
import org.midonet.cluster.models.Neutron.NeutronPort
import org.midonet.cluster.models.ModelsUtil.nPortFromTxt

import org.midonet.cluster.util.UUIDUtil.{fromProto, randomUuidProto}


@RunWith(classOf[JUnitRunner])
class StorageClientTest extends FeatureSpec with Matchers {
    val portId = randomUuidProto
    val portJId = fromProto(portId)
    val networkId = randomUuidProto
    val mac = "11:11:11:11:11:11"
    val deviceId = "my device id"
    val tenantId = "my tenant"
    val fixedIp = "192.2.0.1"
    val subnetId = randomUuidProto

    val portBase = s"""
        id { ${portId} }
        network_id { ${networkId} }
        tenant_id: '${tenantId}'
        mac_address: '${mac}'
        admin_state_up: true
    """

    val examples = Set(
        (Future.failed(new NotFoundException(classOf[NeutronPort], "hi")),
         None),
        (Future.successful(nPortFromTxt(s"""
            ${portBase}
            device_owner: DHCP
            device_id: '${deviceId}'
            fixed_ips {
                ip_address {
                    version: V4
                    address: '${fixedIp}'
                }
                subnet_id { ${subnetId} }
            }
         """)), None),
        (Future.successful(nPortFromTxt(s"""
            ${portBase}
            device_owner: COMPUTE
            device_id: '${deviceId}'
         """)), None),
        (Future.successful(nPortFromTxt(s"""
            ${portBase}
            device_owner: COMPUTE
            device_id: '${deviceId}'
            fixed_ips {
                ip_address {
                    version: V4
                    address: '${fixedIp}'
                }
                subnet_id { ${subnetId} }
            }
            fixed_ips {
                ip_address {
                    version: V4
                    address: '192.2.0.2'
                }
                subnet_id { ${randomUuidProto} }
            }
         """)), None),
        (Future.successful(nPortFromTxt(s"""
            ${portBase}
            device_owner: COMPUTE
            device_id: '${deviceId}'
            fixed_ips {
                ip_address {
                    version: V4
                    address: '${fixedIp}'
                }
                subnet_id { ${subnetId} }
            }
         """)),
         Some(InstanceInfo(
            fixedIp,
            mac,
            portJId,
            tenantId,
            deviceId
         )))
    )

    feature("StorageClient") {
        scenario("getComputePortInfo") {
            examples foreach { case (data, expected) =>
                val store = new MockStorage(data)
                val client = new StorageClient(store)
                client.getComputePortInfo(portJId) shouldBe expected
            }
        }
    }
}
