/*
 * Copyright 2016 Midokura SARL
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
class IPv6SubnetTest extends FunSpec with Matchers {

    describe("IPv6Subnet") {
        it("should discriminate IPv6 address within and outside the network") {
            List[(Long, Long, Long, Long, Int, Boolean)](
                (0x2001aaaabbbbccccL, 0xddddeeeeffff0000L,
                    0x2001aaaabbbbccccL, 0xddddee7788889999L, 16, true),
                (0x2001aaaabbbbccccL, 0xddddeeeeffff0000L,
                    0x2001aaaabbbbccccL, 0xddddee7788889999L, 32, true),
                (0x2001aaaabbbbccccL, 0xddddeeeeffff0000L,
                    0x2001aaaabbbbccccL, 0xddddee7788889999L, 48, true),
                (0x2001aaaabbbbccccL, 0xddddeeeeffff0000L,
                    0x2001aaaabbbbccccL, 0xddddee7788889999L, 64, true),
                (0x2001aaaabbbbccccL, 0xddddeeeeffff0000L,
                    0x2001aaaabbbbccccL, 0xddddee7788889999L, 80, true),
                (0x2001aaaabbbbccccL, 0xddddeeeeffff0000L,
                    0x2001aaaabbbbccccL, 0xddddee7788889999L, 88, true),
                (0x2001aaaabbbbccccL, 0xddddeeeeffff0000L,
                    0x2001aaaabbbbccccL, 0xddddee7788889999L, 96, false),
                (0x2001aaaabbbbccccL, 0xddddeeeeffff0000L,
                    0x2001aaaabbbbccccL, 0xddddee7788889999L, 112, false),
                (0x2001aaaabbbbccccL, 0xddddeeeeffff0000L,
                    0x2001aaaabbbbccccL, 0xddddee7788889999L, 128, false),

                (0x2001aaaabbbbccccL, 0xddddeeeeffff0000L,
                    0x3001aaaabbbbccccL, 0xddddeeeeffff0000L, 0, true),
                (0x2001aaaabbbbccccL, 0xddddeeeeffff0000L,
                    0x3001aaaabbbbccccL, 0xddddeeeeffff0000L, 32, false),
                (0x2001aaaabbbbccccL, 0xddddeeeeffff0000L,
                    0x3001aaaabbbbccccL, 0xddddeeeeffff0000L, 64, false),
                (0x2001aaaabbbbccccL, 0xddddeeeeffff0000L,
                    0x3001aaaabbbbccccL, 0xddddeeeeffff0000L, 96, false),

                (0x2001aaaabbbbccccL, 0xddddeeeeffff0000L,
                    0x2001aaaaddddeeeeL, 0x0123456789abcdefL, 32, true),
                (0x2001aaaabbbbccccL, 0xddddeeeeffff0000L,
                    0x2001aaaaddddeeeeL, 0x0123456789abcdefL, 33, true),
                (0x2001aaaabbbbccccL, 0xddddeeeeffff0000L,
                    0x2001aaaaddddeeeeL, 0x0123456789abcdefL, 34, false)
                ) foreach { case (subnetM, subnetL, addressM, addressL, prefix, result) =>
                val subnet = new IPv6Subnet(IPv6Addr(subnetM, subnetL), prefix)
                val address = IPv6Addr(addressM, addressL)
                (subnet containsAddress address) shouldBe result
                (subnet containsAddress null) shouldBe false
            }
        }

        it("should convert to string") {
            List[(Long,Long,Int,String)](
                (0x2001aaaabbbbccccL, 0xddddeeeeffff0000L, 0,
                    "0:0:0:0:0:0:0:0/0"),
                (0x2001aaaabbbbccccL, 0xddddeeeeffff0000L, 16,
                    "2001:0:0:0:0:0:0:0/16"),
                (0x2001aaaabbbbccccL, 0xddddeeeeffff0000L, 32,
                    "2001:aaaa:0:0:0:0:0:0/32"),
                (0x2001aaaabbbbccccL, 0xddddeeeeffff0000L, 48,
                    "2001:aaaa:bbbb:0:0:0:0:0/48"),
                (0x2001aaaabbbbccccL, 0xddddeeeeffff0000L, 64,
                    "2001:aaaa:bbbb:cccc:0:0:0:0/64"),
                (0x2001aaaabbbbccccL, 0xddddeeeeffff0000L, 88,
                    "2001:aaaa:bbbb:cccc:dddd:ee00:0:0/88"),
                (0x2001aaaabbbbccccL, 0xddddeeeeffff0000L, 128,
                    "2001:aaaa:bbbb:cccc:dddd:eeee:ffff:0/128")
            ) foreach { case (subnetM, subnetL, prefix, str) =>
                val subnet = new IPv6Subnet(IPv6Addr(subnetM, subnetL), prefix)
                subnet.toString shouldBe str
            }
        }

        it("should return the network and broadcast addresses") {
            List[(Long,Long,Int,String,String)](
                (0x2001aaaabbbbccccL, 0xddddeeeeffff0000L, 0,
                    "0:0:0:0:0:0:0:0",
                    "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff"),
                (0x2001aaaabbbbccccL, 0xddddeeeeffff0000L, 16,
                    "2001:0:0:0:0:0:0:0",
                    "2001:ffff:ffff:ffff:ffff:ffff:ffff:ffff"),
                (0x2001aaaabbbbccccL, 0xddddeeeeffff0000L, 32,
                    "2001:aaaa:0:0:0:0:0:0",
                    "2001:aaaa:ffff:ffff:ffff:ffff:ffff:ffff"),
                (0x2001aaaabbbbccccL, 0xddddeeeeffff0000L, 48,
                    "2001:aaaa:bbbb:0:0:0:0:0",
                    "2001:aaaa:bbbb:ffff:ffff:ffff:ffff:ffff"),
                (0x2001aaaabbbbccccL, 0xddddeeeeffff0000L, 64,
                    "2001:aaaa:bbbb:cccc:0:0:0:0",
                    "2001:aaaa:bbbb:cccc:ffff:ffff:ffff:ffff"),
                (0x2001aaaabbbbccccL, 0xddddeeeeffff0000L, 80,
                    "2001:aaaa:bbbb:cccc:dddd:0:0:0",
                    "2001:aaaa:bbbb:cccc:dddd:ffff:ffff:ffff"),
                (0x2001aaaabbbbccccL, 0xddddeeeeffff0000L, 96,
                    "2001:aaaa:bbbb:cccc:dddd:eeee:0:0",
                    "2001:aaaa:bbbb:cccc:dddd:eeee:ffff:ffff"),
                (0x2001aaaabbbbccccL, 0xddddeeeeffff0000L, 112,
                    "2001:aaaa:bbbb:cccc:dddd:eeee:ffff:0",
                    "2001:aaaa:bbbb:cccc:dddd:eeee:ffff:ffff"),
                (0x2001aaaabbbbccccL, 0xddddeeeeffff0000L, 128,
                    "2001:aaaa:bbbb:cccc:dddd:eeee:ffff:0",
                    "2001:aaaa:bbbb:cccc:dddd:eeee:ffff:0")
            ) foreach { case (subnetM, subnetL, prefix, network, broadcast) =>
                val subnet = new IPv6Subnet(IPv6Addr(subnetM, subnetL), prefix)
                subnet.toNetworkAddress shouldBe IPv6Addr(network)
                subnet.toBroadcastAddress shouldBe IPv6Addr(broadcast)
            }
        }

        it("should create instance from string") {
            List[(Long, Long, Int, String)](
                (0x2001aaaabbbbccccL, 0xddddeeeeffff0000L, 0,
                    "2001:aaaa:bbbb:cccc:dddd:eeee:ffff:0"),
                (0x2001aaaabbbbccccL, 0xddddeeeeffff0000L, 64,
                    "2001:aaaa:bbbb:cccc:dddd:eeee:ffff:0"),
                (0x2001aaaabbbbccccL, 0xddddeeeeffff0000L, 128,
                    "2001:aaaa:bbbb:cccc:dddd:eeee:ffff:0")
            ) foreach { case (subnetM, subnetL, prefix, str) =>
                val subnet = new IPv6Subnet(str, prefix)
                subnet.getAddress.upperWord shouldBe subnetM
                subnet.getAddress.lowerWord shouldBe subnetL
                subnet.getPrefixLen shouldBe prefix
            }
        }

        it("should create instance from bytes") {
            List[(Long, Long, Int, Array[Byte])](
                (0x2001aaaabbbbccccL, 0xddddeeeeffff0000L, 0,
                    Array(0x20.toByte, 1.toByte, 0xaa.toByte, 0xaa.toByte,
                          0xbb.toByte, 0xbb.toByte, 0xcc.toByte, 0xcc.toByte,
                          0xdd.toByte, 0xdd.toByte, 0xee.toByte, 0xee.toByte,
                          0xff.toByte, 0xff.toByte, 0.toByte, 0.toByte)),
                (0x2001aaaabbbbccccL, 0xddddeeeeffff0000L, 64,
                    Array(0x20.toByte, 1.toByte, 0xaa.toByte, 0xaa.toByte,
                          0xbb.toByte, 0xbb.toByte, 0xcc.toByte, 0xcc.toByte,
                          0xdd.toByte, 0xdd.toByte, 0xee.toByte, 0xee.toByte,
                          0xff.toByte, 0xff.toByte, 0.toByte, 0.toByte))
            ) foreach { case (subnetM, subnetL, prefix, bytes) =>
                val subnet = new IPv6Subnet(bytes, prefix)
                subnet.getAddress.upperWord shouldBe subnetM
                subnet.getAddress.lowerWord shouldBe subnetL
                subnet.getPrefixLen shouldBe prefix
            }
        }

        it("should parse from string") {
            List[(Long, Long, Int, String)](
                (0x2001aaaabbbbccccL, 0xddddeeeeffff0000L, 0,
                    "2001:aaaa:bbbb:cccc:dddd:eeee:ffff:0/0"),
                (0x2001aaaabbbbccccL, 0xddddeeeeffff0000L, 64,
                    "2001:aaaa:bbbb:cccc:dddd:eeee:ffff:0/64"),
                (0x2001aaaabbbbccccL, 0xddddeeeeffff0000L, 128,
                    "2001:aaaa:bbbb:cccc:dddd:eeee:ffff:0/128"),
                (0x2001aaaabbbbccccL, 0xddddeeeeffff0000L, 128,
                    "2001:aaaa:bbbb:cccc:dddd:eeee:ffff:0"),
                (0x2001aaaabbbbccccL, 0x0L, 32, "2001:aaaa:bbbb:cccc::/32"),
                (0x0L, 0x0L, 32, "::/32")
            ) foreach { case (subnetM, subnetL, prefix, str) =>
                val subnet = IPv6Subnet.fromCidr(str)
                subnet.getAddress.upperWord shouldBe subnetM
                subnet.getAddress.lowerWord shouldBe subnetL
                subnet.getPrefixLen shouldBe prefix
            }

            intercept[IllegalArgumentException] { IPv4Subnet.fromCidr("") }
        }

        it("should return correct ethertype") {
            IPv6Subnet.fromCidr("2001::/64").ethertype() shouldBe IPv6.ETHERTYPE
        }

        /*

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

*/
    }
}