/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman

import akka.util.{Timeout, Duration}
import akka.pattern.ask

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.slf4j.LoggerFactory

import akka.util.duration._
import akka.dispatch.Await

import org.midonet.midolman.FlowController.{InvalidateFlowsByTag, AddWildcardFlow, WildcardFlowAdded, WildcardFlowRemoved}
import org.midonet.midolman.topology.VirtualTopologyActor.ChainRequest
import org.midonet.midolman.simulation.Chain
import org.midonet.midolman.util.SimulationHelper
import org.midonet.odp.flows.IPFragmentType
import org.midonet.packets._
import rules.{RuleResult, Condition}
import topology.LocalPortActive
import org.midonet.cluster.data.Rule
import java.util.concurrent.TimeUnit


@RunWith(classOf[JUnitRunner])
class IPFragmentationTestCase extends MidolmanTestCase with VMsBehindRouterFixture
        with SimulationHelper {
    private final val log = LoggerFactory.getLogger(classOf[IPFragmentationTestCase])

    val sendingVm = 1
    val receivingVm = 2

    override def beforeTest() {
        super.beforeTest()

        log.info("populating the mac learning table with an arp request from each port")
        (vmPortNames, vmMacs, vmIps).zipped foreach {
            (name, mac, ip) => arpVmToRouterAndCheckReply(
                name, mac, ip, routerIp.getAddress, routerMac)
        }

        drainProbes()
        drainProbe(wflowAddedProbe)
        drainProbe(wflowRemovedProbe)
    }

    /**
     * Sets up a chain on the bridge that looks at L4 fields (dst port 80)
     */
    private def setupL4TouchingChain() {
        val chain = newInboundChainOnBridge("brInFilter", bridge)
        println("Chainid: " + chain.getId)
        var r: Rule[_,_] = null
        val tcpCond = new Condition()
        tcpCond.nwProto = Byte.box(TCP.PROTOCOL_NUMBER)
        tcpCond.tpDst = new org.midonet.util.Range(Integer.valueOf(80))
        r = newLiteralRuleOnChain(chain, 1, tcpCond, RuleResult.Action.ACCEPT)

        // Wait until the rule change is picked up
        Await.result(vtaProbe().testActor.ask(ChainRequest(chain.getId, update = false))(new Timeout(3, TimeUnit.SECONDS)), 3 second)
        fishForRequestOfType[InvalidateFlowsByTag](flowProbe())
    }


    /**
     * Makes a packet from sendingVm to receivingVm that contains a TCP from
     * src port 80 to dst port 81. The IP packet will have the fragment type
     * provided, and an offset if fragment.
     *
     * @param fragmentType
     * @return
     */
    def makePacket(fragmentType: IPFragmentType): Ethernet = {
        val data = new Data().setData("Fragmented packet payload".getBytes)

        val flags = if (fragmentType == IPFragmentType.None) 0 else IPv4.IP_FLAGS_MF
        val offset = if (fragmentType == IPFragmentType.Later) 0x4321 else 0

        val tcp = new TCP()
        tcp.setDestinationPort(80)
        tcp.setSourcePort(81)

        val ip = new IPv4().
                setSourceAddress(vmIps(sendingVm).addr).
                setDestinationAddress(vmIps(receivingVm).addr).
                setProtocol(TCP.PROTOCOL_NUMBER).
                setFlags(flags.toByte).
                setFragmentOffset(offset.toShort).
                setPayload(tcp)

        val eth: Ethernet = new Ethernet().
            setSourceMACAddress(vmMacs(sendingVm)).
            setDestinationMACAddress(vmMacs(receivingVm)).
            setEtherType(IPv4.ETHERTYPE)
        eth.setPayload(ip)

        eth
    }

    /**
     * Sends a first fragment from sending VM, checks that an ICMP frag. needed
     * is received on the same VM, then sends the first fragment again and
     * verifies that no packet is received anywhere.
     */
    private def firstFragmentBatch() {
        val packet = makePacket(IPFragmentType.First)
        triggerPacketIn(vmPortNames(sendingVm), packet)

        val pktOut = expectPacketOut(vmPortNumbers(sendingVm))
        pktOut.getEtherType should be(IPv4.ETHERTYPE)
        pktOut.getPayload.getClass should be === classOf[IPv4]
        pktOut.getDestinationMACAddress should be === vmMacs(sendingVm)
        pktOut.getSourceMACAddress should be === vmMacs(receivingVm)

        val ip = pktOut.getPayload.asInstanceOf[IPv4]
        ip.getSourceAddress should be(vmIps(receivingVm).addr)
        ip.getDestinationAddress should be(vmIps(sendingVm).addr)
        ip.getProtocol should be(ICMP.PROTOCOL_NUMBER)
        ip.getPayload.getClass should be === classOf[ICMP]

        val icmp = ip.getPayload.asInstanceOf[ICMP]
        icmp.getType should be(ICMP.TYPE_UNREACH)
        icmp.getCode should be(ICMP.UNREACH_CODE.UNREACH_FRAG_NEEDED.toChar)

        triggerPacketIn(vmPortNames(sendingVm), packet)
        packetsEventsProbe.expectNoMsg()
    }

    /**
     * Tests sending fragmented packets, where L4 fields are touched. This
     * should generate ICMP FRAG NEEDED frames, and DROP.
     */
    def testFirstFragmentTouchingL4Fields() {
        setupL4TouchingChain()
        firstFragmentBatch()
        fishForRequestOfType[WildcardFlowRemoved](wflowRemovedProbe,
            Duration.parse("10 seconds"))
        firstFragmentBatch()
    }

    /**
     * Sends a packet, since the bridge does not touch L4 fields it should pass
     */
    def testFirstFragmentNotTouchingL4Fields() {
        val first = makePacket(IPFragmentType.First)
        triggerPacketIn(vmPortNames(sendingVm), first)
        var pktOut = expectPacketOut(vmPortNumbers(receivingVm))
        pktOut should be === first
        fishForRequestOfType[AddWildcardFlow](flowProbe())
        fishForRequestOfType[WildcardFlowAdded](wflowAddedProbe)
        val later = makePacket(IPFragmentType.Later)
        triggerPacketIn(vmPortNames(sendingVm), later)
        pktOut = expectPacketOut(vmPortNumbers(receivingVm))
        pktOut should be === later
        fishForRequestOfType[AddWildcardFlow](flowProbe())
        fishForRequestOfType[WildcardFlowAdded](wflowAddedProbe)
    }

    /**
     * Sends a LATER packet, the bridge has a chain touching L4 fields so it
     * should drop it.
     */
    def testLaterFragmentTouchingL4Fields() {
        setupL4TouchingChain()
        val packet = makePacket(IPFragmentType.Later)
        triggerPacketIn(vmPortNames(sendingVm), packet)
        fishForRequestOfType[WildcardFlowAdded](wflowAddedProbe)
        packetsEventsProbe.expectNoMsg()
        packet.setSourceMACAddress(MAC.fromString("02:02:03:03:04:04"))
        packet.setDestinationMACAddress(MAC.fromString("02:02:06:06:08:08"))
        triggerPacketIn(vmPortNames(sendingVm), packet)
        packetsEventsProbe.expectNoMsg()
        fishForRequestOfType[WildcardFlowAdded](wflowAddedProbe)
        wflowRemovedProbe.expectNoMsg()
    }

    /**
     * Sends a Later packet, since the bridge is not touching L4 fields it
     * should let it pass.
     */
    def testLaterFragmentNotTouchingL4Fields() {
        val packet = makePacket(IPFragmentType.Later)
        triggerPacketIn(vmPortNames(sendingVm), packet)
        fishForRequestOfType[AddWildcardFlow](flowProbe())
        fishForRequestOfType[WildcardFlowAdded](wflowAddedProbe)
        val pktOut = expectPacketOut(vmPortNumbers(receivingVm))
        pktOut should be === packet
    }

    /**
     * Checks that ending two fragments, then a no-fragment pass through
     * correctly with a bridge that does not touch L4 fields.
     */
    def testNoFragmentAfterFragmentNotTouchingL4Fields() {
        var packet = makePacket(IPFragmentType.First)
        triggerPacketIn(vmPortNames(sendingVm), packet)
        var pktOut = expectPacketOut(vmPortNumbers(receivingVm))
        pktOut should be === packet
        fishForRequestOfType[AddWildcardFlow](flowProbe())
        fishForRequestOfType[WildcardFlowAdded](wflowAddedProbe)

        packet = makePacket(IPFragmentType.Later)
        triggerPacketIn(vmPortNames(sendingVm), packet)
        pktOut = expectPacketOut(vmPortNumbers(receivingVm))
        pktOut should be === packet
        fishForRequestOfType[AddWildcardFlow](flowProbe())
        fishForRequestOfType[WildcardFlowAdded](wflowAddedProbe)

        packet = makePacket(IPFragmentType.None)
        triggerPacketIn(vmPortNames(sendingVm), packet)
        pktOut = expectPacketOut(vmPortNumbers(receivingVm))
        pktOut should be === packet
    }

    /**
     * In a bridge that touches L4 fields, checks that a second fragment is
     * dropped after sending a first one that should get replied with a Frag.
     * Needed. A no-fragment should pass through.
     */
    def testNoFragmentAfterFragmentTouchingL4Fields() {
        setupL4TouchingChain()
        var packet = makePacket(IPFragmentType.First)
        triggerPacketIn(vmPortNames(sendingVm), packet)
        expectPacketOut(vmPortNumbers(sendingVm))

        requestOfType[WildcardFlowAdded](wflowAddedProbe)

        packet = makePacket(IPFragmentType.Later)
        triggerPacketIn(vmPortNames(sendingVm), packet)
        requestOfType[WildcardFlowAdded](wflowAddedProbe)

        packet = makePacket(IPFragmentType.None)
        triggerPacketIn(vmPortNames(sendingVm), packet)
        val pktOut = expectPacketOut(vmPortNumbers(receivingVm))
        pktOut should be === packet
        requestOfType[WildcardFlowAdded](wflowAddedProbe)

        clusterDataClient().portsDelete(vmPorts(sendingVm).getId)
        requestOfType[WildcardFlowRemoved](wflowRemovedProbe)
        requestOfType[WildcardFlowRemoved](wflowRemovedProbe)
        requestOfType[WildcardFlowRemoved](wflowRemovedProbe)
    }

}

