// Copyright 2012 Midokura Inc.

package org.midonet.midolman.rules

import java.util.{HashSet => JSet}
import java.util.UUID

import org.junit.runner.RunWith
import org.scalatest.Suite
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.ShouldMatchers

@RunWith(classOf[JUnitRunner])
class ForwardNatRuleTest extends Suite with ShouldMatchers {
    def testFloatingIpDetection() {
        val addr1 = 0x01020304
        val addr2 = 0x01020305

        implicit def targetToRule(tgt: NatTarget): ForwardNatRule = {
            val targets = new JSet[NatTarget]()
            targets.add(tgt)
            new ForwardNatRule(new Condition(), RuleResult.Action.ACCEPT,
                               UUID.randomUUID(), 0, true, targets)
        }

        new NatTarget(addr1, addr1, 0, 0).isFloatingIp should be === true
        new NatTarget(addr1, addr2, 0, 0).isFloatingIp should be === false
        new NatTarget(addr1, addr1, 10, 0).isFloatingIp should be === false
        new NatTarget(addr1, addr1, 0, 65535).isFloatingIp should be === false
        new NatTarget(addr1, addr1, 80, 80).isFloatingIp should be === false
        new NatTarget(addr1, addr1, 1024, 10000).isFloatingIp should be === false
    }
}
