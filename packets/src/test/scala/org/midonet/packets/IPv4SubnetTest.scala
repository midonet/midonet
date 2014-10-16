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

package org.midonet.packets;

import scala.util.Random

import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class IPv4SubnetTest extends FunSpec with Matchers {

    describe("IPv4Subnet") {
        it("should discriminate ipv4 addr within and outside the network") {
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
            ) foreach { case (ip1,ip2,len,result) =>
                val addr1 = IPv4Addr(ip1)
                val addr2 = IPv4Addr(ip2)
                val sub1 = new IPv4Subnet(addr1, len)
                val sub2 = new IPv4Subnet(addr2, len)
                (sub1 containsAddress addr2) shouldBe result
                (sub2 containsAddress addr1) shouldBe result
                IPv4Subnet.addrMatch(ip1,ip2,len) shouldBe result
                IPv4Subnet.addrMatch(ip2,ip1,len) shouldBe result
            }
        }
    }
}
