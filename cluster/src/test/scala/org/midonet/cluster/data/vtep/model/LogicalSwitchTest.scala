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

package org.midonet.cluster.data.vtep.model

import java.util.UUID

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, Matchers}

@RunWith(classOf[JUnitRunner])
class LogicalSwitchTest extends FeatureSpec with Matchers {
    val lsUuid = UUID.randomUUID()
    val lsName = "ls-name"
    val lsDesc = "ls-desc"
    val lsVni = 42
    feature("constructors") {
        scenario("default constructor") {
            val ls = LogicalSwitch(lsUuid, lsName, lsVni, lsDesc)
            ls.uuid shouldBe lsUuid
            ls.name shouldBe lsName
            ls.tunnelKey shouldBe lsVni
            ls.description shouldBe lsDesc
        }
        scenario("non-uuid constructor") {
            val ls = LogicalSwitch(lsName, lsVni, lsDesc)
            ls.uuid shouldBe null
            ls.name shouldBe lsName
            ls.tunnelKey shouldBe lsVni
            ls.description shouldBe lsDesc
        }
    }
    feature("Logical switch tolerates null values") {
        scenario("null uuid") {
            val ls = LogicalSwitch(null, lsName, lsVni, lsDesc)
            ls.uuid shouldBe null
            ls.name shouldBe lsName
            ls.tunnelKey shouldBe lsVni
            ls.description shouldBe lsDesc
        }
        scenario("null name") {
            val ls = LogicalSwitch(lsUuid, null, lsVni, lsDesc)
            ls.uuid shouldBe lsUuid
            ls.name shouldBe ""
            ls.tunnelKey shouldBe lsVni
            ls.description shouldBe lsDesc
        }
        scenario("empty name") {
            val ls = LogicalSwitch(lsUuid, "", lsVni, lsDesc)
            ls.uuid shouldBe lsUuid
            ls.name shouldBe ""
            ls.tunnelKey shouldBe lsVni
            ls.description shouldBe lsDesc
        }
        scenario("null vni") {
            val ls = LogicalSwitch(lsUuid, lsName, null, lsDesc)
            ls.uuid shouldBe lsUuid
            ls.name shouldBe lsName
            ls.tunnelKey shouldBe null
            ls.description shouldBe lsDesc
        }
        scenario("null description") {
            val ls = LogicalSwitch(lsUuid, lsName, lsVni, null)
            ls.uuid shouldBe lsUuid
            ls.name shouldBe lsName
            ls.tunnelKey shouldBe lsVni
            ls.description shouldBe null
        }
        scenario("empty description") {
            val ls = LogicalSwitch(lsUuid, lsName, lsVni, "")
            ls.uuid shouldBe lsUuid
            ls.name shouldBe lsName
            ls.tunnelKey shouldBe lsVni
            ls.description shouldBe null
        }
    }
    feature("operations") {
        scenario("equality does not depend on uuid") {
            val ls1 = LogicalSwitch(null, lsName, lsVni, lsDesc)
            val ls2 = LogicalSwitch(lsUuid, lsName, lsVni, lsDesc)
            ls1.equals(ls2) shouldBe true
        }
        scenario("hashcode depends on uuid") {
            val ls1 = LogicalSwitch(null, lsName, lsVni, lsDesc)
            val ls2 = LogicalSwitch(lsUuid, lsName, lsVni, lsDesc)
            ls1.hashCode shouldNot be (ls2.hashCode)
            ls2.hashCode shouldBe lsUuid.hashCode()
        }
    }
}
