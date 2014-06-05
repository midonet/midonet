/*
 * Copyright 2012 Midokura
 */
package org.midonet.midolman

import java.nio.ByteBuffer
import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.sys.process._

import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.slf4j.LoggerFactory

import org.midonet.cluster.data.dhcp.Opt121
import org.midonet.cluster.data.dhcp.Subnet
import org.midonet.cluster.data.ports.BridgePort
import org.midonet.cluster.data.zones._
import org.midonet.midolman.DeduplicationActor.EmitGeneratedPacket
import org.midonet.midolman.host.interfaces.InterfaceDescription
import org.midonet.midolman.layer3.Route
import org.midonet.midolman.layer3.Route.NextHop
import org.midonet.midolman.topology.LocalPortActive
import org.midonet.midolman.topology.VirtualToPhysicalMapper._
import org.midonet.midolman.util.MidolmanTestCase
import org.midonet.midolman.util.RouterHelper
import org.midonet.midolman.util.SimulationHelper
import org.midonet.midolman.util.guice.OutgoingMessage
import org.midonet.odp.flows.{FlowActionOutput, FlowAction}
import org.midonet.odp.ports.GreTunnelPort
import org.midonet.packets._

@Category(Array(classOf[SimulationTests]))
@RunWith(classOf[JUnitRunner])
class DhcpInterfaceMtuTestCase extends MidolmanTestCase
        with SimulationHelper with RouterHelper {

    private final val log = LoggerFactory.getLogger(classOf[DhcpInterfaceMtuTestCase])

    val routerIp1 = new IPv4Subnet("192.168.111.1", 24)
    val routerMac1 = MAC.fromString("22:aa:aa:ff:ff:ff")
    val routerIp2 = new IPv4Subnet("192.168.222.1", 24)
    val routerMac2 = MAC.fromString("22:ab:cd:ff:ff:ff")
    val vmMac = MAC.fromString("02:23:24:25:26:27")
    var brPort : BridgePort = null
    var vmIP : IPv4Subnet = null
    val vmPortName = "VirtualMachine"
    var vmPortNumber = 0
    var intfMtu = 0

    override def beforeTest() {

        val host = newHost("myself", hostId())
        host should not be null
        val host2 = newHost("someone else")
        host2 should not be null
        val router = newRouter("router")
        router should not be null
        val bridge = newBridge("bridge")
        bridge should not be null

        val cmdline_ip = ( "/sbin/ifconfig"
                            + "| grep -w inet | grep -vw 127.0.0.1"
                            + "| egrep -o '((1?[0-9]{1,2}|2([0-5]){2})\\.?){4}'"
                            + "| sed -n 1p" )

        log.debug("looking for ip address with command {}", cmdline_ip)

        val ipString: String = Seq("sh", "-c", cmdline_ip).!!.trim

        log.debug("ipString is {}", ipString)

        // try to catch the mtu var around the ip captured by cmdline_ip
        // it should be 3 lines above on OSX and 2 lines below on linux
        val cmdline_mtu = ( "/sbin/ifconfig"
                            + "| grep -A 2 -B 3 " + ipString
                            + "| egrep -o -i 'mtu(:| )[0-9]+'"
                            + "| cut -c 5-" )

        log.debug("looking for MTU with command {}", cmdline_mtu)

        val intfMtu_string: String = Seq("sh", "-c", cmdline_mtu).!!.trim

        log.debug("MTU is {}", intfMtu_string)

        // store original interface MTU
        // TODO(guillermo) use pino's mock interface scanner when merged.
        intfMtu = intfMtu_string.toInt

        vmIP = new IPv4Subnet(ipString, 24)

        // add this interface in MockInterfaceScanner list
        val intf = new InterfaceDescription("My Interface")
        intf.setInetAddress(ipString)
        intf.setMtu(intfMtu)
        interfaceScanner.addInterface(intf)

        val greZone = greTunnelZone("default")

        val myGreConfig = new GreTunnelZoneHost(host.getId)
            .setIp(IPv4Addr(ipString).toIntIPv4)

        val peerGreConfig = new GreTunnelZoneHost(host2.getId)
            .setIp(IPv4Addr("192.168.200.1").toIntIPv4)

        clusterDataClient().tunnelZonesAddMembership(greZone.getId, peerGreConfig)
        clusterDataClient().tunnelZonesAddMembership(greZone.getId, myGreConfig)

        initializeDatapath() should not be (null)
        requestOfType[HostRequest](vtpProbe())
        requestOfType[OutgoingMessage](vtpProbe())

        val rtrPort1 = newRouterPort(router, routerMac1,
            routerIp1.getAddress.toString,
            routerIp1.toNetworkAddress.toString,
            routerIp1.getPrefixLen)
        rtrPort1 should not be null
        materializePort(rtrPort1, host, "RouterPort1")
        val portEvent = requestOfType[LocalPortActive](portsProbe)
        portEvent.active should be(true)
        portEvent.portID should be(rtrPort1.getId)

        newRoute(router, "0.0.0.0", 0,
            routerIp1.toNetworkAddress.toString, routerIp1.getPrefixLen,
            NextHop.PORT, rtrPort1.getId,
            IPv4Addr(Route.NO_GATEWAY).toString, 10)

        val rtrPort2 = newRouterPort(router, routerMac2,
            routerIp2.getAddress.toString,
            routerIp2.toNetworkAddress.toString,
            routerIp2.getPrefixLen)
        rtrPort2 should not be null

        val brPort1 = newBridgePort(bridge)
        brPort1 should not be null
        clusterDataClient().portsLink(rtrPort2.getId, brPort1.getId)

        val brPort2 = newBridgePort(bridge)
        brPort2 should not be null

        val tzRequest = fishForRequestOfType[TunnelZoneRequest](vtpProbe())
        tzRequest.zoneId should be (greZone.getId)

        var opt121Obj = (new Opt121()
                        .setGateway(routerIp2)
                        .setRtDstSubnet(routerIp1))
        var opt121Routes: List[Opt121] = List(opt121Obj)
        var dnsSrvAddrs : List[IPv4Addr] = List(
            IPv4Addr("192.168.77.118"),
            IPv4Addr("192.168.77.119"),
            IPv4Addr("192.168.77.120"))
        var dhcpSubnet = (new Subnet()
                      .setSubnetAddr(routerIp2)
                      .setDefaultGateway(routerIp2)
                      .setDnsServerAddrs(dnsSrvAddrs.map(_.toIntIPv4))
                      .setOpt121Routes(opt121Routes))
        addDhcpSubnet(bridge, dhcpSubnet)

        materializePort(brPort2, host, vmPortName)
        requestOfType[LocalPortActive](portsProbe)

        val dhcpHost = (new org.midonet.cluster.data.dhcp.Host()
                       .setMAC(vmMac)
                       .setIp(new IntIPv4(vmIP)))
        addDhcpHost(bridge, dhcpSubnet, dhcpHost)

        flowProbe().expectMsgType[DatapathController.DatapathReady].datapath should not be (null)
        drainProbes()
    }

    private def expectPacketOut(portNum : Int): Ethernet = {
        val pktOut = requestOfType[PacketsExecute](packetsEventsProbe)
        pktOut.packet should not be null
        pktOut.packet.getPacket should not be null
        log.debug("Packet execute: {}", pktOut)

        pktOut.actions.size should equal (1)

        pktOut.actions.toList map { action =>
            action.getClass() should be (classOf[FlowActionOutput])
            action.asInstanceOf[FlowActionOutput].getPortNumber
        } should contain (portNum)

        pktOut.packet.getPacket
    }

    private def injectDhcpDiscover(portName: String, srcMac : MAC) {
        val dhcpDiscover = new DHCP()
        dhcpDiscover.setOpCode(0x01)
        dhcpDiscover.setHardwareType(0x01)
        dhcpDiscover.setHardwareAddressLength(6)
        dhcpDiscover.setClientHardwareAddress(srcMac)
        var options = mutable.ListBuffer[DHCPOption]()
        options.add(new DHCPOption(DHCPOption.Code.DHCP_TYPE.value,
                           DHCPOption.Code.DHCP_TYPE.length,
                           Array[Byte](DHCPOption.MsgType.DISCOVER.value)))
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

    // broaden to allow us to check other fields in the DHCP reply options
    private def extractInterfaceMtuDhcpReply(ethPkt : Ethernet) : Short = {
        ethPkt.getEtherType should be (IPv4.ETHERTYPE)
        val ipPkt = ethPkt.getPayload.asInstanceOf[IPv4]
        ipPkt.getProtocol should be (UDP.PROTOCOL_NUMBER)
        val udpPkt = ipPkt.getPayload.asInstanceOf[UDP]
        udpPkt.getSourcePort() should be (67)
        udpPkt.getDestinationPort() should be (68)
        val dhcpPkt = udpPkt.getPayload.asInstanceOf[DHCP]
        val replyOptions = mutable.HashMap[Byte, DHCPOption]()
        val replyCodes = mutable.Set[Byte]()
        var mtu : Short = 0
        for (opt <- dhcpPkt.getOptions) {
            val code = opt.getCode
            replyOptions.put(code, opt)
            code match {
                case v if (v == DHCPOption.Code.INTERFACE_MTU.value) =>
                    if (opt.getLength != 2) {
                        fail("DHCP option interface mtu value invalid length")
                    }
                    mtu = ByteBuffer.wrap(opt.getData).getShort
                    log.debug("extractInterfaceMtuDhcpReply got data {} and value {}", opt.getData, mtu)
                case b if (b == DHCPOption.Code.DNS.value) =>
                    var len : Int = (opt.getLength).toInt
                    var offset : Int = 0
                    var byteptr = ByteBuffer.wrap(opt.getData)
                    while (len > 0) {
                        val ipAddr : Int = byteptr.getInt
                        val ipv4Addr : IPv4Addr = IPv4Addr(ipAddr)
                        log.debug("DNS server addr {}", ipv4Addr.toString)
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

    def test() {
        injectDhcpDiscover(vmPortName, vmMac)
        val returnPkt = fishForRequestOfType[EmitGeneratedPacket](dedupProbe()).eth
        val interfaceMtu = extractInterfaceMtuDhcpReply(returnPkt)
        log.info("Returning interface MTU is {}", interfaceMtu)
        intfMtu -= GreTunnelPort.TunnelOverhead
        interfaceMtu should equal (intfMtu)
    }
}
