/* Copyright 2013 Midokura Inc. */

package org.midonet.packets

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.Suite
import org.scalatest.matchers.ShouldMatchers
import java.util.Random
import org.junit.Test

@RunWith(classOf[JUnitRunner])
class IPv6AddrTest extends Suite with ShouldMatchers {

    def testNext() {
        var ip = IPv6Addr.fromString("f00f:4004:2003:2002:3ddd:6666:4934:1")
        ip.next.toString() should be === "f00f:4004:2003:2002:3ddd:6666:4934:2"

        ip = IPv6Addr.fromString("f00f:4004:2003:2002:3ddd:6666:4934:ffff")
        ip.next.toString() should be === "f00f:4004:2003:2002:3ddd:6666:4935:0"
    }

    def testNextOverflows() {
        var ip = IPv6Addr.fromString("f00f:4004:2003:2002:ffff:ffff:ffff:ffff")
        ip.next.toString() should be === "f00f:4004:2003:2003:0:0:0:0"

        ip = IPv6Addr.fromString("f00f:4004:2003:2002:7fff:ffff:ffff:ffff")
        ip.next.toString() should be === "f00f:4004:2003:2002:8000:0:0:0"

        ip = IPv6Addr.fromString("ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff")
        ip.next.toString() should be === "0:0:0:0:0:0:0:0"
    }

    def testRandomToWithPositiveLowerWord() {
        val start = IPv6Addr.fromString("f00f:4004:2003:2002:0:0:0:fff6")
        val end = IPv6Addr.fromString("f00f:4004:2003:2002:0:0:1:ffff")

        val a = start.randomTo(end, new FakeRandom(1))
        a.toString() should be === "f00f:4004:2003:2002:0:0:0:fff7"

        val b = start.randomTo(end, new FakeRandom(0x1f))
        b.toString() should be === "f00f:4004:2003:2002:0:0:1:15"

    }

    /**
     * Tests generating a random IPv6 addr between two addresses where the
     * increment carries from the lower to the upper word.
     */
    def testRandomToWithNegativeLowerWord() {
        val start = IPv6Addr.fromString("f00f:4004:2003:2002:ffff:ffff:ffff:fff6")
        val end = IPv6Addr.fromString("f00f:4004:2003:2002:ffff:ffff:ffff:ffff")
        // Get a random, inject a fake random that adds +11 to the given addr
        // so that 0x06 + 0x0b = 0x11. This should overflow the lower /64

        // If the random is larger than the end-start gap, should be normalized
        // The difference is 9, so 15%9 = +6
        val a = start.randomTo(end, new FakeRandom(0xf))
        a.toString() should be === "f00f:4004:2003:2002:ffff:ffff:ffff:fffc"

        // If the random is smaller
        val b = start.randomTo(end, new FakeRandom(0x2))
        b.toString() should be === "f00f:4004:2003:2002:ffff:ffff:ffff:fff8"
    }

    /**
     * Covering a divide by zero bug
     */
    def testRandomToWithSameIp() {
        val x = IPv6Addr.fromString("f00f:4004:2003:2002:ffff:ffff:ffff:fff6")
        x.randomTo(x, new Random()) should be === x
    }

    /**
     * Tests that requesting a random IP address inside a range that overflows
     * a single /64 range throws the appropriate exception
     */
    @Test
    def testRandomSubnetTooBig() {
        val start = IPv6Addr.fromString("f00f:4004:2003:2002:ffff:ffff:ffff:fff6")
        val end = IPv6Addr.fromString("f00f:4004:2003:2003:ffff:ffff:ffff:fff0")
        try {
            start.randomTo(end, null)
            fail("Expecting IllegalArgumentException")
        } catch {
            case e: IllegalArgumentException => // ok
            case e => fail("Unexpected exception {}", e)
        }
    }

    class FakeRandom(val forceNextLong: Long) extends Random {
        override def nextLong() = forceNextLong
    }

}
