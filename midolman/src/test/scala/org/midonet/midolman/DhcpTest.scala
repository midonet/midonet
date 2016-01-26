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

package org.midonet.midolman

import java.nio.ByteBuffer
import java.util.UUID

import scala.collection.JavaConversions._
import scala.collection.mutable

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.dhcp.Opt121
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.layer3.Route
import org.midonet.midolman.layer3.Route._
import org.midonet.midolman.PacketWorkflow.GeneratedLogicalPacket
import org.midonet.midolman.simulation.{Bridge, DhcpValueParser, Router}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.midolman.util.VirtualConfigurationBuilders.DhcpOpt121Route
import org.midonet.packets._
import org.midonet.packets.util.PacketBuilder._

@RunWith(classOf[JUnitRunner])
class DhcpTest extends MidolmanSpec {

    val routerIp1 = new IPv4Subnet("192.168.11.1", 24)
    val routerMac1 = MAC.fromString("22:aa:aa:ff:ff:ff")

    val routerIp2 = new IPv4Subnet("192.168.22.1", 24)
    val routerMac2 = MAC.fromString("22:ab:cd:ff:ff:ff")

    val routerIp3 = new IPv4Subnet("192.168.33.1", 24)
    val routerMac3 = MAC.fromString("22:aa:ee:ff:ff:ff")

    val vm1Mac = MAC.fromString("02:23:24:25:26:27")
    val vm2Mac = MAC.fromString("02:53:53:53:53:53")

    var bridge: UUID = _
    var bridgePort1: UUID = _
    val bridgePortNumber1 = 1
    var bridgePort2: UUID = _
    val bridgePortNumber2 = 2

    val vm1IP : IPv4Subnet = new IPv4Subnet("10.0.0.0", 24)
    val vm2IP: IPv4Subnet = new IPv4Subnet("10.0.1.0", 24)

    var dhcpSubnet1: IPv4Subnet = _
    var dhcpSubnet2: IPv4Subnet = _

    var dhcpHost1: MAC = _
    var dhcpHost2: MAC = _

    var workflow: PacketWorkflow = _

    override def beforeTest(): Unit = {
        val router = newRouter("router")
        val routerPort1 = newRouterPort(router, routerMac1, routerIp1)
        materializePort(routerPort1, hostId, "routerPort")
        newRoute(router, "0.0.0.0", 0, routerIp1.toUnicastString,
                 routerIp1.getPrefixLen, NextHop.PORT, routerPort1,
                 IPv4Addr(Route.NO_GATEWAY).toString, 10)
        val routerPort2 = newRouterPort(router, routerMac2, routerIp2)
        val routerPort3 = newRouterPort(router, routerMac3, routerIp3)

        bridge = newBridge("bridge")

        val bridgeIntPort1 = newBridgePort(bridge)
        linkPorts(routerPort2, bridgeIntPort1)

        val bridgeIntPort2 = newBridgePort(bridge)
        linkPorts(routerPort3, bridgeIntPort2)

        bridgePort1 = newBridgePort(bridge)
        materializePort(bridgePort1, hostId, "bridgePort1")

        bridgePort2 = newBridgePort(bridge)
        materializePort(bridgePort2, hostId, "bridgePort2")

        // First subnet is routerIp2's
        // TODO (galo) after talking with Abel we suspect that
        // this below should be routerIp1, not 2. It may be
        // irrelevant for the test, but we should confirm
        var opt121Routes = List(DhcpOpt121Route(routerIp2.getAddress(), routerIp1))
        val dnsSrvAddrs = List(IPv4Addr.fromString("192.168.77.118"),
                               IPv4Addr.fromString("192.168.77.119"),
                               IPv4Addr.fromString("192.168.77.120"))
        dhcpSubnet1 = addDhcpSubnet(bridge, routerIp2, routerIp2.getAddress, dnsSrvAddrs,
                                    opt121Routes)

        // Second subnet is routerIp3's
        opt121Routes = List(DhcpOpt121Route(routerIp3.getAddress, routerIp3))
        dhcpSubnet2 = addDhcpSubnet(bridge, routerIp3, routerIp3.getAddress(),
                                    dnsSrvAddrs, opt121Routes)

        dhcpHost1 = addDhcpHost(bridge, dhcpSubnet1, vm1Mac, vm1IP.getAddress)

        dhcpHost2 = addDhcpHost(bridge, dhcpSubnet2, vm2Mac, vm2IP.getAddress)

        fetchPorts(routerPort1, routerPort2, routerPort3,
                   bridgeIntPort1, bridgeIntPort2, bridgePort1, bridgePort2)
        fetchDevice[Router](router)
        fetchDevice[Bridge](bridge)

        workflow = packetWorkflow(Map(bridgePortNumber1 -> bridgePort1,
                                      bridgePortNumber2 -> bridgePort2)).underlyingActor
    }

