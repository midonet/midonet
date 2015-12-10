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
package org.midonet.midolman.containers

import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.concurrent.Eventually
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Neutron.IkePolicy.IkeVersion
import org.midonet.cluster.models.Neutron._
import org.midonet.containers._
import org.midonet.packets.{IPv4Subnet, IPv4Addr}


@RunWith(classOf[JUnitRunner])
class IpsecServiceContainerTest extends FeatureSpec with Matchers with Eventually {

    val vpnService = new IpsecServiceDef("name", "/opt/stack/stuff",
                                         IPv4Addr.fromString("100.100.100.2"),
                                         "00:01:02:03:04:05",
                                         IPv4Subnet.fromCidr("1.0.0.0/24"),
                                         IPv4Addr.fromString("1.1.1.1"),
                                         "09:08:07:06:05:04")

    val ikePolicy = IkePolicy.newBuilder()
        .setIkeVersion(IkeVersion.V1)
        .setLifetimeValue(3600)
        .build()

    val iPSecPolicy = IPSecPolicy.newBuilder()
        .setEncapsulationMode(IPSecPolicy.EncapsulationMode.TUNNEL)
        .setTransformProtocol(IPSecPolicy.TransformProtocol.ESP)
        .setLifetimeValue(3600)
        .build()

    val iPSecSiteConnection = IPSecSiteConnection.newBuilder()
        .setAuthMode(IPSecSiteConnection.AuthMode.PSK)
        .setDpdAction(IPSecSiteConnection.DpdAction.HOLD)
        .setDpdInterval(30)
        .setDpdTimeout(120)
        .setInitiator(IPSecSiteConnection.Initiator.BI_DIRECTIONAL)
        .setName("test_conn")
        .setMtu(1500)
        .setPeerAddress("200.200.200.2")
        .setPsk("secret")
        .addLocalCidrs(Commons.IPSubnet.newBuilder()
                           .setAddress("10.1.0.0")
                           .setPrefixLength(24)
                           .setVersion(Commons.IPVersion.V4)
                           .build())
        .addPeerCidrs(Commons.IPSubnet.newBuilder()
                           .setAddress("10.2.0.0")
                           .setPrefixLength(24)
                           .setVersion(Commons.IPVersion.V4)
                           .build())
        .build()

