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

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{Matchers, Suite}

@RunWith(classOf[JUnitRunner])
class IPv4AddrTest extends Suite with Matchers {

    val ippool = List.tabulate(1000) { _ => IPv4Addr.random }

    def testFactory() {
        for (ip <- ippool) {
            ip should be (IPv4Addr(ip.toBytes))
            ip should be (IPv4Addr(ip.toString))
            ip should be (IPv4Addr(ip.addr))
        }
    }

    def testConversions() {
        for (ip <- ippool; intIp = ip.addr; strIp = ip.toString) {
            (strIp matches IPv4.regex) shouldBe true
            intIp should be (IPv4Addr.bytesToInt(IPv4Addr.intToBytes(intIp)))
            intIp should be (IPv4Addr.stringToInt(IPv4Addr.intToString(intIp)))
            strIp should be (IPv4Addr.bytesToString(IPv4Addr.stringToBytes(strIp)))
        }
    }

    def testConversionException() {
        //byte[] -> int / string
        List[Array[Byte]](
            null,
            Array[Byte](1,2,3,4,5),
            Array[Byte](1,2,3)
        ).foreach { array =>
            intercept[IllegalArgumentException] { IPv4Addr.bytesToInt(array) }
            intercept[IllegalArgumentException] { IPv4Addr.bytesToString(array) }
        }

        // string -> int / byte[]
        List[String](
            null,
            "eewofihewiofh",
            "ww:ww:ww:ww:ww:ww",
            "234.234.10",
            "12.235.34.34.45",
            "10.10.2.23456"
        ).foreach { str =>
            intercept[IllegalArgumentException] { IPv4Addr.stringToInt(str) }
            intercept[IllegalArgumentException] { IPv4Addr.stringToBytes(str) }
        }

    }

    // IPs that have a negative int representation
    def testNextWithNegativeIp() {
        var ip = IPv4Addr.fromString("192.168.1.1")
        val iIp = ip.addr
        ip = ip.next
        ip.toString should be ("192.168.1.2")
        ip.toInt should be (iIp + 1)

        Predef.intWrapper(1).to(255).foreach(_ => ip = ip.next)
        ip.toString() should be ("192.168.2.1")
    }

    def testNext() {
        var ip = IPv4Addr.fromString("10.1.23.1")
        ip = ip.next
        ip.toString() should be ("10.1.23.2")

        Predef.intWrapper(1).to(255).foreach(_ => ip = ip.next)
        ip.toString() should be ("10.1.24.1")

        ip = IPv4Addr.fromString("10.1.255.255")
        ip.next.toString() should be ("10.2.0.0")

        IPv4Addr(Integer.MAX_VALUE).next should be(IPv4Addr(Integer.MIN_VALUE))
    }

    def testCompare() {
        val ip1 = IPv4Addr.fromString("192.168.1.1")
        val ip11 = IPv4Addr.fromString("192.168.1.1")
        val ip2 = IPv4Addr.fromString("192.168.1.2")
        val ip3 = IPv4Addr.fromString("10.3.1.2")

        ip1.compare(ip2) should be (-1)
        ip1.compare(ip1) should be (0)
        ip1.compare(ip11) should be (0)
        ip3.compare(ip1) should be (1)

        for (ip <- ippool) { ip.next should be > ip }
    }

    def testRandomInRange() {
        val ip1 = IPv4Addr("192.168.1.1")
        val ip2 = IPv4Addr("192.168.5.1")

        val ip3 = IPv4Addr("10.1.3.4")
        val ip4 = IPv4Addr("10.1.4.0")

        var r1 = ip1
        do {
            r1 = ip1.randomTo(ip2, new Random())
        } while (r1.equals(ip1) || r1.equals(ip2))

        var r2 = ip3
        do {
            r2 = ip3.randomTo(ip4, new Random())
        } while (r2.equals(ip3) || r2.equals(ip4))


        r1.compare(ip1) should be (1)
        r1.compare(ip2) should be (-1)

        r2.compare(ip3) should be (1)
        r2.compare(ip4) should be (-1)

    }

    def testRange() {
        import scala.collection.JavaConversions.iterableAsScalaIterable
        val ip1 = IPv4Addr(123456)
        val ip2 = IPv4Addr(ip1.addr + 1)
        val ip11 = IPv4Addr(ip1.addr + 10)
        (ip1 range ip1).toSeq should have size(1)
        (ip1 range ip2).toSeq should have size(2)
        (ip1 range ip11).toSeq should have size(11)
        (ip1 range ip11).toSeq should be((ip11 range ip1).toSeq)

        val iter = (ip1 range ip2).iterator
        intercept[UnsupportedOperationException] { iter.remove }
        iter.next
        iter.next
        iter.hasNext shouldEqual false
        intercept[java.util.NoSuchElementException] { iter.next }
    }
}