    def extraDhcpOptToDhcpOption(opt: (String, String)): Option[DHCPOption] = for {
        code <- DhcpValueParser.parseDhcpOptionCode(opt._1)
        value <- DhcpValueParser.parseDhcpOptionValue(code, opt._2)
    } yield new DHCPOption(code, value.length.toByte, value)

    def injectDhcpDiscover(port: UUID, portNumber: Int,
                           srcMac : MAC): Ethernet = {
        val pkt = { eth src srcMac dst "ff:ff:ff:ff:ff:ff" } <<
                  { ip4 src 0 dst 0xffffffff } <<
                  { udp src 68 dst 67 } <<
                  { new DHCP()
                    .setOpCode(0x01)
                    .setHardwareType(0x01)
                    .setHardwareAddressLength(6)
                    .setClientHardwareAddress(srcMac)
                    .setOptions(mutable.ListBuffer(
                        new DHCPOption(DHCPOption.Code.DHCP_TYPE.value,
                                       DHCPOption.Code.DHCP_TYPE.length,
                                       Array[Byte](DHCPOption.MsgType.DISCOVER.value)),
                        new DHCPOption(DHCPOption.Code.DNS.value,
                                       DHCPOption.Code.DNS.length,
                                       Array(192,168,1,1).map(_.toByte)))) }

        val pktCtx = packetContextFor(pkt, inPortNumber = portNumber)
        workflow.start(pktCtx)

        val generatedPkt = simBackChannel.find[GeneratedLogicalPacket]()
        generatedPkt.egressPort should be (port)
        generatedPkt.eth
    }

    def extractDhcpReply(ethPkt : Ethernet) = {
        ethPkt.getEtherType should be (IPv4.ETHERTYPE)
        val ipPkt = ethPkt.getPayload.asInstanceOf[IPv4]
        ipPkt.getProtocol should be (UDP.PROTOCOL_NUMBER)
        val udpPkt = ipPkt.getPayload.asInstanceOf[UDP]
        udpPkt.getSourcePort should be (67)
        udpPkt.getDestinationPort should be (68)
        udpPkt.getPayload.asInstanceOf[DHCP]
    }

    def sendDhcpDiscoveryAndGetDhcpOffer(port: UUID = bridgePort1,
                                         portNum: Int = bridgePortNumber1,
                                         mac: MAC = vm1Mac): DHCP = {
        val returnPkt = injectDhcpDiscover(port, portNum, mac)
        extractDhcpReply(returnPkt)
    }

     /**
      * Setup a bridge connected to a router via 2 ports. The bridge has 2 DHCP
      * subnets configured, with gateways on the corresponding port. We expect
      * this to work normally. See MN-565.
      */
    scenario("Multiple Subnets") {
        val dhcpReplyForVm1 = sendDhcpDiscoveryAndGetDhcpOffer(
            bridgePort1, bridgePortNumber1, vm1Mac)
        dhcpReplyForVm1.getServerIPAddress should be (routerIp2.getIntAddress)

        val dhcpReplyForVm2 = sendDhcpDiscoveryAndGetDhcpOffer(
            bridgePort1, bridgePortNumber1, vm2Mac)
        dhcpReplyForVm2.getServerIPAddress should be (routerIp3.getIntAddress)
    }

