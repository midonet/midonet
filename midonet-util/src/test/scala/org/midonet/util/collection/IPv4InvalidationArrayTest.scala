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

package org.midonet.util.collection

import org.junit.runner.RunWith
import org.midonet.packets.IPv4Addr
import org.scalatest.{FeatureSpec, BeforeAndAfterEach, Matchers}
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class IPv4InvalidationArrayTest extends FeatureSpec with BeforeAndAfterEach with Matchers {

    var array: IPv4InvalidationArray = null

    implicit def str2ipv4(str: String): IPv4Addr = IPv4Addr.fromString(str)
    implicit def ip2int(ip: IPv4Addr): Int = ip.toInt
    implicit def str2int(ip: String): Int = ip2int(str2ipv4(ip))

    val ints: List[Int] = List(
        "192.168.0.0", "192.168.0.16", "192.168.0.32", "192.168.0.48",
        "192.168.0.100", "192.168.0.116", "192.168.0.200", "192.168.0.255",
        "192.168.1.0", "192.168.1.16", "192.168.1.32", "192.168.1.48",
        "192.168.1.100", "192.168.1.116", "192.168.1.200", "192.168.1.255",
        "1.0.1.0", "1.0.1.16", "1.0.1.32", "1.0.1.48")

    override def beforeEach() {
        array = new IPv4InvalidationArray()
    }

    feature("Invalidation array stores and deletes values") {
        scenario("Returns -1 when unref'ing an unknown address") {
            array.unref(0x12345678) should be (-1)
            array.ref(0x12345678 + 0x10, 31)
            array.unref(0x12345678) should be (-1)
        }

        scenario("Drops the last 4 bits of each address") {
            val base = 0x89abcd10
            for (i <- 0 to 0xf) {
                array.ref(base + i, 27) should be (i+1)
                array.ref(base + i + 0x10, 19) should be (i+1)
            }

            for (i <- 0xf to(0, -1)) {
                array.apply(base + i) should be (27)
                array.unref(base + i) should be (i)
                array.apply(base + i + 0x10) should be (19)
                array.unref(base + i + 0x10) should be (i)
            }
        }

        scenario("Stores values") {
            var v = 0
            for (i <- ints) {
                array.ref(i, v)
                v += 1
            }

            v = 0
            for (i <- ints) {
                array(i) should be (v)
                v += 1
            }
        }

        scenario("Deletes values") {
            var v = 0
            for (i <- ints) {
                array.ref(i, v)
                v += 1
            }

            v = 0
            for (i <- ints) {
                array.unref(i) should be (0)
                array(i) should be (IPv4InvalidationArray.NO_VALUE)
                v += 1
            }
        }

        scenario("Cleans up trie nodes") {
            array.ref("10.0.0.0", 31)
            array.ref("10.0.0.0", 31)

            array should not be `empty`
            array.nonEmpty should be (true)

            array.unref("10.0.0.0")
            array should not be `empty`

            array.unref("10.0.0.0")
            array should be (`empty`)
        }

        scenario("Ignores /32 matches") {
            array.unref("10.0.0.0") should be (-1)
            array.ref("10.0.0.1", 32)
            array.unref("10.0.0.0") should be (-1)
        }
    }

    feature("Invaliation invalidates route matches") {
        scenario("Doesn't invalidate for a less specific route") {
            for (i <- ints) { array.ref(i, 19) }
            for (i <- ints) { array.deletePrefix(i, 14) should have size 0 }
        }

        scenario("Invalidates for more specific routes") {
            for (i <- ints) { array.ref(i, 19) }
            for (i <- ints) { array.deletePrefix(i, 32) should have size 1 }
        }

        scenario("Invalidates subnets") {
            array.ref("10.0.0.0", 31)
            array.ref("10.0.0.17", 20)
            array.ref("10.0.0.34", 15)
            array.ref("10.0.0.49", 12)
            array.ref("11.0.0.3", 0)
            array.ref("11.0.0.1", 0)
            array.ref("11.0.0.20", 0)

            array.deletePrefix("10.0.0.0", 9) should have size 0
            array.deletePrefix("10.0.0.48", 13) should have size 1
            array.deletePrefix("10.0.0.33", 16) should have size 1
            array.deletePrefix("10.0.0.16", 24) should have size 1
            array.deletePrefix("10.0.0.1", 32) should have size 1
            array("11.0.0.3") should be (0)
            val result = array.deletePrefix("11.0.0.0", 25)
            result should have size 2
            result should contain (IPv4Addr.fromString("11.0.0.0").toInt)
            result should contain (IPv4Addr.fromString("11.0.0.16").toInt)
        }

        scenario("tracks reference counts") {
            for (i <- 1 to 100) { array.ref("10.0.0.0", 24) }
            for (i <- 99 to(-1, -1)) { array.unref("10.0.0.0") should be (i) }
        }

        scenario("Cleans up trie nodes") {
            array.ref("10.0.0.0", 0)
            array.ref("10.0.0.0", 0)
            array.ref("10.0.0.100", 24)
            array.ref("10.0.0.100", 24)

            array.deletePrefix("10.0.0.0", 16) should have size 1
            array should not be `empty`
            array.deletePrefix("10.0.0.100", 32) should have size 1
            array.nonEmpty should be (false)
            array should be (`empty`)
        }
    }

}
