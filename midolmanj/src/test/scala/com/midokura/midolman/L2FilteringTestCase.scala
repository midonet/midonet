/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman

import guice.actors.OutgoingMessage
import guice.actors.OutgoingMessage
import layer3.Route
import layer3.Route.NextHop
import rules.{RuleResult, Condition}
import scala.collection.mutable
import scala.compat.Platform
import java.util.UUID

import akka.dispatch.{Promise, Await}
import akka.testkit.TestProbe
import akka.util.duration._
import akka.util.Timeout

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.slf4j.LoggerFactory
import com.midokura.midolman.FlowController.{WildcardFlowRemoved, WildcardFlowAdded}
import topology.LocalPortActive
import topology.LocalPortActive
import topology.VirtualToPhysicalMapper.HostRequest
import com.midokura.packets._
import com.midokura.midonet.cluster.data.{Bridge => ClusterBridge, Port}
import com.midokura.sdn.dp.{Port => DpPort}
import topology.VirtualToPhysicalMapper.HostRequest
import topology.VirtualTopologyActor.BridgeRequest
import util.SimulationHelper
import com.midokura.midolman.simulation.{Bridge => SimBridge}
import com.midokura.midolman.DatapathController.PacketIn
import com.midokura.netlink.Callback
import com.midokura.netlink.exceptions.NetlinkException
import com.midokura.midonet.cluster.data.ports.MaterializedBridgePort
import com.midokura.sdn.dp.flows.{FlowAction, FlowActionOutput}
import scala.Some

