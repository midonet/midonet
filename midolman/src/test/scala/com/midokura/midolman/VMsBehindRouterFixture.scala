/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman

import scala.Some

import akka.testkit.TestProbe
import org.slf4j.LoggerFactory
import guice.actors.OutgoingMessage

import com.midokura.midolman.FlowController.{WildcardFlowRemoved,
                                             WildcardFlowAdded}
import layer3.Route
import layer3.Route.NextHop
import topology.LocalPortActive
import com.midokura.packets._
import com.midokura.midonet.cluster.data.{Bridge => ClusterBridge,
                                          Router => ClusterRouter}
import com.midokura.midonet.cluster.data.host.Host
import topology.VirtualToPhysicalMapper.HostRequest
import util.SimulationHelper
import com.midokura.midonet.cluster.data.ports.MaterializedBridgePort

trait VMsBehindRouterFixture extends MidolmanTestCase with SimulationHelper with
        VirtualConfigurationBuilders {
    private final val log =
        LoggerFactory.getLogger(classOf[VMsBehindRouterFixture])

    val routerIp = IntIPv4.fromString("10.0.0.254", 24)
    val routerMac = MAC.fromString("22:aa:aa:ff:ff:ff")

    val vmPortNames = IndexedSeq("port0", "port1", "port2", "port3", "port4")
    var vmPorts: IndexedSeq[MaterializedBridgePort] = null
    var vmPortNumbers: IndexedSeq[Int] = null

    val vmMacs = IndexedSeq(MAC.fromString("02:aa:bb:cc:dd:d1"),
        MAC.fromString("02:aa:bb:cc:dd:d2"),
        MAC.fromString("02:aa:bb:cc:dd:d3"),
        MAC.fromString("02:aa:bb:cc:dd:d4"),
        MAC.fromString("02:aa:bb:cc:dd:d5"))
    val vmIps = IndexedSeq(IntIPv4.fromString("10.0.0.1"),
        IntIPv4.fromString("10.0.0.2"),
        IntIPv4.fromString("10.0.0.3"),
        IntIPv4.fromString("10.0.0.4"),
        IntIPv4.fromString("10.0.0.5"))
    val vmNetworkIp = IntIPv4.fromString("10.0.0.0", 24)

    var bridge: ClusterBridge = null
    var router: ClusterRouter = null
    var host: Host = null

    var flowEventsProbe: TestProbe = null
    var portEventsProbe: TestProbe = null
    var packetsEventsProbe: TestProbe = null

    override def beforeTest() {
        flowEventsProbe = newProbe()
        portEventsProbe = newProbe()
        packetsEventsProbe = newProbe()
        actors().eventStream.subscribe(flowEventsProbe.ref, classOf[WildcardFlowAdded])
        actors().eventStream.subscribe(flowEventsProbe.ref, classOf[WildcardFlowRemoved])
        actors().eventStream.subscribe(portEventsProbe.ref, classOf[LocalPortActive])
        actors().eventStream.subscribe(packetsEventsProbe.ref, classOf[PacketsExecute])

        host = newHost("myself", hostId())
        host should not be null
        router = newRouter("router")
        router should not be null

        initializeDatapath() should not be (null)
        requestOfType[HostRequest](vtpProbe())
        requestOfType[OutgoingMessage](vtpProbe())

        val rtrPort = newInteriorRouterPort(router, routerMac,
            routerIp.toUnicastString, routerIp.toNetworkAddress.toUnicastString,
            routerIp.getMaskLength)
        rtrPort should not be null

        newRoute(router, "0.0.0.0", 0,
            routerIp.toNetworkAddress.toUnicastString, routerIp.getMaskLength,
            NextHop.PORT, rtrPort.getId,
            new IntIPv4(Route.NO_GATEWAY).toUnicastString, 10)

        bridge = newBridge("bridge")
        bridge should not be null

        val brPort = newInteriorBridgePort(bridge)
        brPort should not be null
        clusterDataClient().portsLink(rtrPort.getId, brPort.getId)

        vmPorts = vmPortNames map { _ => newExteriorBridgePort(bridge) }
        vmPorts zip vmPortNames foreach {
            case (port, name) =>
                log.debug("Materializing port {}", name)
                materializePort(port, host, name)
                requestOfType[LocalPortActive](portEventsProbe)
        }
        vmPortNumbers = vmPorts map { port =>
            dpController().underlyingActor.vifToLocalPortNumber(port.getId) match {
                case Some(portNo : Short) => portNo
                case None =>
                    fail("Unable to find data port number for " + port.getInterfaceName)
                    0
            }
        }

        flowProbe().expectMsgType[DatapathController.DatapathReady].datapath should not be (null)
        drainProbes()
    }

    def expectPacketOut(port: Int, numPorts: Seq[Int] = List(1)): Ethernet = {
        val pktOut = requestOfType[PacketsExecute](packetsEventsProbe).packet
        log.debug("Packet execute: {}", pktOut)

        val outPorts = getOutPacketPorts(pktOut)
        numPorts should contain (outPorts.size)
        outPorts should contain (port.toShort)

        Ethernet.deserialize(pktOut.getData)
    }

    def expectPacketOutRouterToVm(port: Int): Ethernet =
        expectPacketOut(port, List(1, vmPortNames.size))

    def expectPacketOutVmToVm(port: Int): Ethernet =
        expectPacketOut(port, List(1, vmPortNames.size - 1))

    def arpVmToRouterAndCheckReply(portName: String, srcMac: MAC, srcIp: IntIPv4,
                               dstIp: IntIPv4, expectedMac: MAC) {
        injectArpRequest(portName, srcIp.getAddress, srcMac, dstIp.getAddress)
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
        eth.setPayload(new IPv4().setSourceAddress(vmIps(portIndexA).addressAsInt).
            setDestinationAddress(vmIps(portIndexB).addressAsInt).
            setProtocol(ICMP.PROTOCOL_NUMBER).
            setPayload(echo))
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
        eth.setPayload(new IPv4().setSourceAddress(vmIps(portIndexA).addressAsInt).
            setDestinationAddress(vmIps(portIndexB).addressAsInt).
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
        eth.setPayload(new IPv4().setSourceAddress(vmIps(portIndexA).addressAsInt).
            setDestinationAddress(vmIps(portIndexB).addressAsInt).
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
        outpkt should be === eth
        outpkt.getPayload should be === eth.getPayload
        outpkt.getPayload.getPayload should be === eth.getPayload.getPayload
        log.info("Packet received on {} forwarded to {}",
                 vmPortNames(portIndexA), vmPortNames(portIndexB))
    }

    def expectPacketDropped(portIndexA: Int, portIndexB: Int,
                            packetGenerator: (Int, Int) => Ethernet) {
        triggerPacketIn(vmPortNames(portIndexA),
                        packetGenerator(portIndexA, portIndexB))
        packetsEventsProbe.expectNoMsg()
    }
}
