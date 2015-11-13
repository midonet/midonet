/*
 * Copyright 2015 Midokura SARL
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

package org.midonet.odp

import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

import org.midonet.netlink.rtnetlink.{Link, LinkOps}
import org.midonet.packets.{IPv4Subnet, IPv4Addr, MAC}


@RunWith(classOf[JUnitRunner])
class LinkOpsTest extends FeatureSpec with BeforeAndAfterAll with ShouldMatchers {

    private val devMac = MAC.random()
    private val peerMac = MAC.random()

    feature("Veth pair") {
        scenario ("Can create and delete") {
            val LinkOps.Veth(dev, peer) = LinkOps.createVethPair(
                "fluffy",
                "sparkles",
                up = true,
                devMac,
                peerMac,
                9000,
                9000
            )

            dev.getName should be ("fluffy")
            peer.getName should be ("sparkles")
            (dev.ifi.flags & Link.Flag.IFF_UP) should be (Link.Flag.IFF_UP)
            dev.mtu should be (9000)
            peer.mtu should be (9000)
            dev.mac should be (devMac)
            peer.mac should be (peerMac)

            LinkOps.setAddress(dev, IPv4Subnet.fromString("10.0.0.1/24", "/"))
            val addr = LinkOps.setAddress(peer, IPv4Subnet.fromString("10.0.0.2/24", "/"))
            addr.ipv4.get(0) should be (IPv4Addr.fromString("10.0.0.2"))
            addr.ifa.prefixLen should be (24)

            LinkOps.deleteLink(dev)
        }
    }
}
