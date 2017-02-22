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

import scala.util.Random

import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

import org.midonet.netlink.rtnetlink.Link.NestedAttrValue
import org.midonet.netlink.rtnetlink.{Link, LinkOps}
import org.midonet.packets.{IPv4Subnet, MAC}


@RunWith(classOf[JUnitRunner])
class LinkOpsTest extends FeatureSpec with BeforeAndAfterAll with ShouldMatchers {

    private def rndInt(s: Int, e: Int) = s + Random.nextInt(e - s)
    private def rndIfaceName = Random.alphanumeric.take(rndInt(5,14)).mkString
    private def rndBool = Random.nextBoolean

    feature("Veth pair") {
        scenario ("Can create and delete") {
            val devName = rndIfaceName
            val devMac = MAC.random
            val devMtu = rndInt(1500, 65536)
            val peerName = rndIfaceName
            val peerMac = MAC.random
            val peerMtu = rndInt(1500, 65536)
            val up = rndBool
            val LinkOps.Veth(dev, peer) = LinkOps.createVethPair(
                devName, peerName, up, devMac, peerMac, devMtu,peerMtu
            )

            dev.getName shouldBe devName
            peer.getName shouldBe peerName
            if (up) {
                (dev.ifi.flags & Link.Flag.IFF_UP) shouldBe Link.Flag.IFF_UP
                (peer.ifi.flags & Link.Flag.IFF_UP) shouldBe Link.Flag.IFF_UP
            } else {
                (dev.ifi.flags & Link.Flag.IFF_UP) should not be
                    Link.Flag.IFF_UP
                (peer.ifi.flags & Link.Flag.IFF_UP) should not be
                    Link.Flag.IFF_UP
            }


            dev.info.kind shouldBe NestedAttrValue.LinkInfo.KIND_VETH
            peer.info.kind shouldBe NestedAttrValue.LinkInfo.KIND_VETH
            dev.mtu shouldBe devMtu
            peer.mtu shouldBe peerMtu
            dev.mac shouldBe devMac
            peer.mac shouldBe peerMac

            LinkOps.setAddress(dev, IPv4Subnet.fromCidr("10.0.0.1/24"))
            LinkOps.setAddress(peer, IPv4Subnet.fromCidr("10.0.0.2/24"))

            LinkOps.deleteLink(dev)
        }
    }

    feature("Bridge") {
        scenario ("Can create and delete") {
            val bridgeName = rndIfaceName
            val bridgeMac = MAC.random
            val bridgeMtu = rndInt(1500, 65536)
            val up = rndBool
            val bridge = LinkOps.createBridge(
                bridgeName, up, bridgeMac, bridgeMtu
            )

            bridge.getName shouldBe bridgeName
            bridge.info.kind shouldBe NestedAttrValue.LinkInfo.KIND_BRIDGE
            if (up) {
                (bridge.ifi.flags & Link.Flag.IFF_UP) shouldBe Link.Flag.IFF_UP
            } else {
                (bridge.ifi.flags & Link.Flag.IFF_UP) should not be
                    Link.Flag.IFF_UP
            }
            bridge.mac shouldBe bridgeMac
            bridge.mtu shouldBe bridgeMtu

            LinkOps.setAddress(bridge, IPv4Subnet.fromCidr("10.0.0.1/24"))

            LinkOps.deleteLink(bridge)
        }
    }
}