    feature("VpnServiceContainer writes contents of config files") {
        scenario("VpnServiceContainer creates config for single conn") {
            val expectedSecrets =
                s"""100.100.100.2 200.200.200.2 : PSK "secret"
                   |""".stripMargin
            val expectedConf =
                s"""config setup
                   |    nat_traversal=yes
                   |conn %default
                   |    ikelifetime=480m
                   |    keylife=60m
                   |    keyingtries=%forever
                   |conn test_conn
                   |    leftnexthop=%defaultroute
                   |    rightnexthop=%defaultroute
                   |    left=100.100.100.2
                   |    leftid=100.100.100.2
                   |    auto=start
                   |    leftsubnets={ 10.1.0.0/24 }
                   |    leftupdown="ipsec _updown --route yes"
                   |    right=200.200.200.2
                   |    rightid=200.200.200.2
                   |    rightsubnets={ 10.2.0.0/24 }
                   |    mtu=1500
                   |    dpdaction=hold
                   |    dpddelay=30
                   |    dpdtimeout=120
                   |    authby=secret
                   |    ikev2=never
                   |    ike=aes128-sha1;modp1536
                   |    ikelifetime=3600s
                   |    auth=esp
                   |    phase2alg=aes128-sha1;modp1536
                   |    type=tunnel
                   |    lifetime=3600s
                   |""".stripMargin
            val conn = new IpsecConnection(iPSecPolicy, ikePolicy, iPSecSiteConnection)
            val conf = new IpsecServiceConfig("vpn-helper", vpnService, List(conn))
            val actualConf = conf.getConfigFileContents
            val actualSecrets = conf.getSecretsFileContents

            expectedConf shouldBe actualConf
            expectedSecrets shouldBe actualSecrets
        }
        scenario("VpnServiceContainer creates config for multiple conns") {
            val expectedSecrets =
                s"""100.100.100.2 200.200.200.2 : PSK "secret"
                   |100.100.100.2 230.230.230.230 : PSK "secret"
                   |""".stripMargin
            val expectedConf =
                s"""config setup
                   |    nat_traversal=yes
                   |conn %default
                   |    ikelifetime=480m
                   |    keylife=60m
                   |    keyingtries=%forever
                   |conn test_conn
                   |    leftnexthop=%defaultroute
                   |    rightnexthop=%defaultroute
                   |    left=100.100.100.2
                   |    leftid=100.100.100.2
                   |    auto=start
                   |    leftsubnets={ 10.1.0.0/24 }
                   |    leftupdown="ipsec _updown --route yes"
                   |    right=200.200.200.2
                   |    rightid=200.200.200.2
                   |    rightsubnets={ 10.2.0.0/24 }
                   |    mtu=1500
                   |    dpdaction=hold
                   |    dpddelay=30
                   |    dpdtimeout=120
                   |    authby=secret
                   |    ikev2=never
                   |    ike=aes128-sha1;modp1536
                   |    ikelifetime=3600s
                   |    auth=esp
                   |    phase2alg=aes128-sha1;modp1536
                   |    type=tunnel
                   |    lifetime=3600s
                   |conn test_conn2
                   |    leftnexthop=%defaultroute
                   |    rightnexthop=%defaultroute
                   |    left=100.100.100.2
                   |    leftid=100.100.100.2
                   |    auto=start
                   |    leftsubnets={ 10.1.0.0/24 }
                   |    leftupdown="ipsec _updown --route yes"
                   |    right=230.230.230.230
                   |    rightid=230.230.230.230
                   |    rightsubnets={ 10.2.0.0/24 }
                   |    mtu=1500
                   |    dpdaction=hold
                   |    dpddelay=30
                   |    dpdtimeout=120
                   |    authby=secret
                   |    ikev2=never
                   |    ike=aes128-sha1;modp1536
                   |    ikelifetime=3600s
                   |    auth=esp
                   |    phase2alg=aes128-sha1;modp1536
                   |    type=tunnel
                   |    lifetime=3600s
                   |""".stripMargin
            val conn = new IpsecConnection(iPSecPolicy, ikePolicy, iPSecSiteConnection)
            val ipsecConn2 = IPSecSiteConnection.newBuilder()
                .setAuthMode(IPSecSiteConnection.AuthMode.PSK)
                .setDpdAction(IPSecSiteConnection.DpdAction.HOLD)
                .setDpdInterval(30)
                .setDpdTimeout(120)
                .setInitiator(IPSecSiteConnection.Initiator.BI_DIRECTIONAL)
                .setName("test_conn2")
                .setMtu(1500)
                .setPeerAddress("230.230.230.230")
                .setPsk("secret")
                .addLocalCidrs(Commons.IPSubnet.newBuilder()
                    .setAddress("10.1.0.0")
                    .setPrefixLength(24)
                    .setVersion(Commons.IPVersion.V4)
                    .build())
                .addPeerCidrs(Commons.IPSubnet.newBuilder()
                    .setAddress("10.2.0.0")
                    .setPrefixLength(24)
                    .setVersion(Commons.IPVersion.V4)
                    .build())
                .build()
            val secondConn = new IpsecConnection(iPSecPolicy, ikePolicy, ipsecConn2)
            val conf = new IpsecServiceConfig("vpn-helper", vpnService,
                                            List(conn, secondConn))
            val actualConf = conf.getConfigFileContents
            expectedConf shouldBe actualConf
        }
    }

    feature("Vpn script starts and stops") {
        scenario("commands are correctly executred") {
            val conn = new IpsecConnection(iPSecPolicy, ikePolicy, iPSecSiteConnection)
            val conf = new IpsecServiceConfig("vpn-helper", vpnService, List(conn))
            TestVpnServiceContainter.start(conf)
            TestVpnServiceContainter.cmdList(0) shouldBe
                "vpn-helper makens -n name -g 1.1.1.1 -G 09:08:07:06:05:04 " +
                "-l 100.100.100.2 -i 1.0.0.0/24 -m 00:01:02:03:04:05"
            TestVpnServiceContainter.cmdList(1) shouldBe
                "vpn-helper start_service -n name -p /opt/stack/stuff"
            TestVpnServiceContainter.cmdList(2) shouldBe
                "vpn-helper init_conns -n name -p /opt/stack/stuff -g 1.1.1.1 " +
                "-c test_conn"

            TestVpnServiceContainter.stop(conf)

            TestVpnServiceContainter.cmdList(3) shouldBe
                "vpn-helper stop_service -n name -p /opt/stack/stuff"
            TestVpnServiceContainter.cmdList(4) shouldBe
                "vpn-helper cleanns -n name"
        }
    }

    object TestVpnServiceContainter extends IpsecServiceContainerFunctions {
        var cmdList = List[String]()
        override def execCmd(cmd: String) = {
            cmdList ++= List(cmd)
        }
        override def writeFile(contents: String, location: String): Boolean = {
            true
        }
    }
}