@RunWith(classOf[JUnitRunner])
class L2FilteringTestCase extends MidolmanTestCase with
        VirtualConfigurationBuilders with SimulationHelper {
    private final val log = LoggerFactory.getLogger(classOf[L2FilteringTestCase])

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

    var bridge: ClusterBridge = null

    private var flowEventsProbe: TestProbe = null
    private var portEventsProbe: TestProbe = null
    private var packetsEventsProbe: TestProbe = null

    override def beforeTest() {
        flowEventsProbe = newProbe()
        portEventsProbe = newProbe()
        packetsEventsProbe = newProbe()
        actors().eventStream.subscribe(flowEventsProbe.ref, classOf[WildcardFlowAdded])
        actors().eventStream.subscribe(flowEventsProbe.ref, classOf[WildcardFlowRemoved])
        actors().eventStream.subscribe(portEventsProbe.ref, classOf[LocalPortActive])
        actors().eventStream.subscribe(packetsEventsProbe.ref, classOf[PacketsExecute])

        val host = newHost("myself", hostId())
        host should not be null
        // XXX need tenant name?
        val clusterRouter = newRouter("router")
        clusterRouter should not be null

        initializeDatapath() should not be (null)
        requestOfType[HostRequest](vtpProbe())
        requestOfType[OutgoingMessage](vtpProbe())

        val rtrPort = newInteriorRouterPort(clusterRouter, routerMac,
            routerIp.toUnicastString, routerIp.toNetworkAddress.toUnicastString,
            routerIp.getMaskLength)
        rtrPort should not be null

        newRoute(clusterRouter, "0.0.0.0", 0,
            routerIp.toNetworkAddress.toUnicastString, routerIp.getMaskLength,
            NextHop.PORT, rtrPort.getId,
            new IntIPv4(Route.NO_GATEWAY).toUnicastString, 10)

        // XXX need tenant name?
        bridge = newBridge("bridge")
        bridge should not be null

        val brPort = newInteriorBridgePort(bridge)
        brPort should not be null
        clusterDataClient().portsLink(rtrPort.getId, brPort.getId)

        vmPorts = vmPortNames map { _ => newExteriorBridgePort(bridge) }
        vmPorts zip vmPortNames foreach {
            case (port, name) =>
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

    private def expectPacketOut(port: Int): Ethernet = {
        val pktOut = requestOfType[PacketsExecute](packetsEventsProbe).packet
        pktOut should not be null
        pktOut.getData should not be null
        log.debug("Packet execute: {}", pktOut)

        pktOut.getActions.size should be === 1
        val action = pktOut.getActions.get(0)
        action.getKey should be === FlowAction.FlowActionAttr.OUTPUT
        action.getValue.getClass() should be === classOf[FlowActionOutput]
        action.getValue.asInstanceOf[FlowActionOutput].getPortNumber should be === port

        Ethernet.deserialize(pktOut.getData)
    }

    private def arpAndCheckReply(portName: String, srcMac: MAC, srcIp: IntIPv4,
                                 dstIp: IntIPv4, expectedMac: MAC) {

        injectArpRequest(portName, srcIp.getAddress, srcMac, dstIp.getAddress)
        val pkt = expectPacketOut(vmPortNameToPortNumber(portName))
        log.debug("Packet out: {}", pkt)
        // TODO(guillermo) check the arp reply packet
    }

    private def vmPortNameToPortNumber(portName: String): Int = {
        for ((name, port) <- vmPortNames zip vmPortNumbers) {
            if (name == portName)
                return port
        }
        fail("Unknown port: " + portName)
        0
    }

    private def icmpBetweenPorts(portIndexA: Int, portIndexB: Int): Ethernet = {
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

    private def lldpBetweenPorts(portIndexA: Int, portIndexB: Int): Ethernet = {
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

    private def expectPacketAllowed(portIndexA: Int, portIndexB: Int,
                                    packetGenerator: (Int, Int) => Ethernet) {
        val eth = packetGenerator(portIndexA, portIndexB)
        triggerPacketIn(vmPortNames(portIndexA), eth)
        val outpkt = expectPacketOut(vmPortNameToPortNumber(vmPortNames(portIndexB)))
        outpkt should be === eth
        outpkt.getPayload should be === eth.getPayload
        outpkt.getPayload.getPayload should be === eth.getPayload.getPayload
        log.info("Packet received on {} forwarded to {}",
                 vmPortNames(portIndexA), vmPortNames(portIndexB))
    }

    private def expectPacketDropped(portIndexA: Int, portIndexB: Int,
                                    packetGenerator: (Int, Int) => Ethernet) {
        triggerPacketIn(vmPortNames(portIndexA),
                        packetGenerator(portIndexA, portIndexB))
        packetsEventsProbe.expectNoMsg()
    }

    def test() {
        flowController().underlyingActor.flowToTags.size should be === vmPorts.size

        (vmPortNames, vmMacs, vmIps).zipped foreach {
            (name, mac, ip) => arpAndCheckReply(name, mac, ip, routerIp, routerMac)
        }

        for (pair <- (0 to (vmPorts.size-1)).toList.combinations(2)) {
            expectPacketAllowed(pair.head, pair.last, icmpBetweenPorts)
            requestOfType[WildcardFlowAdded](flowEventsProbe)
            expectPacketAllowed(pair.last, pair.head, icmpBetweenPorts)
            requestOfType[WildcardFlowAdded](flowEventsProbe)
        }
        drainProbes()

        log.info("creating chain")
        val brInChain = newInboundChainOnBridge("brInFilter", bridge)
        log.info("adding first rule: drop by ip from port0 to port3")
        val cond0 = new Condition()
        cond0.nwSrcIp = vmIps(0)
        cond0.nwDstIp = vmIps(3)
        val rule0 = newLiteralRuleOnChain(brInChain, 1, cond0,
                                          RuleResult.Action.DROP)
        clusterDataClient().bridgesUpdate(bridge)

        fishForRequestOfType[FlowController.InvalidateFlowsByTag](flowProbe())
        fishForRequestOfType[FlowController.InvalidateFlowsByTag](flowProbe())
        for (pair <- (0 to (vmPorts.size-1)).toList.combinations(2)) {
            fishForRequestOfType[WildcardFlowRemoved](flowEventsProbe)
            fishForRequestOfType[WildcardFlowRemoved](flowEventsProbe)
        }
        flowController().underlyingActor.flowToTags.size should be === vmPorts.size
        drainProbe(packetsEventsProbe)
        drainProbe(flowEventsProbe)

        log.info("sending a packet that should be dropped by rule 1")
        expectPacketDropped(0, 3, icmpBetweenPorts)
        fishForRequestOfType[WildcardFlowAdded](flowEventsProbe)
        log.info("sending a packet that should be allowed by rule 1")
        expectPacketAllowed(4, 1, icmpBetweenPorts)
        fishForRequestOfType[WildcardFlowAdded](flowEventsProbe)
        log.info("sending a packet that should be allowed by rule 1")
        expectPacketAllowed(0, 3, lldpBetweenPorts)
        fishForRequestOfType[WildcardFlowAdded](flowEventsProbe)
        fishForRequestOfType[BridgeRequest](vtaProbe())
        fishForReplyOfType[SimBridge](vtaProbe())

        log.info("adding a second rule: drop by mac from port4 to port1")
        val cond1 = new Condition()
        cond1.dlSrc = vmMacs(4)
        cond1.dlDst = vmMacs(1)
        val rule1 = newLiteralRuleOnChain(brInChain, 2, cond1,
                                          RuleResult.Action.DROP)

        fishForRequestOfType[FlowController.InvalidateFlowsByTag](flowProbe())
        fishForRequestOfType[WildcardFlowRemoved](flowEventsProbe)
        fishForRequestOfType[WildcardFlowRemoved](flowEventsProbe)
        fishForRequestOfType[WildcardFlowRemoved](flowEventsProbe)
        flowController().underlyingActor.flowToTags.size should be === vmPorts.size

        log.info("sending two packets that should be dropped by rule 2")
        expectPacketDropped(4, 1, icmpBetweenPorts)
        fishForRequestOfType[WildcardFlowAdded](flowEventsProbe)
        expectPacketDropped(4, 1, lldpBetweenPorts)
        fishForRequestOfType[WildcardFlowAdded](flowEventsProbe)
        log.info("sending a packet that should be allowed by rules 1,2")
        expectPacketAllowed(4, 3, icmpBetweenPorts)
        fishForRequestOfType[WildcardFlowAdded](flowEventsProbe)
        log.info("sending an lldp packet that should be allowed by rules 1,2")
        expectPacketAllowed(4, 3, lldpBetweenPorts)
        fishForRequestOfType[WildcardFlowAdded](flowEventsProbe)

        log.info("adding a third rule: drop if ether-type == LLDP")
        val cond2 = new Condition()
        cond2.dlType = LLDP.ETHERTYPE
        val rule2 = newLiteralRuleOnChain(brInChain, 3, cond2,
                                          RuleResult.Action.DROP)
        fishForRequestOfType[FlowController.InvalidateFlowsByTag](flowProbe())
        fishForRequestOfType[WildcardFlowRemoved](flowEventsProbe)
        fishForRequestOfType[WildcardFlowRemoved](flowEventsProbe)
        fishForRequestOfType[WildcardFlowRemoved](flowEventsProbe)
        fishForRequestOfType[WildcardFlowRemoved](flowEventsProbe)
        flowController().underlyingActor.flowToTags.size should be === vmPorts.size

        log.info("sending an lldp packet that should be dropped by rule 3")
        expectPacketDropped(4, 3, lldpBetweenPorts)
        expectPacketAllowed(4, 3, icmpBetweenPorts)

        log.info("deleting rule 3")
        clusterDataClient().rulesDelete(rule2.getId)
        fishForRequestOfType[FlowController.InvalidateFlowsByTag](flowProbe())
        fishForRequestOfType[WildcardFlowRemoved](flowEventsProbe)
        flowController().underlyingActor.flowToTags.size should be === vmPorts.size

        log.info("sending an lldp packet that should be allowed by the " +
                 "removal of rule 3")
        expectPacketAllowed(4, 3, lldpBetweenPorts)
    }
}
