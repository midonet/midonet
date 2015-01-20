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
package org.midonet.midolman

import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.sys.process._
import java.nio.ByteBuffer

import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.slf4j.LoggerFactory

import org.midonet.cluster.data.dhcp.{Host => DhcpHost}
import org.midonet.cluster.data.dhcp.Opt121
import org.midonet.cluster.data.dhcp.Subnet
import org.midonet.cluster.data.host.Host
import org.midonet.cluster.data.dhcp.ExtraDhcpOpt
import org.midonet.cluster.data.ports.BridgePort
import org.midonet.cluster.data.{TunnelZone, Bridge, Router}
import org.midonet.midolman.PacketWorkflow.PacketIn
import org.midonet.midolman.util.guice.OutgoingMessage
import org.midonet.midolman.host.interfaces.InterfaceDescription
import org.midonet.midolman.layer3.Route
import org.midonet.midolman.layer3.Route.NextHop
import org.midonet.midolman.simulation.DhcpValueParser
import org.midonet.midolman.topology.LocalPortActive
import org.midonet.midolman.topology.VirtualToPhysicalMapper._
import org.midonet.midolman.util.MidolmanTestCase
import org.midonet.midolman.util.RouterHelper
import org.midonet.midolman.util.SimulationHelper
import org.midonet.packets._

object DhcpTestCase {
    private val allDhcpOptions: Array[DHCPOption.Code] = DHCPOption.Code.values

    private
    def extraDhcpOptToDhcpOption(opt: ExtraDhcpOpt): Option[DHCPOption] = for {
        code <- DhcpValueParser.parseDhcpOptionCode(opt.optName)
        value <- DhcpValueParser.parseDhcpOptionValue(code, opt.optValue)
    } yield new DHCPOption(code, value.length.toByte, value)
}

