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
        "192.168.0.0", "192.168.0.1", "192.168.0.2", "192.168.0.3",
        "192.168.0.100", "192.168.0.101", "192.168.0.254", "192.168.0.255",
        "192.168.1.0", "192.168.1.1", "192.168.1.2", "192.168.1.3",
        "192.0.1.0", "192.0.1.1", "192.0.1.2", "192.0.1.3",
        "1.0.1.0", "1.0.1.1", "1.0.1.2", "1.0.1.3")

    override def beforeEach() {
        array = new IPv4InvalidationArray()
    }

    feature("Invaliation array stores and deletes values") {
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
            array.ref("10.0.0.0", 32)
            array.ref("10.0.0.0", 32)

            array should not be `empty`
            array.nonEmpty should be (true)

            array.unref("10.0.0.0")
            array should not be `empty`

            array.unref("10.0.0.0")
            array should be (`empty`)
        }

        scenario("Unref'ing a non existent entry returns NO_VALUE") {
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
            array.ref("10.0.0.0", 32)
            array.ref("10.0.0.1", 20)
            array.ref("10.0.0.2", 15)
            array.ref("10.0.0.3", 12)
            array.ref("11.0.0.3", 0)
            array.ref("11.0.0.2", 0)
            array.ref("11.0.0.20", 0)

            array.deletePrefix("10.0.0.0", 9) should have size 0
            array.deletePrefix("10.0.0.0", 13) should have size 1
            array.deletePrefix("10.0.0.0", 15) should have size 1
            array.deletePrefix("10.0.0.0", 24) should have size 1
            array.deletePrefix("10.0.0.0", 32) should have size 1
            array("11.0.0.3") should be (0)
            val result = array.deletePrefix("11.0.0.0", 30)
            result should have size 2
            result should contain (IPv4Addr.fromString("11.0.0.3").toInt)
            result should contain (IPv4Addr.fromString("11.0.0.2").toInt)
        }

        scenario("tracks reference counts") {
            for (i <- 1 to 100) { array.ref("10.0.0.0", 24) }
            for (i <- 99 to(-1, -1)) { array.unref("10.0.0.0") should be (i) }
        }

        scenario("Cleans up trie nodes") {
            array.ref("10.0.0.0", 0)
            array.ref("10.0.0.0", 0)
            array.ref("10.0.0.1", 24)
            array.ref("10.0.0.1", 24)

            array.deletePrefix("10.0.0.0", 16) should have size 1
            array should not be `empty`
            array.deletePrefix("10.0.0.1", 32) should have size 1
            array.nonEmpty should be (false)
            array should be (`empty`)
        }
    }

}