    scenario("Dhcp Extra Option") {
        val hostNameOption = (DHCPOption.Code.HOST_NAME.value.toString, "foobar")
        val extraDhcpOpts = Map(hostNameOption)
        setDhcpHostOptions(bridge, dhcpSubnet1, dhcpHost1, extraDhcpOpts)

        val dhcpReply = sendDhcpDiscoveryAndGetDhcpOffer()
        val hostNameDhcpOption = extraDhcpOptToDhcpOption(hostNameOption)
        hostNameDhcpOption should not equal None
        dhcpReply.getOptions.contains(hostNameDhcpOption.get) should be (true)
    }

    scenario("Dhcp Extra Option With Name") {
        val hostNameOption = ("host-name", "foobar")
        val extraDhcpOpts = Map(hostNameOption)
        setDhcpHostOptions(bridge, dhcpSubnet1, dhcpHost1, extraDhcpOpts)

        val dhcpReply = sendDhcpDiscoveryAndGetDhcpOffer()
        val hostNameDhcpOption = extraDhcpOptToDhcpOption(hostNameOption)
        hostNameDhcpOption should not equal None
        dhcpReply.getOptions.contains(hostNameDhcpOption.get) should be (true)
    }

    scenario("Dhcp Extra Option Without Dhcp Host Update") {
        val hostNameOption = (DHCPOption.Code.HOST_NAME.value.toString, "foobar")

        val dhcpReply = sendDhcpDiscoveryAndGetDhcpOffer()
        val hostNameDhcpOption = extraDhcpOptToDhcpOption(hostNameOption)
        hostNameDhcpOption should not equal None
        dhcpReply.getOptions.contains(hostNameDhcpOption.get) should be (false)
    }

    scenario("Invalid Dhcp Extra Option Should Be Ignored") {
        val dhcpReply = sendDhcpDiscoveryAndGetDhcpOffer()

        val invalidOption = ("I am", "an invalid option")
        val extraDhcpOpts = Map(invalidOption)
        setDhcpHostOptions(bridge, dhcpSubnet1, dhcpHost1, extraDhcpOpts)

        val dhcpReplyWithInvalidOption = sendDhcpDiscoveryAndGetDhcpOffer()
        dhcpReply.getOptions should equal (
            dhcpReplyWithInvalidOption.getOptions)
    }

    scenario("Dhcp Extra Option With Duplicated Option") {
        val dhcpReply = sendDhcpDiscoveryAndGetDhcpOffer()
        dhcpReply.getOptions.map(_.getCode).contains(
            DHCPOption.Code.INTERFACE_MTU.value) should be (true)
        val oldOptionsNumber = dhcpReply.getOptions.length
        val oldMtuOption = dhcpReply.getOptions.find(
            _.getCode == DHCPOption.Code.INTERFACE_MTU.value).get

        val newMtu: Int = ByteBuffer.wrap(oldMtuOption.getData).getShort + 42
        val mtuOption = (DHCPOption.Code.INTERFACE_MTU.value.toString, newMtu.toString)
        val extraDhcpOpts = Map(mtuOption)
        setDhcpHostOptions(bridge, dhcpSubnet1, dhcpHost1, extraDhcpOpts)

        val dhcpReplyWithDuplicatedOption = sendDhcpDiscoveryAndGetDhcpOffer()
        val newMtuOptionsNumber =
            dhcpReplyWithDuplicatedOption.getOptions.length
        val newMtuOption = dhcpReplyWithDuplicatedOption.getOptions.find(
            _.getCode == DHCPOption.Code.INTERFACE_MTU.value).get

        newMtuOptionsNumber should equal (oldOptionsNumber)
        newMtuOption should not equal oldMtuOption
    }

