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

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, Matchers}

import org.midonet.packets.MAC

@RunWith(classOf[JUnitRunner])
class VtepMACTest extends FeatureSpec with Matchers {
    feature("Vtep MAC") {
        scenario("equals") {
            val m1 = MAC.fromString("aa:bb:cc:dd:ee:11")
            val m2 = MAC.fromString("aa:bb:cc:dd:ee:22")
            val vm1 = VtepMAC.fromMac(m1)
            val vm2 = VtepMAC.fromMac(m2)
            vm1 shouldNot be (vm2)
            vm1 shouldNot be (VtepMAC.UNKNOWN_DST)
            VtepMAC.UNKNOWN_DST shouldEqual VtepMAC.UNKNOWN_DST
        }

        scenario("toString") {
            val m1 = MAC.fromString("aa:bb:cc:dd:ee:11")
            val vm1 = VtepMAC.fromMac(m1)
            m1.toString shouldBe vm1.toString
            VtepMAC.UNKNOWN_DST.toString shouldBe "unknown-dst"
        }

        scenario("IEEE802") {
            val m1 = MAC.fromString("aa:bb:cc:dd:ee:11")
            val vm1 = VtepMAC.fromMac(m1)
            vm1.isIEEE802 shouldBe true
            vm1.IEEE802 shouldBe m1
            VtepMAC.UNKNOWN_DST.isIEEE802 shouldBe false
            VtepMAC.UNKNOWN_DST.IEEE802 shouldBe null
        }

        scenario("unknown destination multicast") {
            VtepMAC.UNKNOWN_DST.isMcast shouldBe true
        }
    }
}
