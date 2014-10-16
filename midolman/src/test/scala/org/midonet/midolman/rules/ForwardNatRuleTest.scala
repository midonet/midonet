/*
 * Copyright 2014 Midokura SARL
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

package org.midonet.midolman.rules

import java.util.{HashSet => JSet}
import java.util.UUID

import org.junit.runner.RunWith
import org.scalatest.{Matchers, Suite}
import org.scalatest.junit.JUnitRunner
import org.midonet.packets.{IPv6Addr, IPAddr, IPv4Addr}

@RunWith(classOf[JUnitRunner])
class ForwardNatRuleTest extends Suite with Matchers {

    def testFloatingIpDetectionIPv4() {
        testFloatingIpDetection(new IPv4Addr(0x01020304),
                                new IPv4Addr(0x01020305))
    }

    def testFloatingIpDetection (a1: IPv4Addr, a2: IPv4Addr) {
        implicit def targetToRule(tgt: NatTarget): ForwardNatRule = {
            val targets = new JSet[NatTarget]()
            targets.add(tgt)
            new ForwardNatRule(new Condition(), RuleResult.Action.ACCEPT,
                               UUID.randomUUID(), 0, true, targets)
        }

        new NatTarget(a1, a1, 0, 0).isFloatingIp should be (true)
        new NatTarget(a1, a2, 0, 0).isFloatingIp should be (false)
        new NatTarget(a1, a1, 10, 0).isFloatingIp should be (false)
        new NatTarget(a1, a1, 0, 65535).isFloatingIp should be (false)
        new NatTarget(a1, a1, 80, 80).isFloatingIp should be (false)
        new NatTarget(a1, a1, 1024, 10000).isFloatingIp should be (false)
    }

}
