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

package org.midonet.cluster.rest_api.neutron

import java.util
import java.util.UUID

import org.midonet.cluster.rest_api.neutron.models.{QOSPolicy}
import org.midonet.cluster.rest_api.neutron.models.{QOSRuleDSCP, QOSRuleBWLimit}


@RunWith(classOf[JUnitRunner])
class TestQOSPolicy extends NeutronApiTest {

    scenario("Neutron has QOS policy endpoint") {
        val neutron = getNeutron
        neutron.qosPolicy.toString
            .endsWith("/neutron/qos_policy") shouldBe true
        neutron.qosPolicyTemplate
            .endsWith("/neutron/qos_policy/{id}") shouldBe true
    }

    scenario("Create, read, delete") {
        val pol = new QOSPolicy
        pol.id = UUID.randomUUID()
        pol.bwLimitRules = new util.ArrayList[QOSRuleBWLimit]()
        pol.dscpRules = new util.ArrayList[QOSRuleDSCP]()

        val bwLimit1 = new QOSRuleBWLimit
        bwLimit1.id = UUID.randomUUID()
        bwLimit1.max_kbps = 30
        bwLimit1.max_burst_kbps = 100

        val dscp1 = new QOSRuleDSCP
        dscp1.id = UUID.randomUUID()
        dscp1.dscp_mark = 10

        pol.bwLimitRules.add(bwLimit1.id)
        pol.dscpRules.add(dscp1.id)

        val qosUri = postAndVerifySuccess(pol)

        get[QOSPolicy](qosUri) shouldBe pol

        deleteAndVerifyNoContent(qosUri)
    }
}
