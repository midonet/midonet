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

package org.midonet.midolman

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.util.MidolmanSpec
import org.midonet.odp.flows._
import org.midonet.packets._
import org.midonet.packets.util.PacketBuilder._

@RunWith(classOf[JUnitRunner])
class FlowKeysTest extends MidolmanSpec {

    scenario ("Empty payload doesn't add userspace fields") {
        val pkt = { eth src MAC.random() dst MAC.random() }
        val keys = FlowKeys.fromEthernetPacket(pkt)
        // Only ethernet and ethertype keys are added
        keys should have size 2
        keys.get(0) shouldBe a [FlowKeyEthernet]
        keys.get(1) shouldBe a [FlowKeyEtherType]
    }

    scenario ("ICMP payload adds icmp userpsace fields") {
        val pkt1 = { eth src MAC.random() dst MAC.random() } <<
                   { ip4 src IPv4Addr.random dst IPv4Addr.random } <<
                   { icmp.echo }
        val keys1 = FlowKeys.fromEthernetPacket(pkt1)
        keys1 should have size 4
        keys1.get(3) shouldBe a [FlowKeyICMP]
        keys1.get(3) shouldBe a [FlowKeyICMPEcho]

        val pkt2 = { eth src MAC.random() dst MAC.random() } <<
                   { ip4 src IPv4Addr.random dst IPv4Addr.random} <<
                   { icmp.unreach.host }
        val keys2 = FlowKeys.fromEthernetPacket(pkt2)
        keys2 should have size 4
        keys2.get(3) shouldBe a [FlowKeyICMP]
        keys2.get(3) shouldBe a [FlowKeyICMPError]
    }

    scenario ("Non-ICMP payload does not add userspace fields") {
        val pkt1 = { eth src MAC.random() dst MAC.random() } <<
                   { ip4 src IPv4Addr.random dst IPv4Addr.random } <<
                   { tcp src 42 dst 42 }
        val keys = FlowKeys.fromEthernetPacket(pkt1)
        keys should have size 5
        0 until 5 foreach {
            keys.get(_) should not be a [FlowKeyICMP]
        }
    }


}
