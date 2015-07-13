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

import org.junit.runner.RunWith
import org.scalatest.FeatureSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner

import java.util.UUID

@RunWith(classOf[JUnitRunner])
class InstanceInfoMapTest extends FeatureSpec with Matchers {
    feature("InstanceMap") {
        scenario("put, get, and remove") {
            val ip = "192.2.0.1"
            val mac = "22:22:22:22:22:22"
            val portId =
                UUID.fromString("69E717F4-DF6F-44A1-886D-3525874EFC89")
            val fixedIp = "10.0.0.1"
            val tenantId = "343D3BF7-0AC1-4938-B87B-DCD0E7ED669D"
            val instanceId = "98DB1DBF-701E-4E16-9B54-4132C707E14D"
            val info = InstanceInfo(fixedIp, mac, portId, tenantId, instanceId)

            InstanceInfoMap getByAddr ip shouldBe None
            InstanceInfoMap getByPortId portId shouldBe None
            InstanceInfoMap.put(ip, portId, info)
            InstanceInfoMap getByAddr ip shouldBe Some(info)
            InstanceInfoMap getByPortId portId shouldBe Some(ip)
            InstanceInfoMap removeByPortId portId
            InstanceInfoMap getByAddr ip shouldBe None
            InstanceInfoMap getByPortId portId shouldBe None
        }
    }
}
