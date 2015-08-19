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
package org.midonet.quagga

import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

import org.midonet.packets.{MAC, IPv4Addr, IPv4Subnet}
import org.midonet.quagga.BgpdConfiguration._

@RunWith(classOf[JUnitRunner])
class BgpdTest extends Suite with BeforeAndAfter with ShouldMatchers {
    val idx = 5
    val PREFIX = 172 * (1<<24) + 23 * (1<<16)
    val BGP_VTY_LOCAL_IP = new IPv4Subnet(IPv4Addr.fromInt(PREFIX + 1 + 4 * idx), 30)
    val BGP_VTY_MIRROR_IP = new IPv4Subnet(IPv4Addr.fromInt(PREFIX + 2 + 4 * idx), 30)
    val BGP_VTY_PORT = 2605 + idx

    val routerAddress = IPv4Subnet.fromCidr("192.168.163.1/24")
    val routerMac = MAC.random()

    var quaggaEnv: QuaggaEnvironment = _

    implicit def str2subnet(str: String) = IPv4Subnet.fromCidr(str)
    implicit def str2ipv4(str: String) = IPv4Addr.fromString(str)

    before {
        quaggaEnv = new DefaultBgpdEnvironment(idx,
            BGP_VTY_LOCAL_IP, BGP_VTY_MIRROR_IP,
            routerAddress, routerMac, BGP_VTY_PORT,
            "./src/lib/midolman/bgpd-helper",
            System.getProperty("user.dir") + "/src/deb/quagga/bgpd.conf")
    }

    after {
        if (quaggaEnv ne null)
            quaggaEnv.stop()
    }

    def testBgpdStartsAndStops(): Unit = {
        quaggaEnv.prepare()
        quaggaEnv.start()
        quaggaEnv.isAlive should be (true)
        quaggaEnv.stop() should be (true)
    }

    def testInitialConfig(): Unit = {
        quaggaEnv.prepare()
        quaggaEnv.start()

        quaggaEnv.bgpVty.showConfig() should be (
            BgpdRunningConfig(debug = false,
                Some("bgpd"),
                Some("bgpd.log"),
                Some("zebra_password"))
        )
    }

    def testConfigureRouter(): Unit = {
        quaggaEnv.prepare()
        quaggaEnv.start()

        quaggaEnv.bgpVty.setAs(23)

        quaggaEnv.bgpVty.showConfig() should be (
            BgpdRunningConfig(debug = false,
                Some("bgpd"),
                Some("bgpd.log"),
                Some("zebra_password"),
                router = Some(BgpRouter(23)))
        )

        quaggaEnv.bgpVty.setRouterId(23, routerAddress.getAddress)

        quaggaEnv.bgpVty.showConfig() should be (
            BgpdRunningConfig(debug = false,
                Some("bgpd"),
                Some("bgpd.log"),
                Some("zebra_password"),
                router = Some(BgpRouter(23, routerAddress.getAddress)))
        )
    }

    def testAdvertiseNetworks(): Unit = {
        quaggaEnv.prepare()
        quaggaEnv.start()
        val empty = BgpdRunningConfig(debug = false,
                Some("bgpd"),
                Some("bgpd.log"),
                Some("zebra_password"),
                router = Some(BgpRouter(23)))

        val oneNetwork = empty.copy(
                router = Some(empty.router.get.copy(
                    networks = Set(Network("10.0.10.0/24")))))
        val twoNetworks = oneNetwork.copy(router = Some(oneNetwork.router.get.copy(
                    networks = Set(Network("10.0.10.0/24"), Network("10.0.20.0/24")))))

        quaggaEnv.bgpVty.setAs(23)

        quaggaEnv.bgpVty.addNetwork(23, IPv4Subnet.fromCidr("10.0.10.0/24"))
        quaggaEnv.bgpVty.showConfig() should be (oneNetwork)

        quaggaEnv.bgpVty.addNetwork(23, IPv4Subnet.fromCidr("10.0.20.0/24"))
        quaggaEnv.bgpVty.showConfig() should be (twoNetworks)

        quaggaEnv.bgpVty.deleteNetwork(23, IPv4Subnet.fromCidr("10.0.20.0/24"))
        quaggaEnv.bgpVty.showConfig() should be (oneNetwork)

        quaggaEnv.bgpVty.deleteNetwork(23, IPv4Subnet.fromCidr("10.0.10.0/24"))
        quaggaEnv.bgpVty.showConfig() should be (empty)
    }

    def testManagePeers(): Unit = {
        quaggaEnv.prepare()
        quaggaEnv.start()

        val firstPeer = IPv4Addr.fromString("192.168.163.100")
        val secondPeer = IPv4Addr.fromString("192.168.163.101")

        val empty = BgpdRunningConfig(debug = false,
            Some("bgpd"),
            Some("bgpd.log"),
            Some("zebra_password"),
            router = Some(BgpRouter(23,
                networks = Set(Network("10.0.10.0/24"), Network("10.0.20.0/24")))))
        val withOnePeer = empty.copy(router = Some(empty.router.get.copy(
                neighbors = Map(firstPeer -> Neighbor(firstPeer, 100, Some(10), Some(30), Some(50))))))
        val withTwoPeers = withOnePeer.copy(router = Some(withOnePeer.router.get.copy(
                neighbors = withOnePeer.router.get.neighbors +
                                (secondPeer ->  Neighbor(secondPeer, 101, Some(10), Some(31), Some(51))))))

        quaggaEnv.bgpVty.setAs(23)
        quaggaEnv.bgpVty.addNetwork(23, IPv4Subnet.fromCidr("10.0.10.0/24"))
        quaggaEnv.bgpVty.addNetwork(23, IPv4Subnet.fromCidr("10.0.20.0/24"))
        quaggaEnv.bgpVty.showConfig() should be (empty)

        quaggaEnv.bgpVty.addPeer(23, firstPeer, 100, 10, 30, 50)
        quaggaEnv.bgpVty.showConfig() should be (withOnePeer)

        quaggaEnv.bgpVty.addPeer(23, secondPeer, 101, 10, 31, 51)
        quaggaEnv.bgpVty.showConfig() should be (withTwoPeers)

        quaggaEnv.bgpVty.deletePeer(23, secondPeer)
        quaggaEnv.bgpVty.showConfig() should be (withOnePeer)

        quaggaEnv.bgpVty.deletePeer(23, firstPeer)
        quaggaEnv.bgpVty.showConfig() should be (empty)
    }
}