    scenario("Invalid Dhcp Extra Option Value Should Be Ignored") {
        val dhcpReply = sendDhcpDiscoveryAndGetDhcpOffer()

        val tooBigMtuValueString = s"$Int.MaxValue" + "0"
        val invalidMtuOption = (DHCPOption.Code.INTERFACE_MTU.value.toString,
                                tooBigMtuValueString)
        val extraDhcpOpts = Map(invalidMtuOption)
        setDhcpHostOptions(bridge, dhcpSubnet1, dhcpHost1, extraDhcpOpts)

        val dhcpReplyWithInvalidOption = sendDhcpDiscoveryAndGetDhcpOffer()
        dhcpReply.getOptions should equal (
            dhcpReplyWithInvalidOption.getOptions)
    }

    scenario("Ip Required Dhcp Extra Option") {
        val emptyRouterOption = (DHCPOption.Code.ROUTER.value.toString, "")
        val emptyExtraDhcpOpts = Map(emptyRouterOption)
        setDhcpHostOptions(bridge, dhcpSubnet1, dhcpHost1, emptyExtraDhcpOpts)

        sendDhcpDiscoveryAndGetDhcpOffer()
        val emptyRouterDhcpOption = extraDhcpOptToDhcpOption(emptyRouterOption)
        emptyRouterDhcpOption should be (None)

        val routerOption = (DHCPOption.Code.ROUTER.value.toString, "192.168.1.1, 192.168.11.1")
        val extraDhcpOpts = Map(routerOption)
        setDhcpHostOptions(bridge, dhcpSubnet1, dhcpHost1, extraDhcpOpts)

        val dhcpReply = sendDhcpDiscoveryAndGetDhcpOffer()
        val routerDhcpOption = extraDhcpOptToDhcpOption(routerOption)
        routerDhcpOption should not equal None
        dhcpReply.getOptions.contains(routerDhcpOption.get) should be (true)
    }

    scenario("Boolean Required Dhcp Extra Option") {
        val ipForwardingOption = (DHCPOption.Code.IP_FOWARDING_ENABLE_DISABLE.value.toString, "1")
        val extraDhcpOpts = Map(ipForwardingOption)
        setDhcpHostOptions(bridge, dhcpSubnet1, dhcpHost1, extraDhcpOpts)

        val dhcpReply = sendDhcpDiscoveryAndGetDhcpOffer()
        val ipFowardingDhcpOption = extraDhcpOptToDhcpOption(ipForwardingOption)
        ipFowardingDhcpOption should not equal None
        dhcpReply.getOptions.contains(
            ipFowardingDhcpOption.get) should be (true)
    }

    scenario("Cidr Required Dhcp Extra Option") {
        val cidrOption = (DHCPOption.Code.STATIC_ROUTE.value.toString, "192.168.1.0/24")
        val extraDhcpOpts = Map(cidrOption)
        setDhcpHostOptions(bridge, dhcpSubnet1, dhcpHost1, extraDhcpOpts)

        val dhcpReply = sendDhcpDiscoveryAndGetDhcpOffer()
        val cidrDhcpOption = extraDhcpOptToDhcpOption(cidrOption)
        cidrDhcpOption should not equal None
        dhcpReply.getOptions.contains(cidrDhcpOption.get) should be (true)
    }

    scenario("1248 Required Dhcp Extra Option") {
        val _1248Option = (DHCPOption.Code.NETBIOS_OVER_TCP_IP_NODE_TYPE.value.toString, "4")
        val extraDhcpOpts = Map(_1248Option)
        setDhcpHostOptions(bridge, dhcpSubnet1, dhcpHost1, extraDhcpOpts)

        val dhcpReply = sendDhcpDiscoveryAndGetDhcpOffer()
        val _1248DhcpOption = extraDhcpOptToDhcpOption(_1248Option)
        _1248DhcpOption should not equal None
        dhcpReply.getOptions.contains(_1248DhcpOption.get) should be (true)
    }

    scenario("1 to 3 Required Dhcp Extra Option") {
        val _1to3Option = (DHCPOption.Code.OPTION_OVERLOAD.value.toString, "3")
        val extraDhcpOpts = Map(_1to3Option)
        setDhcpHostOptions(bridge, dhcpSubnet1, dhcpHost1, extraDhcpOpts)

        val dhcpReply = sendDhcpDiscoveryAndGetDhcpOffer()
        val _1to3DhcpOption = extraDhcpOptToDhcpOption(_1to3Option)
        _1to3DhcpOption should not equal None
        dhcpReply.getOptions.contains(_1to3DhcpOption.get) should be (true)
    }

