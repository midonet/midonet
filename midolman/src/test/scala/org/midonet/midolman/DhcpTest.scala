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
import java.util.{LinkedList, UUID}

import scala.collection.JavaConversions._
import scala.collection.mutable

import org.junit.runner.RunWith
import org.midonet.midolman.config.MidolmanConfig
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.dhcp.{Host => DhcpHost}
import org.midonet.cluster.data.dhcp.Opt121
import org.midonet.cluster.data.dhcp.Subnet
import org.midonet.cluster.data.dhcp.ExtraDhcpOpt
import org.midonet.midolman.layer3.Route
import org.midonet.midolman.layer3.Route._
import org.midonet.midolman.simulation.{Bridge, DhcpValueParser, Router}
import org.midonet.midolman.simulation.PacketEmitter.GeneratedPacket
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.packets._
import org.midonet.packets.util.PacketBuilder._

@RunWith(classOf[JUnitRunner])
class DhcpTest extends MidolmanSpec {

    registerActors(VirtualTopologyActor -> (() => new VirtualTopologyActor))

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

    var dhcpSubnet1: Subnet = _
    var dhcpSubnet2: Subnet = _

    var dhcpHost1: DhcpHost = _
    var dhcpHost2: DhcpHost = _

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
        clusterDataClient.portsLink(routerPort2, bridgeIntPort1)

        val bridgeIntPort2 = newBridgePort(bridge)
        clusterDataClient.portsLink(routerPort3, bridgeIntPort2)

        bridgePort1 = newBridgePort(bridge)
        materializePort(bridgePort1, hostId, "bridgePort1")

        bridgePort2 = newBridgePort(bridge)
        materializePort(bridgePort2, hostId, "bridgePort2")

        // First subnet is routerIp2's
        var opt121Obj = new Opt121()
            .setGateway(routerIp2.getAddress)
            // TODO (galo) after talking with Abel we suspect that
            // this below should be routerIp1, not 2. It may be
            // irrelevant for the test, but we should confirm
            .setRtDstSubnet(routerIp1)
        var opt121Routes = List(opt121Obj)
        val dnsSrvAddrs = List(IPv4Addr.fromString("192.168.77.118"),
                               IPv4Addr.fromString("192.168.77.119"),
                               IPv4Addr.fromString("192.168.77.120"))
        dhcpSubnet1 = new Subnet()
            .setSubnetAddr(routerIp2)
            .setDefaultGateway(routerIp2.getAddress)
            .setDnsServerAddrs(dnsSrvAddrs)
            .setOpt121Routes(opt121Routes)
        addDhcpSubnet(bridge, dhcpSubnet1)

        // Second subnet is routerIp3's
        opt121Obj = new Opt121()
            .setGateway(routerIp3.getAddress)
            .setRtDstSubnet(routerIp3)
        opt121Routes = List(opt121Obj)
        dhcpSubnet2 = new Subnet()
            .setSubnetAddr(routerIp3)
            .setDefaultGateway(routerIp3.getAddress)
            .setDnsServerAddrs(dnsSrvAddrs)
            .setOpt121Routes(opt121Routes)
        addDhcpSubnet(bridge, dhcpSubnet2)

        dhcpHost1 = new DhcpHost()
            .setMAC(vm1Mac)
            .setIp(vm1IP.getAddress)
        addDhcpHost(bridge, dhcpSubnet1, dhcpHost1)

        dhcpHost2 = new DhcpHost()
            .setMAC(vm2Mac)
            .setIp(vm2IP.getAddress)
        addDhcpHost(bridge, dhcpSubnet2, dhcpHost2)

        fetchPorts(routerPort1, routerPort2, routerPort3,
                   bridgeIntPort1, bridgeIntPort2, bridgePort1, bridgePort2)
        fetchDevice[Router](router)
        fetchDevice[Bridge](bridge)

