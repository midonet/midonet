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

package org.midonet.packets

import java.net.InetAddress
import java.util.Random

import org.junit.Test
import org.scalatest.Matchers
import org.scalatest.Suite

class IPv6AddrTest extends Suite with Matchers {

    val ippool = List.tabulate(1000) { _ => IPv6Addr.random }

    def testNext() {
        var ip = IPv6Addr.fromString("f00f:4004:2003:2002:3ddd:6666:4934:1")
        ip.next.toString() shouldEqual "f00f:4004:2003:2002:3ddd:6666:4934:2"

        ip = IPv6Addr.fromString("f00f:4004:2003:2002:3ddd:6666:4934:ffff")
        ip.next.toString() shouldEqual "f00f:4004:2003:2002:3ddd:6666:4935:0"
    }

    def testNextOverflows() {
        val ip1 = IPv6Addr.fromString("f00f:4004:2003:2002:ffff:ffff:ffff:ffff")
        ip1.lowerWord should be(-1)

        ip1.next.toString() shouldEqual "f00f:4004:2003:2003:0:0:0:0"
        ip1.next.lowerWord should be(0)

        val ip2 = IPv6Addr.fromString("f00f:4004:2003:2002:7fff:ffff:ffff:ffff")
        ip2.next.toString() shouldEqual "f00f:4004:2003:2002:8000:0:0:0"

        val ip3 = IPv6Addr.fromString("ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff")
        ip3.upperWord should be(-1)
        ip3.lowerWord should be(-1)

        ip3.next.toString() shouldEqual "0:0:0:0:0:0:0:0"
        ip3.next.upperWord should be(0)
        ip3.next.lowerWord should be(0)
    }

    def testRandomToWithPositiveLowerWord() {
        val start = IPv6Addr.fromString("f00f:4004:2003:2002:0:0:0:fff6")
        val end = IPv6Addr.fromString("f00f:4004:2003:2002:0:0:1:ffff")

        val a = start.randomTo(end, new FakeRandom(1))
        a.toString() shouldEqual "f00f:4004:2003:2002:0:0:0:fff7"

        val b = start.randomTo(end, new FakeRandom(0x1f))
        b.toString() shouldEqual "f00f:4004:2003:2002:0:0:1:15"
    }

    def testCompare() {
        for (ip <- ippool) { ip.next should be > ip }
    }

    def testRegex() {
        for (ip <- ippool) { (ip.toString matches IPv6.regex) shouldBe true }
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
        a.toString() shouldEqual "f00f:4004:2003:2002:ffff:ffff:ffff:fffc"

        // If the random is smaller
        val b = start.randomTo(end, new FakeRandom(0x2))
        b.toString() shouldEqual "f00f:4004:2003:2002:ffff:ffff:ffff:fff8"
    }

    /**
     * Covering a divide by zero bug
     */
    def testRandomToWithSameIp() {
        val x = IPv6Addr.fromString("f00f:4004:2003:2002:ffff:ffff:ffff:fff6")
        x.randomTo(x, new Random()) shouldEqual x
    }

    /**
     * Tests that requesting a random IP address inside a range that overflows
     * a single /64 range throws the appropriate exception
     */
    @Test
    def testRandomSubnetTooBig() {
        val start = IPv6Addr.fromString("f00f:4004:2003:2002:ffff:ffff:ffff:fff6")
        val end = IPv6Addr.fromString("f00f:4004:2003:2003:ffff:ffff:ffff:fff0")
        intercept[IllegalArgumentException] { start.randomTo(end, null) }
    }

    class FakeRandom(val forceNextLong: Long) extends Random {
        override def nextLong() = forceNextLong
    }

    def testRange() {
        import scala.collection.JavaConversions.iterableAsScalaIterable
        val ip1 = new IPv6Addr(1234L, 5678L)
        val ip2 = ip1.next
        val ip11 = new IPv6Addr(1234L, 5678L + 10L)
        (ip1 range ip1).toSeq should have size(1)
        (ip1 range ip2).toSeq should have size(2)
        (ip1 range ip11).toSeq should have size(11)
        (ip1 range ip11).toSeq should be((ip11 range ip1).toSeq)
    }

    @Test
    def testConvertToLongNotation() {
        IPv6Addr.convertToLongNotation("::") shouldEqual "0:0:0:0:0:0:0:0"

        IPv6Addr.convertToLongNotation("::FE01") shouldEqual
            "0:0:0:0:0:0:0:FE01"

        IPv6Addr.convertToLongNotation("2001::") shouldEqual
            "2001:0:0:0:0:0:0:0"

        IPv6Addr.convertToLongNotation("2001::0DB8") shouldEqual
            "2001:0:0:0:0:0:0:0DB8"

        IPv6Addr.convertToLongNotation("0:0:0:0:0:0:0:0") shouldEqual
            "0:0:0:0:0:0:0:0"
    }

}