    scenario("1 to 8 Required Dhcp Extra Option") {
        val _1to8Option = (DHCPOption.Code.ERROR_MESSAGE.value.toString, "8")
        val extraDhcpOpts = Map(_1to8Option)
        setDhcpHostOptions(bridge, dhcpSubnet1, dhcpHost1, extraDhcpOpts)

        val dhcpReply = sendDhcpDiscoveryAndGetDhcpOffer()
        val _1to8DhcpOption = extraDhcpOptToDhcpOption(_1to8Option)
        _1to8DhcpOption should not equal None
        dhcpReply.getOptions.contains(_1to8DhcpOption.get) should be (true)
    }

    scenario("Client Identifier Dhcp Extra Option") {
        val clientIdentifierOption = (DHCPOption.Code.CLIENT_IDENTIFIER.value.toString,
                                      "12, midokura.com")
        val extraDhcpOpts = Map(clientIdentifierOption)
        setDhcpHostOptions(bridge, dhcpSubnet1, dhcpHost1, extraDhcpOpts)

        val dhcpReply = sendDhcpDiscoveryAndGetDhcpOffer()
        val clientIdentifierDhcpOption =
            extraDhcpOptToDhcpOption(clientIdentifierOption)
        clientIdentifierDhcpOption should not equal None
        dhcpReply.getOptions.contains(
            clientIdentifierDhcpOption.get) should be (true)
    }

    scenario("Byte Followed By Ip Addrs Dhcp Extra Option") {
        val byteFollowedByIpAddrsOption = (DHCPOption.Code.SLP_DIRECTORY_AGENT.value.toString,
                                           "1, 192.168.1.1")
        val extraDhcpOpts = Map(byteFollowedByIpAddrsOption)
        setDhcpHostOptions(bridge, dhcpSubnet1, dhcpHost1, extraDhcpOpts)

        val dhcpReply = sendDhcpDiscoveryAndGetDhcpOffer()
        val byteFollowedByIpAddrsDhcpOption =
            extraDhcpOptToDhcpOption(byteFollowedByIpAddrsOption)
        byteFollowedByIpAddrsDhcpOption should not equal None
        dhcpReply.getOptions.contains(
            byteFollowedByIpAddrsDhcpOption.get) should be (true)
    }

    scenario("Byte Followed By String Dhcp Extra Option") {
        val byteFollowedByStringOption = (DHCPOption.Code.SLP_SERVICE_SCOPE.value.toString,
                                          "1, foobar")
        val extraDhcpOpts = Map(byteFollowedByStringOption)
        setDhcpHostOptions(bridge, dhcpSubnet1, dhcpHost1, extraDhcpOpts)

        val dhcpReply = sendDhcpDiscoveryAndGetDhcpOffer()
        val byteFollowedByStringDhcpOption =
            extraDhcpOptToDhcpOption(byteFollowedByStringOption)
        byteFollowedByStringDhcpOption should not equal None
        dhcpReply.getOptions.contains(
            byteFollowedByStringDhcpOption.get) should be (true)
    }

    scenario("Rapid Commit Dhcp Extra Option") {
        val rapidCommitOption = (DHCPOption.Code.RAPID_COMMIT.value.toString, "")
        val extraDhcpOpts = Map(rapidCommitOption)
        setDhcpHostOptions(bridge, dhcpSubnet1, dhcpHost1, extraDhcpOpts)

        val dhcpReply = sendDhcpDiscoveryAndGetDhcpOffer()
        val rapdCommitDhcpOption =
            extraDhcpOptToDhcpOption(rapidCommitOption)
        rapdCommitDhcpOption should not equal None
        dhcpReply.getOptions.contains(
            rapdCommitDhcpOption.get) should be (true)
    }

