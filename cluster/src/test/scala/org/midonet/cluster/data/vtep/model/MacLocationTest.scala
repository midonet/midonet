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

import org.midonet.packets.IPv4Addr

@RunWith(classOf[JUnitRunner])
class MacLocationTest extends FeatureSpec with Matchers {
    val mlMacStr = "aa:bb:cc:dd:ee:ff"
    val mlMac = VtepMAC.fromString(mlMacStr)
    val mlIpStr = "1.2.3.4"
    val mlIp = IPv4Addr.fromString(mlIpStr)
    val mlTunStr = "5.6.7.8"
    val mlTun = IPv4Addr.fromString(mlTunStr)
    val lsName = "ls-name"

    feature("constructors") {
        scenario("default constructor") {
            val ml = new MacLocation(mlMac, mlIp, lsName, mlTun)
            ml.mac shouldBe mlMac
            ml.ipAddr shouldBe mlIp
            ml.logicalSwitchName shouldBe lsName
            ml.vxlanTunnelEndpoint shouldBe mlTun
        }
        scenario("non-ip constructor") {
            val ml = MacLocation(mlMac, lsName, mlTun)
            ml.mac shouldBe mlMac
            ml.ipAddr shouldBe null
            ml.logicalSwitchName shouldBe lsName
            ml.vxlanTunnelEndpoint shouldBe mlTun
        }
        scenario("unknown destination mac location") {
            val ml = MacLocation.unknownAt(mlTun, lsName)
            ml.mac shouldBe VtepMAC.UNKNOWN_DST
            ml.ipAddr shouldBe null
            ml.logicalSwitchName shouldBe lsName
            ml.vxlanTunnelEndpoint shouldBe mlTun
        }
    }
}
