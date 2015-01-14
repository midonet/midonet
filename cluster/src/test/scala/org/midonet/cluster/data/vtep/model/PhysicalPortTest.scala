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

import java.util
import java.util.UUID

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, Matchers}

@RunWith(classOf[JUnitRunner])
class PhysicalPortTest extends FeatureSpec with Matchers {
    val lsId = UUID.randomUUID()
    val stId = UUID.randomUUID()
    val uuid = UUID.randomUUID()
    val name = "pp-name"
    val desc = "pp-desc"
    val bindings = new util.HashMap[Int, UUID]()
    bindings.put(1, lsId)
    val stats = new util.HashMap[Int, UUID]()
    stats.put(1, stId)
    val faults = new util.HashSet[String]()
    faults.add("some")

    feature("constructors") {
        scenario("default constructor") {
            val pp = PhysicalPort(uuid, name, desc, bindings, stats, faults)
            pp.uuid shouldBe uuid
            pp.name shouldBe name
            pp.description shouldBe desc
            pp.vlanBindings shouldBe bindings
            pp.vlanStats shouldBe stats
            pp.portFaultStatus shouldBe faults
        }
    }
    feature("physical port tolerates null values") {
        scenario("null uuid") {
            val pp = PhysicalPort(null, name, desc, bindings, stats, faults)
            pp.uuid shouldBe null
            pp.name shouldBe name
            pp.description shouldBe desc
            pp.vlanBindings shouldBe bindings
            pp.vlanStats shouldBe stats
            pp.portFaultStatus shouldBe faults
        }
        scenario("null name") {
            val pp = PhysicalPort(uuid, null, desc, bindings, stats, faults)
            pp.uuid shouldBe uuid
            pp.name shouldBe ""
            pp.description shouldBe desc
            pp.vlanBindings shouldBe bindings
            pp.vlanStats shouldBe stats
            pp.portFaultStatus shouldBe faults
        }
        scenario("empty name") {
            val pp = PhysicalPort(uuid, "", desc, bindings, stats, faults)
            pp.uuid shouldBe uuid
            pp.name shouldBe ""
            pp.description shouldBe desc
            pp.vlanBindings shouldBe bindings
            pp.vlanStats shouldBe stats
            pp.portFaultStatus shouldBe faults
        }
        scenario("null description") {
            val pp = PhysicalPort(uuid, name, null, bindings, stats, faults)
            pp.uuid shouldBe uuid
            pp.name shouldBe name
            pp.description shouldBe null
            pp.vlanBindings shouldBe bindings
            pp.vlanStats shouldBe stats
            pp.portFaultStatus shouldBe faults
        }
        scenario("empty description") {
            val pp = PhysicalPort(uuid, name, "", bindings, stats, faults)
            pp.uuid shouldBe uuid
            pp.name shouldBe name
            pp.description shouldBe null
            pp.vlanBindings shouldBe bindings
            pp.vlanStats shouldBe stats
            pp.portFaultStatus shouldBe faults
        }
        scenario("null bindings") {
            val pp = PhysicalPort(uuid, name, desc, null, stats, faults)
            pp.uuid shouldBe uuid
            pp.name shouldBe name
            pp.description shouldBe desc
            pp.vlanBindings.isEmpty shouldBe true
            pp.vlanStats shouldBe stats
            pp.portFaultStatus shouldBe faults
        }
        scenario("null stats") {
            val pp = PhysicalPort(uuid, name, desc, bindings, null, faults)
            pp.uuid shouldBe uuid
            pp.name shouldBe name
            pp.description shouldBe desc
            pp.vlanBindings shouldBe bindings
            pp.vlanStats.isEmpty shouldBe true
            pp.portFaultStatus shouldBe faults
        }
        scenario("null faults") {
            val pp = PhysicalPort(uuid, name, desc, bindings, stats, null)
            pp.uuid shouldBe uuid
            pp.name shouldBe name
            pp.description shouldBe desc
            pp.vlanBindings shouldBe bindings
            pp.vlanStats shouldBe stats
            pp.portFaultStatus.isEmpty shouldBe true
        }
    }
    feature("operations") {
        scenario("equality does not depend on uuid") {
            val pp1 = PhysicalPort(null, name, desc, bindings, stats, faults)
            val pp2 = PhysicalPort(uuid, name, desc, bindings, stats, faults)
            pp1.equals(pp2) shouldBe true
        }
        scenario("hashcode depends on uuid") {
            val pp1 = PhysicalPort(null, name, desc, bindings, stats, faults)
            val pp2 = PhysicalPort(uuid, name, desc, bindings, stats, faults)
            pp1.hashCode shouldNot be (pp2.hashCode)
            pp2.hashCode shouldBe uuid.hashCode()
        }
    }
}