    scenario("Status Code Dhcp Extra Option") {
        val statusCodeOption = (DHCPOption.Code.RAPID_COMMIT.value.toString, "2")
        val extraDhcpOpts = Map(statusCodeOption)
        setDhcpHostOptions(bridge, dhcpSubnet1, dhcpHost1, extraDhcpOpts)

        val dhcpReply = sendDhcpDiscoveryAndGetDhcpOffer()
        val statusCodeDhcpOption =
            extraDhcpOptToDhcpOption(statusCodeOption)
        statusCodeDhcpOption should not equal None
        dhcpReply.getOptions.contains(statusCodeDhcpOption.get) should be (true)
    }

    scenario("Data Source Dhcp Extra Option") {
        val dataSourceOption = (DHCPOption.Code.DATA_SOURCE.value.toString, "24")
        val extraDhcpOpts = Map(dataSourceOption)
        setDhcpHostOptions(bridge, dhcpSubnet1, dhcpHost1, extraDhcpOpts)

        val dhcpReply = sendDhcpDiscoveryAndGetDhcpOffer()
        val dataSourceDhcpOption =
            extraDhcpOptToDhcpOption(dataSourceOption)
        dataSourceDhcpOption should not equal None
        dhcpReply.getOptions.contains(dataSourceDhcpOption.get) should be (true)
    }

    scenario("Classless Routes Dhcp Extra Option") {
        val classlessRoutesOption = (DHCPOption.Code.CLASSLESS_ROUTES.value.toString,
                                     "192.168.100.0/24, 10.0.0.1")
        val extraDhcpOpts = Map(classlessRoutesOption)
        setDhcpHostOptions(bridge, dhcpSubnet1, dhcpHost1, extraDhcpOpts)

        val dhcpReply = sendDhcpDiscoveryAndGetDhcpOffer()
        val classlessRoutesDhcpOption =
            extraDhcpOptToDhcpOption(classlessRoutesOption)
        classlessRoutesDhcpOption should not equal None
        dhcpReply.getOptions.contains(
            classlessRoutesDhcpOption.get) should be (true)
    }

    def getOpt121FromBytes(bytes: Array[Byte]): mutable.ListBuffer[Opt121] = {
        val opt121Routes = mutable.ListBuffer[Opt121]()
        val bb = ByteBuffer.wrap(bytes)
        while (bb.hasRemaining) {
            val maskLen = bb.get
            var mask = 0
            for (i <- 0 to 3) {
                mask <<= 8
                if (maskLen > i * 8) mask |= (0xFF & bb.get)
            }
            opt121Routes += new Opt121(
                new IPv4Subnet(mask, maskLen), new IPv4Addr(bb.getInt))
        }
        opt121Routes
    }

    scenario("Classless Routes should get default route") {
        val dhcpReply = sendDhcpDiscoveryAndGetDhcpOffer()

        val univSubnet = IPv4Subnet.fromCidr("0.0.0.0/0")
        val defaultRoute = new Opt121(IPv4Subnet.fromCidr("0.0.0.0/0"),
                                      routerIp2.getAddress)

        // Defined as routerIp1 above, but the full IP is used
        // (192.168.11.1/24) we compare only the sub.
        val expectedRoute = new Opt121(IPv4Subnet.fromCidr("192.168.11.0/24"),
                                       routerIp2.getAddress)

        val opt121 = dhcpReply.getOptions
            .filter(_.getCode == DHCPOption.Code.CLASSLESS_ROUTES.value)

        opt121.size shouldBe 1
        val routes = getOpt121FromBytes(opt121.head.getData)

        routes should contain only (defaultRoute, expectedRoute)
    }

    scenario("Interface MTU") {
        val returnPkt = injectDhcpDiscover(bridgePort1, bridgePortNumber1, vm1Mac)
        val dhcpReply = extractDhcpReply(returnPkt)
        val mtuValue = ByteBuffer.wrap(dhcpReply.getOptions.find(
            _.getCode == DHCPOption.Code.INTERFACE_MTU.value).get.getData).getShort
        mtuValue should (be (DatapathController.minMtu) or (be (MidolmanConfig.DEFAULT_MTU)))
    }
}
