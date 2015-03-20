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

import org.midonet.packets.IPv4Addr

@RunWith(classOf[JUnitRunner])
class MacEntryTest extends FeatureSpec with Matchers {
    val eUuid = UUID.randomUUID()
    val eLS = UUID.randomUUID()
    val eLoc = UUID.randomUUID()
    val eMacStr = "aa:bb:cc:dd:ee:ff"
    val eMac = VtepMAC.fromString(eMacStr)
    val eIpStr = "1.2.3.4"
    val eIp = IPv4Addr.fromString(eIpStr)
    val eMulti = VtepMAC.UNKNOWN_DST
    val eMultiStr = VtepMAC.UNKNOWN_DST.toString

    feature("constructors") {
        scenario("default constructor - ucast") {
            val ucast = UcastMac(eUuid, eLS, eMac, eIp, eLoc)
            ucast.uuid shouldBe eUuid
            ucast.logicalSwitchId shouldBe eLS
            ucast.mac shouldBe eMac
            ucast.macString shouldBe eMacStr
            ucast.ip shouldBe eIp
            ucast.ipString shouldBe eIpStr
            ucast.locationId shouldBe eLoc
            ucast.locator shouldBe eLoc

            val entry: MacEntry = ucast
            entry.isUcast shouldBe true
            entry.asUcast shouldBe ucast
            a [ClassCastException] shouldBe thrownBy(entry.asMcast)
        }
        scenario("non-uuid constructor - ucast") {
            val ucast = UcastMac(eLS, eMac, eIp, eLoc)
            ucast.uuid shouldBe null
            ucast.logicalSwitchId shouldBe eLS
            ucast.mac shouldBe eMac
            ucast.macString shouldBe eMacStr
            ucast.ip shouldBe eIp
            ucast.ipString shouldBe eIpStr
            ucast.locationId shouldBe eLoc
            ucast.locator shouldBe eLoc
        }
        scenario("default constructor - mcast") {
            val mcast = McastMac(eUuid, eLS, eMulti, eIp, eLoc)
            mcast.uuid shouldBe eUuid
            mcast.logicalSwitchId shouldBe eLS
            mcast.mac shouldBe eMulti
            mcast.macString shouldBe eMultiStr
            mcast.ip shouldBe eIp
            mcast.ipString shouldBe eIpStr
            mcast.locationId shouldBe eLoc
            mcast.locatorSet shouldBe eLoc

            val entry: MacEntry = mcast
            entry.isUcast shouldBe false
            entry.asMcast shouldBe mcast
            a [ClassCastException] shouldBe thrownBy(entry.asUcast)
        }
        scenario("non-uuid constructor - mcast") {
            val mcast = McastMac(eLS, eMulti, eIp, eLoc)
            mcast.uuid shouldBe null
            mcast.logicalSwitchId shouldBe eLS
            mcast.mac shouldBe eMulti
            mcast.macString shouldBe eMultiStr
            mcast.ip shouldBe eIp
            mcast.ipString shouldBe eIpStr
            mcast.locationId shouldBe eLoc
            mcast.locatorSet shouldBe eLoc
        }
    }
    feature("Mac entries tolerate null values") {
        scenario("null uuid - ucast") {
            val m = UcastMac(null, eLS, eMac, eIp, eLoc)
            m.uuid shouldBe null
            m.logicalSwitchId shouldBe eLS
            m.mac shouldBe eMac
            m.macString shouldBe eMacStr
            m.ip shouldBe eIp
            m.ipString shouldBe eIpStr
            m.locationId shouldBe eLoc
            m.locator shouldBe eLoc
        }
        scenario("null mac - ucast") {
            val m = new UcastMac(eUuid, eLS, null, eIp, eLoc)
            m.uuid shouldBe eUuid
            m.logicalSwitchId shouldBe eLS
            m.mac shouldBe null
            m.macString shouldBe null
            m.ip shouldBe eIp
            m.ipString shouldBe eIpStr
            m.locationId shouldBe eLoc
            m.locator shouldBe eLoc
        }
        scenario("empty mac - ucast") {
            val m = UcastMac(eUuid, eLS, "", eIp, eLoc)
            m.uuid shouldBe eUuid
            m.logicalSwitchId shouldBe eLS
            m.mac shouldBe null
            m.macString shouldBe null
            m.ip shouldBe eIp
            m.ipString shouldBe eIpStr
            m.locationId shouldBe eLoc
            m.locator shouldBe eLoc
        }
        scenario("null ip - ucast") {
            val m = new UcastMac(eUuid, eLS, eMac, null, eLoc)
            m.uuid shouldBe eUuid
            m.logicalSwitchId shouldBe eLS
            m.mac shouldBe eMac
            m.macString shouldBe eMacStr
            m.ip shouldBe null
            m.ipString shouldBe null
            m.locationId shouldBe eLoc
            m.locator shouldBe eLoc
        }
        scenario("empty ip - ucast") {
            val m = UcastMac(eUuid, eLS, eMac, "", eLoc)
            m.uuid shouldBe eUuid
            m.logicalSwitchId shouldBe eLS
            m.mac shouldBe eMac
            m.macString shouldBe eMacStr
            m.ip shouldBe null
            m.ipString shouldBe null
            m.locationId shouldBe eLoc
            m.locator shouldBe eLoc
        }
        scenario("null uuid - mcast") {
            val m = McastMac(null, eLS, eMulti, eIp, eLoc)
            m.uuid shouldBe null
            m.logicalSwitchId shouldBe eLS
            m.mac shouldBe eMulti
            m.macString shouldBe eMultiStr
            m.ip shouldBe eIp
            m.ipString shouldBe eIpStr
            m.locationId shouldBe eLoc
            m.locatorSet shouldBe eLoc
        }
        scenario("null mac - mcast") {
            val m = new McastMac(eUuid, eLS, null, eIp, eLoc)
            m.uuid shouldBe eUuid
            m.logicalSwitchId shouldBe eLS
            m.mac shouldBe null
            m.macString shouldBe null
            m.ip shouldBe eIp
            m.ipString shouldBe eIpStr
            m.locationId shouldBe eLoc
            m.locatorSet shouldBe eLoc
        }
        scenario("empty mac - Mcast") {
            val m = McastMac(eUuid, eLS, "", eIp, eLoc)
            m.uuid shouldBe eUuid
            m.logicalSwitchId shouldBe eLS
            m.mac shouldBe null
            m.macString shouldBe null
            m.ip shouldBe eIp
            m.ipString shouldBe eIpStr
            m.locationId shouldBe eLoc
            m.locatorSet shouldBe eLoc
        }
        scenario("null ip - mcast") {
            val m = new McastMac(eUuid, eLS, eMulti, null, eLoc)
            m.uuid shouldBe eUuid
            m.logicalSwitchId shouldBe eLS
            m.mac shouldBe eMulti
            m.macString shouldBe eMultiStr
            m.ip shouldBe null
            m.ipString shouldBe null
            m.locationId shouldBe eLoc
            m.locatorSet shouldBe eLoc
        }
        scenario("empty ip - mcast") {
            val m = McastMac(eUuid, eLS, eMulti, "", eLoc)
            m.uuid shouldBe eUuid
            m.logicalSwitchId shouldBe eLS
            m.mac shouldBe eMulti
            m.macString shouldBe eMultiStr
            m.ip shouldBe null
            m.ipString shouldBe null
            m.locationId shouldBe eLoc
            m.locatorSet shouldBe eLoc
        }
    }
    feature("operations") {
        scenario("equality does not depend on uuid - ucast") {
            val e1 = UcastMac(null, eLS, eMac, eIp, eLoc)
            val e2 = UcastMac(eUuid, eLS, eMac, eIp, eLoc)
            e1.equals(e2) shouldBe true
        }
        scenario("hashcode depends on uuid - ucast") {
            val e1 = UcastMac(null, eLS, eMac, eIp, eLoc)
            val e2 = UcastMac(eUuid, eLS, eMac, eIp, eLoc)
            e1.hashCode shouldNot be (e2.hashCode)
            e2.hashCode shouldBe eUuid.hashCode()
        }
        scenario("equality does not depend on uuid - mcast") {
            val e1 = McastMac(null, eLS, eMulti, eIp, eLoc)
            val e2 = McastMac(eUuid, eLS, eMulti, eIp, eLoc)
            e1.equals(e2) shouldBe true
        }
        scenario("hashcode depends on uuid - mcast") {
            val e1 = McastMac(null, eLS, eMulti, eIp, eLoc)
            val e2 = McastMac(eUuid, eLS, eMulti, eIp, eLoc)
            e1.hashCode shouldNot be (e2.hashCode)
            e2.hashCode shouldBe eUuid.hashCode()
        }
    }
}
