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

import org.midonet.packets.IPv4Addr

@RunWith(classOf[JUnitRunner])
class PhysicalSwitchTest extends FeatureSpec with Matchers {
    val psUuid = UUID.randomUUID()
    val psName = "ps-name"
    val psDesc = "ps-desc"
    val psPorts: util.Set[String] = new util.HashSet[String]()
    psPorts.add("p1")
    psPorts.add("p2")
    val psMgmt: util.Set[IPv4Addr] = new util.HashSet[IPv4Addr]()
    psMgmt.add(IPv4Addr.fromString("1.2.3.4"))
    val psTunnel: util.Set[IPv4Addr] = new util.HashSet[IPv4Addr]()
    psTunnel.add(IPv4Addr.fromString("5.6.7.8"))

    feature("constructors") {
        scenario("default constructor") {
            val ps =
                PhysicalSwitch(psUuid, psName, psDesc, psPorts, psMgmt, psTunnel)
            ps.uuid shouldBe psUuid
            ps.name shouldBe psName
            ps.description shouldBe psDesc
            ps.ports shouldBe psPorts
            ps.mgmtIps shouldBe psMgmt
            ps.tunnelIps shouldBe psTunnel

            ps.mgmtIpStrings.iterator().next() shouldBe "1.2.3.4"
            ps.tunnelIpStrings.iterator().next() shouldBe "5.6.7.8"
            ps.tunnelIp.isEmpty shouldBe false
        }
    }
    feature("physical switch tolerates null values") {
        scenario("null uuid") {
            val ps =
                PhysicalSwitch(null, psName, psDesc, psPorts, psMgmt, psTunnel)
            ps.uuid shouldBe null
            ps.name shouldBe psName
            ps.description shouldBe psDesc
            ps.ports shouldBe psPorts
            ps.mgmtIps shouldBe psMgmt
            ps.tunnelIps shouldBe psTunnel

            ps.mgmtIpStrings.iterator().next() shouldBe "1.2.3.4"
            ps.tunnelIpStrings.iterator().next() shouldBe "5.6.7.8"
            ps.tunnelIp.isEmpty shouldBe false
        }
        scenario("null name") {
            val ps =
                PhysicalSwitch(psUuid, null, psDesc, psPorts, psMgmt, psTunnel)
            ps.uuid shouldBe psUuid
            ps.name shouldBe ""
            ps.description shouldBe psDesc
            ps.ports shouldBe psPorts
            ps.mgmtIps shouldBe psMgmt
            ps.tunnelIps shouldBe psTunnel

            ps.mgmtIpStrings.iterator().next() shouldBe "1.2.3.4"
            ps.tunnelIpStrings.iterator().next() shouldBe "5.6.7.8"
            ps.tunnelIp.isEmpty shouldBe false
        }
        scenario("empty name") {
            val ps =
                PhysicalSwitch(psUuid, "", psDesc, psPorts, psMgmt, psTunnel)
            ps.uuid shouldBe psUuid
            ps.name shouldBe ""
            ps.description shouldBe psDesc
            ps.ports shouldBe psPorts
            ps.mgmtIps shouldBe psMgmt
            ps.tunnelIps shouldBe psTunnel

            ps.mgmtIpStrings.iterator().next() shouldBe "1.2.3.4"
            ps.tunnelIpStrings.iterator().next() shouldBe "5.6.7.8"
            ps.tunnelIp.isEmpty shouldBe false
        }
        scenario("null description") {
            val ps =
                PhysicalSwitch(psUuid, psName, null, psPorts, psMgmt, psTunnel)
            ps.uuid shouldBe psUuid
            ps.name shouldBe psName
            ps.description shouldBe null
            ps.ports shouldBe psPorts
            ps.mgmtIps shouldBe psMgmt
            ps.tunnelIps shouldBe psTunnel

            ps.mgmtIpStrings.iterator().next() shouldBe "1.2.3.4"
            ps.tunnelIpStrings.iterator().next() shouldBe "5.6.7.8"
            ps.tunnelIp.isEmpty shouldBe false
        }
        scenario("empty description") {
            val ps =
                PhysicalSwitch(psUuid, psName, "", psPorts, psMgmt, psTunnel)
            ps.uuid shouldBe psUuid
            ps.name shouldBe psName
            ps.description shouldBe null
            ps.ports shouldBe psPorts
            ps.mgmtIps shouldBe psMgmt
            ps.tunnelIps shouldBe psTunnel

            ps.mgmtIpStrings.iterator().next() shouldBe "1.2.3.4"
            ps.tunnelIpStrings.iterator().next() shouldBe "5.6.7.8"
            ps.tunnelIp.isEmpty shouldBe false
        }
        scenario("null ports") {
            val ps =
                PhysicalSwitch(psUuid, psName, psDesc, null, psMgmt, psTunnel)
            ps.uuid shouldBe psUuid
            ps.name shouldBe psName
            ps.description shouldBe psDesc
            ps.ports.isEmpty shouldBe true
            ps.mgmtIps shouldBe psMgmt
            ps.tunnelIps shouldBe psTunnel

            ps.mgmtIpStrings.iterator().next() shouldBe "1.2.3.4"
            ps.tunnelIpStrings.iterator().next() shouldBe "5.6.7.8"
            ps.tunnelIp.isEmpty shouldBe false
        }
        scenario("null mgmt ips") {
            val ps =
                PhysicalSwitch(psUuid, psName, psDesc, psPorts, null, psTunnel)
            ps.uuid shouldBe psUuid
            ps.name shouldBe psName
            ps.description shouldBe psDesc
            ps.ports shouldBe psPorts
            ps.mgmtIps.isEmpty shouldBe true
            ps.tunnelIps shouldBe psTunnel

            ps.tunnelIpStrings.iterator().next() shouldBe "5.6.7.8"
            ps.tunnelIp.isEmpty shouldBe false
        }
        scenario("null tunnel ips") {
            val ps =
                PhysicalSwitch(psUuid, psName, psDesc, psPorts, psMgmt, null)
            ps.uuid shouldBe psUuid
            ps.name shouldBe psName
            ps.description shouldBe psDesc
            ps.ports shouldBe psPorts
            ps.mgmtIps shouldBe psMgmt
            ps.tunnelIps.isEmpty shouldBe true

            ps.mgmtIpStrings.iterator().next() shouldBe "1.2.3.4"
            ps.tunnelIp.isEmpty shouldBe true
        }
    }
    feature("operations") {
        scenario("equality does not depend on uuid") {
            val ps1 =
                PhysicalSwitch(null, psName, psDesc, psPorts, psMgmt, psTunnel)
            val ps2 =
                PhysicalSwitch(psUuid, psName, psDesc, psPorts, psMgmt, psTunnel)
            ps1.equals(ps2) shouldBe true
        }
        scenario("hashcode depends on uuid") {
            val ps1 =
                PhysicalSwitch(null, psName, psDesc, psPorts, psMgmt, psTunnel)
            val ps2 =
                PhysicalSwitch(psUuid, psName, psDesc, psPorts, psMgmt, psTunnel)
            ps1.hashCode shouldNot be (ps2.hashCode)
            ps2.hashCode shouldBe psUuid.hashCode()
        }
    }
}
