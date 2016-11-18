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

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FunSpec, Matchers}

@RunWith(classOf[JUnitRunner])
class IPv4SubnetTest extends FunSpec with Matchers {

    describe("IPv4Subnet") {
        it("should discriminate IPv4 address within and outside the network") {
            List[(Int,Int,Int,Boolean)](
                (0x0a0101fd, 0x0a0101f8, 1, true),
                (0x0a0101fd, 0x0a0101f8, 5, true),
                (0x0a0101fd, 0x0a0101f8, 17, true),
                (0x0a0101fd, 0x0a0101f8, 21, true),
                (0x0a0101fd, 0x0a0101f8, 28, true),
                (0x0a0101fd, 0x0a0101f8, 29, true),
                (0x0a0101fd, 0x0a0101f8, 30, false),
                (0x0a0101fd, 0x0a0101f8, 32, false),

                (0x01234567, 0x87654321, 0, true),
                (0x01234567, 0x87654321, 1, false),
                (0x01234567, 0x87654321, 5, false),
                (0x01234567, 0x87654321, 20, false),

                (0xa0a0a0a0, 0xa0a05050, 15, true),
                (0xa0a0a0a0, 0xa0a05050, 16, true),
                (0xa0a0a0a0, 0xa0a05050, 17, false),
                (0xa0a0a0a0, 0xa0a05050, 20, false),
                (0xa0a0a0a0, 0xa0a05050, 25, false)
            ) foreach { case (ip1, ip2, len, result) =>
                val addr1 = IPv4Addr(ip1)
                val addr2 = IPv4Addr(ip2)
                val sub1 = new IPv4Subnet(addr1, len)
                val sub2 = new IPv4Subnet(addr2, len)
                (sub1 containsAddress addr2) shouldBe result
                (sub2 containsAddress addr1) shouldBe result
                (sub1 containsAddress null) shouldBe false
                IPv4Subnet.addrMatch(ip1,ip2,len) shouldBe result
                IPv4Subnet.addrMatch(ip2,ip1,len) shouldBe result
            }
        }

        it("should convert to string") {
            List[(Int,Int,String,String)](
                (0x0a0101fd, 0, "10.1.1.253/0", "10.1.1.253"),
                (0x0a010000, 0, "10.1.0.0/0", "10.1.0.0"),
                (0x0a0101fd, 16, "10.1.1.253/16", "10.1.1.253"),
                (0x0a010000, 16, "10.1.0.0/16", "10.1.0.0"),
                (0x0a0101fd, 32, "10.1.1.253/32", "10.1.1.253"),
                (0x0a010000, 32, "10.1.0.0/32", "10.1.0.0")
            ) foreach { case (ip, prefix, str, unicast) =>
                val subnet = new IPv4Subnet(ip, prefix)
                subnet.toString shouldBe str
                subnet.toUnicastString shouldBe unicast
            }
        }

        it("should return the network and broadcast addresses") {
            List[(Int,Int,String,String)](
                (0x0a010000, 0, "0.0.0.0", "255.255.255.255"),
                (0x0a0101fd, 16, "10.1.0.0", "10.1.255.255"),
                (0x0a010000, 16, "10.1.0.0", "10.1.255.255"),
                (0x0a0101fd, 32, "10.1.1.253", "10.1.1.253")
            ) foreach { case (ip, prefix, network, broadcast) =>
                val subnet = new IPv4Subnet(ip, prefix)
                subnet.toNetworkAddress shouldBe IPv4Addr(network)
                subnet.toBroadcastAddress shouldBe IPv4Addr(broadcast)
            }
        }

        it("should create instance from string") {
            List[(String, Int, Int)](
                ("10.1.0.0", 16, 0x0a010000),
                ("10.1.1.253", 16, 0x0a0101fd)
            ) foreach { case (str, prefix, address) =>
                val subnet = new IPv4Subnet(str, prefix)
                subnet.getIntAddress shouldBe address
                subnet.getPrefixLen shouldBe prefix
            }
        }

        it("should create instance from bytes") {
            List[(Array[Byte], Int, Int)](
                (Array(10.toByte, 1.toByte, 0.toByte, 0.toByte), 16, 0x0a010000),
                (Array(10.toByte, 1.toByte, 1.toByte, 253.toByte), 16, 0x0a0101fd)
            ) foreach { case (bytes, prefix, address) =>
                val subnet = new IPv4Subnet(bytes, prefix)
                subnet.getIntAddress shouldBe address
                subnet.getPrefixLen shouldBe prefix
            }
        }

        it("should parse from string") {
            List[(String, Int, Int)](
                ("10.1.0.0/16", 16, 0x0a010000),
                ("10.1.1.253/16", 16, 0x0a0101fd),
                (" 10.1.0.0/16 ", 16, 0x0a010000),
                (" 10.1.1.253/16 ", 16, 0x0a0101fd)
            ) foreach { case (str, prefix, address) =>
                val subnet = IPv4Subnet.fromCidr(str)
                subnet.getIntAddress shouldBe address
                subnet.getPrefixLen shouldBe prefix
            }

            intercept[IllegalArgumentException] { IPv4Subnet.fromCidr("") }
        }

        it("should parse from ZK string") {
            List[(String, Int, Int)](
                ("10.1.0.0_16", 16, 0x0a010000),
                ("10.1.1.253_16", 16, 0x0a0101fd),
                (" 10.1.0.0_16 ", 16, 0x0a010000),
                (" 10.1.1.253_16 ", 16, 0x0a0101fd)
            ) foreach { case (str, prefix, address) =>
                val subnet = IPv4Subnet.fromUriCidr(str)
                subnet.getIntAddress shouldBe address
                subnet.getPrefixLen shouldBe prefix
            }
        }

        it("should convert prefix length to bytes") {
            List[(Int, Array[Byte])](
                (4, Array(0xf0.toByte, 0.toByte, 0.toByte, 0.toByte)),
                (8, Array(0xff.toByte, 0.toByte, 0.toByte, 0.toByte)),
                (12, Array(0xff.toByte, 0xf0.toByte, 0.toByte, 0.toByte)),
                (16, Array(0xff.toByte, 0xff.toByte, 0.toByte, 0.toByte)),
                (24, Array(0xff.toByte, 0xff.toByte, 0xff.toByte, 0.toByte)),
                (32, Array(0xff.toByte, 0xff.toByte, 0xff.toByte, 0xff.toByte))
            ) foreach { case (prefix, bytes) =>
                IPv4Subnet.prefixLenToBytes(prefix) shouldBe bytes
            }
        }

        it("should return correct ethertype") {
            IPv4Subnet.fromCidr("1.2.3.4/24").ethertype() shouldBe IPv4.ETHERTYPE
        }
    }
}
