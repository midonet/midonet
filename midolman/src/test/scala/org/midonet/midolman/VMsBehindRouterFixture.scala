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
import scala.util.Try

import akka.testkit.TestProbe
import org.slf4j.LoggerFactory

import org.midonet.cluster.data.{Bridge => ClusterBridge,
                                 Router => ClusterRouter}
import org.midonet.cluster.data.host.Host
import org.midonet.cluster.data.ports.BridgePort
import org.midonet.midolman.layer3.Route
import org.midonet.midolman.layer3.Route.NextHop
import org.midonet.midolman.topology.LocalPortActive
import org.midonet.midolman.topology.VirtualToPhysicalMapper.HostRequest
import org.midonet.midolman.util.MidolmanTestCase
import org.midonet.midolman.util.SimulationHelper
import org.midonet.midolman.util.VirtualConfigurationBuilders
import org.midonet.midolman.util.guice.OutgoingMessage
import org.midonet.packets._

trait VMsBehindRouterFixture extends SimulationHelper with
        VirtualConfigurationBuilders { self: MidolmanTestCase =>
    private final val log =
        LoggerFactory.getLogger(classOf[VMsBehindRouterFixture])

    val vmNetworkIp = new IPv4Subnet("10.0.0.0", 24)
    val routerIp = new IPv4Subnet("10.0.0.254", 24)
    val routerMac = MAC.fromString("22:aa:aa:ff:ff:ff")

    val vmPortNames = IndexedSeq("port0", "port1", "port2", "port3", "port4")
    var vmPorts: IndexedSeq[BridgePort] = null

    var vmPortNumbers: IndexedSeq[Int] = null
    val vmMacs = IndexedSeq(MAC.fromString("02:aa:bb:cc:dd:d1"),
        MAC.fromString("02:aa:bb:cc:dd:d2"),
        MAC.fromString("02:aa:bb:cc:dd:d3"),
        MAC.fromString("02:aa:bb:cc:dd:d4"),
        MAC.fromString("02:aa:bb:cc:dd:d5"))
    val vmIps = IndexedSeq(IPv4Addr.fromString("10.0.0.1"),
        IPv4Addr.fromString("10.0.0.2"),
        IPv4Addr.fromString("10.0.0.3"),
        IPv4Addr.fromString("10.0.0.4"),
        IPv4Addr.fromString("10.0.0.5"))
    val v6VmIps = IndexedSeq(
        IPv6Addr.fromString("fe80:0:0:0:0:7ed1:c3ff:1"),
        IPv6Addr.fromString("fe80:0:0:0:0:7ed1:c3ff:2"),
        IPv6Addr.fromString("fe80:0:0:0:0:7ed1:c3ff:3"),
        IPv6Addr.fromString("fe80:0:0:0:0:7ed1:c3ff:4"),
        IPv6Addr.fromString("fe80:0:0:0:0:7ed1:c3ff:5"))

    var bridge: ClusterBridge = null
    var router: ClusterRouter = null
    var host: Host = null

    override def beforeTest() {

        host = newHost("myself", hostId())
        host should not be null
        router = newRouter("router")
        router should not be null

        initializeDatapath() should not be (null)
        requestOfType[HostRequest](vtpProbe())
        requestOfType[OutgoingMessage](vtpProbe())

        val rtrPort = newRouterPort(router, routerMac,
            routerIp.toUnicastString, routerIp.toNetworkAddress.toString,
            routerIp.getPrefixLen)
        rtrPort should not be null

        newRoute(router, "0.0.0.0", 0,
            routerIp.toNetworkAddress.toString, routerIp.getPrefixLen,
            NextHop.PORT, rtrPort.getId,
            new IPv4Addr(Route.NO_GATEWAY).toString, 10)

        bridge = newBridge("bridge")
        bridge should not be null

        val brPort = newBridgePort(bridge)
        brPort should not be null
        clusterDataClient().portsLink(rtrPort.getId, brPort.getId)

        vmPorts = vmPortNames map { _ => newBridgePort(bridge) }
        vmPorts zip vmPortNames foreach {
            case (port, name) =>
                log.debug("Materializing port {}", name)
                materializePort(port, host, name)
                requestOfType[LocalPortActive](portsProbe)
        }
        flowProbe().expectMsgType[DatapathController.DatapathReady].datapath should not be (null)
        vmPortNumbers = ensureAllPortsUp(vmPorts)
        drainProbes()
    }

    def ensureAllPortsUp(vmPorts: IndexedSeq[BridgePort]): IndexedSeq[Int] = {
        def checkPorts() = vmPorts map {
            port =>
                vifToLocalPortNumber(port.getId) match {
                    case Some(portNo : Short) => portNo
                    case None =>
                        fail("Unable to find data port number for " +
                             port.getInterfaceName)
                        0
                }
        }
        def pollPorts(nTry: Int = 20): IndexedSeq[Int] = {
            Thread sleep 50
            val t = Try(checkPorts())
            if (nTry == 0) t.get else t.getOrElse(pollPorts(nTry-1))
        }
        pollPorts()
    }

    def expectPacketOut(port: Int, numPorts: Seq[Int] = List(1)): Ethernet = {
        val pktOut = getPacketOut
        log.debug("Packet execute: {}", pktOut)
        pktOut.packet.getEthernet
    }

    def expectPacketOutRouterToVm(port: Int): Ethernet =
        expectPacketOut(port, List(1, vmPortNames.size))

    def expectPacketOutVmToVm(port: Int): Ethernet =
        expectPacketOut(port, List(1, vmPortNames.size - 1))

    def arpVmToRouterAndCheckReply(portName: String, srcMac: MAC, srcIp: IPv4Addr,
                               dstIp: IPv4Addr, expectedMac: MAC) {
        injectArpRequest(portName, srcIp.addr, srcMac, dstIp.addr)
        val pkt = expectPacketOutRouterToVm(vmPortNameToPortNumber(portName))
        log.debug("Packet out: {}", pkt)
        // TODO(guillermo) check the arp reply packet
    }

    def vmPortNameToPortNumber(portName: String): Int = {
        for ((name, port) <- vmPortNames zip vmPortNumbers) {
            if (name == portName)
                return port
        }
        fail("Unknown port: " + portName)
        0
    }

    def icmpBetweenPorts(portIndexA: Int, portIndexB: Int): Ethernet = {
        val echo = new ICMP()
        echo.setEchoRequest(16, 32, "My ICMP".getBytes)
        val eth: Ethernet = new Ethernet().
            setSourceMACAddress(vmMacs(portIndexA)).
            setDestinationMACAddress(vmMacs(portIndexB)).
            setEtherType(IPv4.ETHERTYPE)
        eth.setPayload(new IPv4().setSourceAddress(vmIps(portIndexA)).
            setDestinationAddress(vmIps(portIndexB)).
            setProtocol(ICMP.PROTOCOL_NUMBER).
            setPayload(echo))
        eth
    }

    def ipv6BetweenPorts(portIndexA: Int, portIndexB: Int): Ethernet = {
        val ip6 = new IPv6().setSourceAddress(v6VmIps(portIndexA)).
                             setDestinationAddress(v6VmIps(portIndexB)).
                             setPayload(new Data("IPv6 payload".getBytes))
        val eth: Ethernet = new Ethernet().
            setSourceMACAddress(vmMacs(portIndexA)).
            setDestinationMACAddress(vmMacs(portIndexB)).
            setEtherType(IPv6.ETHERTYPE)
        eth.setPayload(ip6)
        eth
    }

    def tcpBetweenPorts(portIndexA: Int, portIndexB: Int,
                        tcpPortSrc: Short, tcpPortDst: Short): Ethernet = {
        val tcp = new TCP()
        tcp.setSourcePort(tcpPortSrc)
        tcp.setDestinationPort(tcpPortDst)
        tcp.setPayload(new Data().setData("TCP payload".getBytes))
        val eth: Ethernet = new Ethernet().
            setSourceMACAddress(vmMacs(portIndexA)).
            setDestinationMACAddress(vmMacs(portIndexB)).
            setEtherType(IPv4.ETHERTYPE)
        eth.setPayload(new IPv4().setSourceAddress(vmIps(portIndexA)).
            setDestinationAddress(vmIps(portIndexB)).
            setProtocol(TCP.PROTOCOL_NUMBER).
            setPayload(tcp))
        eth
    }

    def udpBetweenPorts(portIndexA: Int, portIndexB: Int): Ethernet = {
        val udp = new UDP()
        udp.setSourcePort((12000 + portIndexA).toShort)
        udp.setDestinationPort((12000 + portIndexB).toShort)
        udp.setPayload(new Data().setData("UDP payload".getBytes))
        val eth: Ethernet = new Ethernet().
            setSourceMACAddress(vmMacs(portIndexA)).
            setDestinationMACAddress(vmMacs(portIndexB)).
            setEtherType(IPv4.ETHERTYPE)
        eth.setPayload(new IPv4().setSourceAddress(vmIps(portIndexA)).
            setDestinationAddress(vmIps(portIndexB)).
            setProtocol(UDP.PROTOCOL_NUMBER).
            setPayload(udp))
        eth
    }

    def lldpBetweenPorts(portIndexA: Int, portIndexB: Int): Ethernet = {
        val chassis = new LLDPTLV().setType(0x1.toByte).setLength(7.toShort).
            setValue("chassis".getBytes)
        val port = new LLDPTLV().setType(0x2.toByte).setLength(4.toShort).
            setValue("port".getBytes)
        val ttl = new LLDPTLV().setType(0x3.toByte).setLength(3.toShort).
            setValue("ttl".getBytes)
        val lldp = new LLDP().setChassisId(chassis).setPortId(port).setTtl(ttl)

        val eth: Ethernet = new Ethernet().setEtherType(LLDP.ETHERTYPE).
                                 setSourceMACAddress(vmMacs(portIndexA)).
                                 setDestinationMACAddress(vmMacs(portIndexB))
        eth.setPayload(lldp)
        eth
    }

    def expectPacketAllowed(portIndexA: Int, portIndexB: Int,
                            packetGenerator: (Int, Int) => Ethernet) {
        val eth = packetGenerator(portIndexA, portIndexB)
        triggerPacketIn(vmPortNames(portIndexA), eth)
        val outpkt = expectPacketOutVmToVm(vmPortNameToPortNumber(vmPortNames(portIndexB)))
        outpkt should be (eth)
        outpkt.getPayload should be (eth.getPayload)
        outpkt.getPayload.getPayload should be (eth.getPayload.getPayload)
        log.info(s"Packet received on ${vmPortNames(portIndexA)} " +
                 s"forwarded to ${vmPortNames(portIndexB)}")
    }

    def expectPacketDropped(portIndexA: Int, portIndexB: Int,
                            packetGenerator: (Int, Int) => Ethernet) {
        triggerPacketIn(vmPortNames(portIndexA),
                        packetGenerator(portIndexA, portIndexB))
        packetsEventsProbe.expectNoMsg()
    }
}
