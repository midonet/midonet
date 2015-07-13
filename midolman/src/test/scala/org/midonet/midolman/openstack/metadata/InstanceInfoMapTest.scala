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
            val ip = "169.254.0.2"
            val mac = "fa:16:3e:a8:9f:15"
            val portId =
                UUID.fromString("698bd163-f43a-40c5-ae4e-d3a8479ee358")
            val fixedIp = "10.0.0.1"
            val tenantId = "4fc9464564fa4e43a13cc48acea23081"
            val instanceId = "3a680f05-fa1c-48fc-a5bb-b5642a7df11c"
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
