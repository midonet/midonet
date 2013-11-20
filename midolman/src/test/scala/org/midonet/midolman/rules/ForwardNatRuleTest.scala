// Copyright 2012 Midokura Inc.

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

    def testFloatingIpDetectionIPv6() {
        testFloatingIpDetection(IPv6Addr.fromString("11:22:33:44:55:33:22:FF"),
                                IPv6Addr.fromString("11:22:33:44:55:33:22:F0"))
    }

    def testFloatingIpDetection (a1: IPAddr, a2: IPAddr) {
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