@Category(Array(classOf[SimulationTests]))
@RunWith(classOf[JUnitRunner])
class DhcpTestCase extends MidolmanTestCase
        with SimulationHelper with RouterHelper {
    import DhcpTestCase._

    private final val log = LoggerFactory.getLogger(classOf[DhcpTestCase])

    var router: Router = null

    val routerIp1 = new IPv4Subnet("192.168.11.1", 24)
    val routerMac1 = MAC.fromString("22:aa:aa:ff:ff:ff")

    val routerIp2 = new IPv4Subnet("192.168.22.1", 24)
    val routerMac2 = MAC.fromString("22:ab:cd:ff:ff:ff")

    val routerIp3 = new IPv4Subnet("192.168.33.1", 24)
    val routerMac3 = MAC.fromString("22:aa:ee:ff:ff:ff")

    val vm1Mac = MAC.fromString("02:23:24:25:26:27")
    val vm2Mac = MAC.fromString("02:53:53:53:53:53")

    var bridge: Bridge = null
    var brPort1: BridgePort = null
    var brPort2: BridgePort = null
    var brPort3: BridgePort = null

    var vm1IP : IPv4Subnet = null // assigned from the ifc scanner
    val vm1PortName = "VirtualMachine1"
    var vm1PortNumber = 0

    // assigned by us, we just want to use this one
    // for multiple subnet
    var vm2IP: IPv4Subnet = new IPv4Subnet("192.168.33.33", 24)
    val vm2PortName = "VirtualMachine2"
    var vm2PortNumber = 1

    var host: Host = null
    var host2: Host = null

    private var dhcpSubnet1: Subnet = null
    private var dhcpSubnet2: Subnet = null

    private var dhcpHost1: DhcpHost = null
    private var dhcpHost2: DhcpHost = null

    var intfMtu = 0

    private def getIp: String = {
        val cmd = ( "/sbin/ifconfig"
                    + "| grep -w inet | grep -vw 127.0.0.1"
                    + "| egrep -o '((1?[0-9]{1,2}|2([0-5]){2})\\.?){4}'"
                    + "| sed -n 1p" )
        val sIp = Seq("sh", "-c", cmd).!!.trim
        log.debug(s"looking for ip address with command $cmd yields $sIp")
        sIp
    }

    private def getMtu(ip: String): String = {
        // try to catch the mtu var around the ip captured by cmdline_ip
        // it should be 3 lines above on OSX and 2 lines below on linux
        val cmd = ( "/sbin/ifconfig | grep -A 2 -B 3 " + ip
                            + "| egrep -o -i 'mtu(:| )[0-9]+'"
                            + "| cut -c 5-" )

        val sMtu = Seq("sh", "-c", cmd).!!.trim
        log.debug(s"Looking for MTU with command $cmd yields $sMtu")
        sMtu
    }

    private def routerExtPort(router: Router, mac: MAC, ip: IPv4Addr) = {
        val port = newRouterPort(router, mac,
            routerIp1.toUnicastString,
            routerIp1.toNetworkAddress.toString,
            routerIp1.getPrefixLen)
        port should not be null
        materializePort(port, host, "RouterPort1")
        val portEvent = requestOfType[LocalPortActive](portsProbe)
        portEvent.active should be (true)
        portEvent.portID should be (port.getId)
        port
    }

    private def routerIntPort(router: Router, mac: MAC, ip: IPv4Addr) = {
        val rtrPort2 = newInteriorRouterPort(router, routerMac2,
            routerIp2.toUnicastString,
            routerIp2.toNetworkAddress.toString,
            routerIp2.getPrefixLen)
        rtrPort2 should not be null
        rtrPort2
    }

    // make host, bridge, router
    private def initDevices() {
        host = newHost("myself", hostId())
        host should not be null
        host2 = newHost("someone else")
        host2 should not be null
        router = newRouter("router")
        router should not be null
        bridge = newBridge("bridge")
        bridge should not be null
    }

    override def beforeTest() {
        initDevices()

        val ipString = getIp
        val sIfcMtu = getMtu(ipString)

        intfMtu = sIfcMtu.toInt // TODO(guillermo) use mock interface scanner
        vm1IP = new IPv4Subnet(ipString, 24)

        // Add this interface in MockInterfaceScanner list
        val intf = new InterfaceDescription("My Interface")
        intf.setInetAddress(ipString)
        intf.setMtu(intfMtu)
        interfaceScanner.addInterface(intf)

        val greZone = greTunnelZone("default")

        val myGreConfig = new TunnelZone.HostConfig(host.getId)
                              .setIp(IPv4Addr.fromString(ipString))

        val peerGreConfig = new TunnelZone.HostConfig(host2.getId)
                                .setIp(IPv4Addr.fromString("192.168.200.1"))

        clusterDataClient().tunnelZonesAddMembership(greZone.getId, peerGreConfig)
        clusterDataClient().tunnelZonesAddMembership(greZone.getId, myGreConfig)

        initializeDatapath() should not be (null)
        requestOfType[HostRequest](vtpProbe())
        requestOfType[OutgoingMessage](vtpProbe())

        val rtrPort1 = routerExtPort(router, routerMac1, routerIp1.getAddress)

        newRoute(router, "0.0.0.0", 0, routerIp1.toUnicastString,
                 routerIp1.getPrefixLen, NextHop.PORT, rtrPort1.getId,
                 IPv4Addr(Route.NO_GATEWAY).toString, 10)

        val rtrPort2 = routerIntPort(router, routerMac2, routerIp2.getAddress)
        val rtrPort3 = routerIntPort(router, routerMac3, routerIp3.getAddress)

        val brIntPort1 = newBridgePort(bridge)
        brIntPort1 should not be null
        clusterDataClient().portsLink(rtrPort2.getId, brIntPort1.getId)

        val brIntPort2 = newBridgePort(bridge)
        brIntPort1 should not be null
        clusterDataClient().portsLink(rtrPort3.getId, brIntPort2.getId)

        val brPort2 = newBridgePort(bridge)
        brPort2 should not be null

        val brPort3 = newBridgePort(bridge)
        brPort3 should not be null

        val tzRequest = fishForRequestOfType[TunnelZoneRequest](vtpProbe())
        tzRequest.zoneId should be (greZone.getId)

        // First subnet is routerIp2's
        var opt121Obj = new Opt121()
                        .setGateway(routerIp2.getAddress)
                        // TODO (galo) after talking with Abel we suspect that
                        // this below should be routerIp1, not 2. It may be
                        // irrelevant for the test, but we should confirm
                        .setRtDstSubnet(routerIp1)
        var opt121Routes: List[Opt121] = List(opt121Obj)
        val dnsSrvAddrs : List[IPv4Addr] = List(
                                          IPv4Addr.fromString("192.168.77.118"),
                                          IPv4Addr.fromString("192.168.77.119"),
                                          IPv4Addr.fromString("192.168.77.120"))
        dhcpSubnet1 = new Subnet()
            .setSubnetAddr(routerIp2)
            .setDefaultGateway(routerIp2.getAddress)
            .setDnsServerAddrs(dnsSrvAddrs)
            .setOpt121Routes(opt121Routes)
        addDhcpSubnet(bridge, dhcpSubnet1)

        // Second subnet is routerIp2's
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

        log.debug("Materializing ports..")
        materializePort(brPort2, host, vm1PortName)
        requestOfType[LocalPortActive](portsProbe)

        materializePort(brPort3, host, vm2PortName)
        requestOfType[LocalPortActive](portsProbe)

        log.debug("Creating DHCP Host")
        dhcpHost1 = new org.midonet.cluster.data.dhcp.Host()
            .setMAC(vm1Mac)
            .setIp(vm1IP.getAddress)
        addDhcpHost(bridge, dhcpSubnet1, dhcpHost1)

        dhcpHost2 = new org.midonet.cluster.data.dhcp.Host()
            .setMAC(vm2Mac)
            .setIp(vm2IP.getAddress)
        addDhcpHost(bridge, dhcpSubnet2, dhcpHost2)

        datapathEventsProbe.expectMsgType[DatapathController.DatapathReady]
                   .datapath should not be (null)
        drainProbes()
    }

    private def injectDhcpDiscover(portName: String, srcMac : MAC) {
        val dhcpDiscover = new DHCP()
        dhcpDiscover.setOpCode(0x01)
        dhcpDiscover.setHardwareType(0x01)
        dhcpDiscover.setHardwareAddressLength(6)
        dhcpDiscover.setClientHardwareAddress(srcMac)
        val options = mutable.ListBuffer[DHCPOption]()
        options.add(new DHCPOption(DHCPOption.Code.DHCP_TYPE.value,
                           DHCPOption.Code.DHCP_TYPE.length,
                           Array[Byte](DHCPOption.MsgType.DISCOVER.value)))
        options.add(new DHCPOption(DHCPOption.Code.DNS.value,
            DHCPOption.Code.DNS.length,
            Array(192,168,1,1).map(_.toByte)))
        dhcpDiscover.setOptions(options)
        val udp = new UDP()
        udp.setSourcePort((68).toShort)
        udp.setDestinationPort((67).toShort)
        udp.setPayload(dhcpDiscover)
        val eth = new Ethernet()
        eth.setSourceMACAddress(srcMac)
        eth.setDestinationMACAddress(MAC.fromString("ff:ff:ff:ff:ff:ff"))
        eth.setEtherType(IPv4.ETHERTYPE)
        eth.setPayload(new IPv4().setSourceAddress(0).
                                  setDestinationAddress(0xffffffff).
                                  setProtocol(UDP.PROTOCOL_NUMBER).
                                  setPayload(udp))
        triggerPacketIn(portName, eth)
    }

    private def extractDhcpReply(ethPkt : Ethernet) = {
        ethPkt.getEtherType should be (IPv4.ETHERTYPE)
        val ipPkt = ethPkt.getPayload.asInstanceOf[IPv4]
        ipPkt.getProtocol should be (UDP.PROTOCOL_NUMBER)
        val udpPkt = ipPkt.getPayload.asInstanceOf[UDP]
        udpPkt.getSourcePort should be (67)
        udpPkt.getDestinationPort should be (68)
        udpPkt.getPayload.asInstanceOf[DHCP]
    }

    // broaden to allow us to check other fields in the DHCP reply options
    private def extractInterfaceMtuDhcpReply(ethPkt : Ethernet) : Short = {
        val dhcpPkt = extractDhcpReply(ethPkt)
        val replyOptions = mutable.HashMap[Byte, DHCPOption]()
        var mtu : Short = 0
        for (opt <- dhcpPkt.getOptions) {
            val code = opt.getCode
            replyOptions.put(code, opt)
            code match {
                case v if v == DHCPOption.Code.INTERFACE_MTU.value =>
                    if (opt.getLength != 2) {
                        fail("DHCP option interface mtu value invalid length")
                    }
                    mtu = ByteBuffer.wrap(opt.getData).getShort
                    log.info(s"extractInterfaceMtuDhcpReply got data ${opt.getData} and value $mtu")
                case b if b == DHCPOption.Code.DNS.value =>
                    var len : Int = opt.getLength.toInt
                    var offset : Int = 0
                    var byteptr = ByteBuffer.wrap(opt.getData)
                    while (len > 0) {
                        val ipAddr: Int = byteptr.getInt
                        val ipv4Addr: IPv4Addr = IPv4Addr(ipAddr)
                        log.info("DNS server addr {}", ipv4Addr.toString)
                        len = len - 4
                        if (len > 0) {
                            offset = offset + 4
                            byteptr = ByteBuffer.wrap(opt.getData, offset, 4)
                        }
                    }
                case _ => 0
            }
        }
        mtu
    }

    private def sendDhcpDiscoveryAndGetDhcpOffer(portName: String = vm1PortName,
                                                 mac: MAC = vm1Mac): DHCP = {
        injectDhcpDiscover(portName, mac)
        requestOfType[PacketIn](packetInProbe)
        val returnPkt =
            requestOfType[PacketsExecute](packetsEventsProbe).packet.getEthernet
        drainProbes()
        extractDhcpReply(returnPkt)
    }

    /**
      * Setup a bridge connected to a router via 2 ports. The bridge has 2 DHCP
      * subnets configured, with gateways on the corresponding port. We expect
      * this to work normally. See MN-565.
      */
    def testMultipleSubnets(): Unit = {
        val dhcpReplyForVm1 =
            sendDhcpDiscoveryAndGetDhcpOffer(vm1PortName, vm1Mac)
        dhcpReplyForVm1.getServerIPAddress should be (routerIp2.getIntAddress)

        val dhcpReplyForVm2 =
            sendDhcpDiscoveryAndGetDhcpOffer(vm2PortName, vm2Mac)
        dhcpReplyForVm2.getServerIPAddress should be (routerIp3.getIntAddress)
    }

    def testDhcpExtraOption(): Unit = {
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

    def testDhcpExtraOptionWithName(): Unit = {
        val hostNameOption = new ExtraDhcpOpt("host-name", "foobar")
        val extraDhcpOpts = List(hostNameOption)
        dhcpHost1.setExtraDhcpOpts(extraDhcpOpts)
        updatedhcpHost(bridge, dhcpSubnet1, dhcpHost1)

        val dhcpReply = sendDhcpDiscoveryAndGetDhcpOffer()
        val hostNameDhcpOption = extraDhcpOptToDhcpOption(hostNameOption)
        hostNameDhcpOption should not equal None
        dhcpReply.getOptions.contains(hostNameDhcpOption.get) should be (true)
    }

    def testDhcpExtraOptionWithoutDhcpHostUpdate(): Unit = {
        val hostNameOption = new ExtraDhcpOpt(
            DHCPOption.Code.HOST_NAME.value.toString, "foobar")

        val dhcpReply = sendDhcpDiscoveryAndGetDhcpOffer()
        val hostNameDhcpOption = extraDhcpOptToDhcpOption(hostNameOption)
        hostNameDhcpOption should not equal None
        dhcpReply.getOptions.contains(hostNameDhcpOption.get) should be (false)
    }

    def testInvalidDhcpExtraOptionShouldBeIgnored(): Unit = {
        val dhcpReply = sendDhcpDiscoveryAndGetDhcpOffer()

        val invalidOption = new ExtraDhcpOpt("I am", "an invalid option")
        val extraDhcpOpts = List(invalidOption)
        dhcpHost1.setExtraDhcpOpts(extraDhcpOpts)
        updatedhcpHost(bridge, dhcpSubnet1, dhcpHost1)

        val dhcpReplyWithInvalidOption = sendDhcpDiscoveryAndGetDhcpOffer()
        dhcpReply.getOptions should equal (
            dhcpReplyWithInvalidOption.getOptions)
    }

    def testDhcpExtraOptionWithDuplicatedOption(): Unit = {
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

    def testInvalidDhcpExtraOptionValueShouldBeIgnored(): Unit = {
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

    def testIpRequiredDhcpExtraOption(): Unit = {
        val emptyRouterOption = new ExtraDhcpOpt(
            DHCPOption.Code.ROUTER.value.toString, "")
        val emptyExtraDhcpOpts = List(emptyRouterOption)
        dhcpHost1.setExtraDhcpOpts(emptyExtraDhcpOpts)
        updatedhcpHost(bridge, dhcpSubnet1, dhcpHost1)

        val emptyRouterdhcpReply = sendDhcpDiscoveryAndGetDhcpOffer()
        val emptyRouterDhcpOption = extraDhcpOptToDhcpOption(emptyRouterOption)
        emptyRouterDhcpOption should be (None)

        val routerOption = new ExtraDhcpOpt(
            DHCPOption.Code.ROUTER.value.toString, "192.168.1.1, 192.168.11.1")
        val extraDhcpOpts = List(routerOption)
        dhcpHost1.setExtraDhcpOpts(extraDhcpOpts)
        updatedhcpHost(bridge, dhcpSubnet1, dhcpHost1)

        val dhcpReply = sendDhcpDiscoveryAndGetDhcpOffer()
        val routerDhcpOption = extraDhcpOptToDhcpOption(routerOption)
        routerDhcpOption should not equal (None)
        dhcpReply.getOptions.contains(routerDhcpOption.get) should be (true)
    }

    def testBooleanRequiredDhcpExtraOption(): Unit = {
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

    def testCidrRequiredDhcpExtraOption(): Unit = {
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

    def test1248RequiredDhcpExtraOption(): Unit = {
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

    def test1to3RequiredDhcpExtraOption(): Unit = {
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

    def test1to8RequiredDhcpExtraOption(): Unit = {
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

    def testClientIdentifierDhcpExtraOption(): Unit = {
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

    def testByteFollowdByIpAddrsDhcpExtraOption(): Unit = {
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

    def testByteFollowdByStringDhcpExtraOption(): Unit = {
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

    def testRapidCommitDhcpExtraOption(): Unit = {
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

    def testStatusCodeDhcpExtraOption(): Unit = {
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

    def testDataSourceDhcpExtraOption(): Unit = {
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

    def testClasslessRoutesDhcpExtraOption(): Unit = {
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
}