        workflow = packetWorkflow(Map(bridgePortNumber1 -> bridgePort1,
                                      bridgePortNumber2 -> bridgePort2)).underlyingActor
    }

    def extraDhcpOptToDhcpOption(opt: ExtraDhcpOpt): Option[DHCPOption] = for {
        code <- DhcpValueParser.parseDhcpOptionCode(opt.optName)
        value <- DhcpValueParser.parseDhcpOptionValue(code, opt.optValue)
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

        val emitter = new LinkedList[GeneratedPacket]
        val pktCtx = packetContextFor(pkt, inPortNumber = portNumber,
                                      emitter = emitter)
        workflow.start(pktCtx)

        emitter should have size 1
        emitter.head.egressPort should be (port)
        emitter.head.eth
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
        val hostNameOption = new ExtraDhcpOpt(
            DHCPOption.Code.HOST_NAME.value.toString, "foobar")
        val extraDhcpOpts = List(hostNameOption)
        dhcpHost1.setExtraDhcpOpts(extraDhcpOpts)
        updatedhcpHost(bridge, dhcpSubnet1, dhcpHost1)

        val dhcpReply = sendDhcpDiscoveryAndGetDhcpOffer()
        val hostNameDhcpOption = extraDhcpOptToDhcpOption(hostNameOption)
        hostNameDhcpOption should not equal None
        dhcpReply.getOptions.contains(hostNameDhcpOption.get) should be (true)
    }

    scenario("Dhcp Extra Option With Name") {
        val hostNameOption = new ExtraDhcpOpt("host-name", "foobar")
        val extraDhcpOpts = List(hostNameOption)
        dhcpHost1.setExtraDhcpOpts(extraDhcpOpts)
        updatedhcpHost(bridge, dhcpSubnet1, dhcpHost1)

        val dhcpReply = sendDhcpDiscoveryAndGetDhcpOffer()
        val hostNameDhcpOption = extraDhcpOptToDhcpOption(hostNameOption)
        hostNameDhcpOption should not equal None
        dhcpReply.getOptions.contains(hostNameDhcpOption.get) should be (true)
    }

    scenario("Dhcp Extra Option Without Dhcp Host Update") {
        val hostNameOption = new ExtraDhcpOpt(
            DHCPOption.Code.HOST_NAME.value.toString, "foobar")

        val dhcpReply = sendDhcpDiscoveryAndGetDhcpOffer()
        val hostNameDhcpOption = extraDhcpOptToDhcpOption(hostNameOption)
        hostNameDhcpOption should not equal None
        dhcpReply.getOptions.contains(hostNameDhcpOption.get) should be (false)
    }

    scenario("Invalid Dhcp Extra Option Should Be Ignored") {
        val dhcpReply = sendDhcpDiscoveryAndGetDhcpOffer()

        val invalidOption = new ExtraDhcpOpt("I am", "an invalid option")
        val extraDhcpOpts = List(invalidOption)
        dhcpHost1.setExtraDhcpOpts(extraDhcpOpts)
        updatedhcpHost(bridge, dhcpSubnet1, dhcpHost1)

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
        val mtuOption = new ExtraDhcpOpt(
            DHCPOption.Code.INTERFACE_MTU.value.toString, newMtu.toString)
        val extraDhcpOpts = List(mtuOption)
        dhcpHost1.setExtraDhcpOpts(extraDhcpOpts)
        updatedhcpHost(bridge, dhcpSubnet1, dhcpHost1)

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
        val invalidMtuOption = new ExtraDhcpOpt(
            DHCPOption.Code.INTERFACE_MTU.value.toString,
            tooBigMtuValueString)
        val extraDhcpOpts = List(invalidMtuOption)
        dhcpHost1.setExtraDhcpOpts(extraDhcpOpts)
        updatedhcpHost(bridge, dhcpSubnet1, dhcpHost1)

        val dhcpReplyWithInvalidOption = sendDhcpDiscoveryAndGetDhcpOffer()
        dhcpReply.getOptions should equal (
            dhcpReplyWithInvalidOption.getOptions)
    }

    scenario("Ip Required Dhcp Extra Option") {
        val emptyRouterOption = new ExtraDhcpOpt(
            DHCPOption.Code.ROUTER.value.toString, "")
        val emptyExtraDhcpOpts = List(emptyRouterOption)
        dhcpHost1.setExtraDhcpOpts(emptyExtraDhcpOpts)
        updatedhcpHost(bridge, dhcpSubnet1, dhcpHost1)

        sendDhcpDiscoveryAndGetDhcpOffer()
        val emptyRouterDhcpOption = extraDhcpOptToDhcpOption(emptyRouterOption)
        emptyRouterDhcpOption should be (None)

        val routerOption = new ExtraDhcpOpt(
            DHCPOption.Code.ROUTER.value.toString, "192.168.1.1, 192.168.11.1")
        val extraDhcpOpts = List(routerOption)
        dhcpHost1.setExtraDhcpOpts(extraDhcpOpts)
        updatedhcpHost(bridge, dhcpSubnet1, dhcpHost1)

        val dhcpReply = sendDhcpDiscoveryAndGetDhcpOffer()
        val routerDhcpOption = extraDhcpOptToDhcpOption(routerOption)
        routerDhcpOption should not equal None
        dhcpReply.getOptions.contains(routerDhcpOption.get) should be (true)
    }

    scenario("Boolean Required Dhcp Extra Option") {
        val ipForwardingOption = new ExtraDhcpOpt(
            DHCPOption.Code.IP_FOWARDING_ENABLE_DISABLE.value.toString, "1")
        val extraDhcpOpts = List(ipForwardingOption)
        dhcpHost1.setExtraDhcpOpts(extraDhcpOpts)
        updatedhcpHost(bridge, dhcpSubnet1, dhcpHost1)

        val dhcpReply = sendDhcpDiscoveryAndGetDhcpOffer()
        val ipFowardingDhcpOption = extraDhcpOptToDhcpOption(ipForwardingOption)
        ipFowardingDhcpOption should not equal None
        dhcpReply.getOptions.contains(
            ipFowardingDhcpOption.get) should be (true)
    }

    scenario("Cidr Required Dhcp Extra Option") {
        val cidrOption = new ExtraDhcpOpt(
            DHCPOption.Code.STATIC_ROUTE.value.toString, "192.168.1.0/24")
        val extraDhcpOpts = List(cidrOption)
        dhcpHost1.setExtraDhcpOpts(extraDhcpOpts)
        updatedhcpHost(bridge, dhcpSubnet1, dhcpHost1)

        val dhcpReply = sendDhcpDiscoveryAndGetDhcpOffer()
        val cidrDhcpOption = extraDhcpOptToDhcpOption(cidrOption)
        cidrDhcpOption should not equal None
        dhcpReply.getOptions.contains(cidrDhcpOption.get) should be (true)
    }

    scenario("1248 Required Dhcp Extra Option") {
        val _1248Option = new ExtraDhcpOpt(
            DHCPOption.Code.NETBIOS_OVER_TCP_IP_NODE_TYPE.value.toString, "4")
        val extraDhcpOpts = List(_1248Option)
        dhcpHost1.setExtraDhcpOpts(extraDhcpOpts)
        updatedhcpHost(bridge, dhcpSubnet1, dhcpHost1)

        val dhcpReply = sendDhcpDiscoveryAndGetDhcpOffer()
        val _1248DhcpOption = extraDhcpOptToDhcpOption(_1248Option)
        _1248DhcpOption should not equal None
        dhcpReply.getOptions.contains(_1248DhcpOption.get) should be (true)
    }

    scenario("1 to 3 Required Dhcp Extra Option") {
        val _1to3Option = new ExtraDhcpOpt(
            DHCPOption.Code.OPTION_OVERLOAD.value.toString, "3")
        val extraDhcpOpts = List(_1to3Option)
        dhcpHost1.setExtraDhcpOpts(extraDhcpOpts)
        updatedhcpHost(bridge, dhcpSubnet1, dhcpHost1)

        val dhcpReply = sendDhcpDiscoveryAndGetDhcpOffer()
        val _1to3DhcpOption = extraDhcpOptToDhcpOption(_1to3Option)
        _1to3DhcpOption should not equal None
        dhcpReply.getOptions.contains(_1to3DhcpOption.get) should be (true)
    }

    scenario("1 to 8 Required Dhcp Extra Option") {
        val _1to8Option = new ExtraDhcpOpt(
            DHCPOption.Code.ERROR_MESSAGE.value.toString, "8")
        val extraDhcpOpts = List(_1to8Option)
        dhcpHost1.setExtraDhcpOpts(extraDhcpOpts)
        updatedhcpHost(bridge, dhcpSubnet1, dhcpHost1)

        val dhcpReply = sendDhcpDiscoveryAndGetDhcpOffer()
        val _1to8DhcpOption = extraDhcpOptToDhcpOption(_1to8Option)
        _1to8DhcpOption should not equal None
        dhcpReply.getOptions.contains(_1to8DhcpOption.get) should be (true)
    }

    scenario("Client Identifier Dhcp Extra Option") {
        val clientIdentifierOption = new ExtraDhcpOpt(
            DHCPOption.Code.CLIENT_IDENTIFIER.value.toString,
            "12, midokura.com")
        val extraDhcpOpts = List(clientIdentifierOption)
        dhcpHost1.setExtraDhcpOpts(extraDhcpOpts)
        updatedhcpHost(bridge, dhcpSubnet1, dhcpHost1)

        val dhcpReply = sendDhcpDiscoveryAndGetDhcpOffer()
        val clientIdentifierDhcpOption =
            extraDhcpOptToDhcpOption(clientIdentifierOption)
        clientIdentifierDhcpOption should not equal None
        dhcpReply.getOptions.contains(
            clientIdentifierDhcpOption.get) should be (true)
    }

    scenario("Byte Followd By Ip Addrs Dhcp Extra Option") {
        val byteFollowedByIpAddrsOption = new ExtraDhcpOpt(
            DHCPOption.Code.SLP_DIRECTORY_AGENT.value.toString,
            "1, 192.168.1.1")
        val extraDhcpOpts = List(byteFollowedByIpAddrsOption)
        dhcpHost1.setExtraDhcpOpts(extraDhcpOpts)
        updatedhcpHost(bridge, dhcpSubnet1, dhcpHost1)

        val dhcpReply = sendDhcpDiscoveryAndGetDhcpOffer()
        val byteFollowedByIpAddrsDhcpOption =
            extraDhcpOptToDhcpOption(byteFollowedByIpAddrsOption)
        byteFollowedByIpAddrsDhcpOption should not equal None
        dhcpReply.getOptions.contains(
            byteFollowedByIpAddrsDhcpOption.get) should be (true)
    }

    scenario("Byte Followed By String Dhcp Extra Option") {
        val byteFollowedByStringOption = new ExtraDhcpOpt(
            DHCPOption.Code.SLP_SERVICE_SCOPE.value.toString,
            "1, foobar")
        val extraDhcpOpts = List(byteFollowedByStringOption)
        dhcpHost1.setExtraDhcpOpts(extraDhcpOpts)
        updatedhcpHost(bridge, dhcpSubnet1, dhcpHost1)

        val dhcpReply = sendDhcpDiscoveryAndGetDhcpOffer()
        val byteFollowedByStringDhcpOption =
            extraDhcpOptToDhcpOption(byteFollowedByStringOption)
        byteFollowedByStringDhcpOption should not equal None
        dhcpReply.getOptions.contains(
            byteFollowedByStringDhcpOption.get) should be (true)
    }

    scenario("Rapid Commit Dhcp Extra Option") {
        val rapidCommitOption = new ExtraDhcpOpt(
            DHCPOption.Code.RAPID_COMMIT.value.toString, "")
        val extraDhcpOpts = List(rapidCommitOption)
        dhcpHost1.setExtraDhcpOpts(extraDhcpOpts)
        updatedhcpHost(bridge, dhcpSubnet1, dhcpHost1)

        val dhcpReply = sendDhcpDiscoveryAndGetDhcpOffer()
        val rapdCommitDhcpOption =
            extraDhcpOptToDhcpOption(rapidCommitOption)
        rapdCommitDhcpOption should not equal None
        dhcpReply.getOptions.contains(
            rapdCommitDhcpOption.get) should be (true)
    }

    scenario("Status Code Dhcp Extra Option") {
        val statusCodeOption = new ExtraDhcpOpt(
            DHCPOption.Code.RAPID_COMMIT.value.toString, "2")
        val extraDhcpOpts = List(statusCodeOption)
        dhcpHost1.setExtraDhcpOpts(extraDhcpOpts)
        updatedhcpHost(bridge, dhcpSubnet1, dhcpHost1)

        val dhcpReply = sendDhcpDiscoveryAndGetDhcpOffer()
        val statusCodeDhcpOption =
            extraDhcpOptToDhcpOption(statusCodeOption)
        statusCodeDhcpOption should not equal None
        dhcpReply.getOptions.contains(statusCodeDhcpOption.get) should be (true)
    }

    scenario("Data Source Dhcp Extra Option") {
        val dataSourceOption = new ExtraDhcpOpt(
            DHCPOption.Code.DATA_SOURCE.value.toString, "24")
        val extraDhcpOpts = List(dataSourceOption)
        dhcpHost1.setExtraDhcpOpts(extraDhcpOpts)
        updatedhcpHost(bridge, dhcpSubnet1, dhcpHost1)

        val dhcpReply = sendDhcpDiscoveryAndGetDhcpOffer()
        val dataSourceDhcpOption =
            extraDhcpOptToDhcpOption(dataSourceOption)
        dataSourceDhcpOption should not equal None
        dhcpReply.getOptions.contains(dataSourceDhcpOption.get) should be (true)
    }

    scenario("Classless Routes Dhcp Extra Option") {
        val classlessRoutesOption = new ExtraDhcpOpt(
            DHCPOption.Code.CLASSLESS_ROUTES.value.toString,
            "192.168.100.0/24, 10.0.0.1")
        val extraDhcpOpts = List(classlessRoutesOption)
        dhcpHost1.setExtraDhcpOpts(extraDhcpOpts)
        updatedhcpHost(bridge, dhcpSubnet1, dhcpHost1)

        val dhcpReply = sendDhcpDiscoveryAndGetDhcpOffer()
        val classlessRoutesDhcpOption =
            extraDhcpOptToDhcpOption(classlessRoutesOption)
        classlessRoutesDhcpOption should not equal None
        dhcpReply.getOptions.contains(
            classlessRoutesDhcpOption.get) should be (true)
    }

    scenario("Interface MTU") {
        val returnPkt = injectDhcpDiscover(bridgePort1, bridgePortNumber1, vm1Mac)
        val dhcpReply = extractDhcpReply(returnPkt)
        val mtuValue = ByteBuffer.wrap(dhcpReply.getOptions.find(
            _.getCode == DHCPOption.Code.INTERFACE_MTU.value).get.getData).getShort
        mtuValue should (be (DatapathController.minMtu) or (be (MidolmanConfig.DEFAULT_MTU)))
    }
}
