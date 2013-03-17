/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman

import akka.util.Duration

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.slf4j.LoggerFactory

import org.midonet.midolman.FlowController.{AddWildcardFlow, WildcardFlowAdded,
                                            WildcardFlowRemoved}
import org.midonet.midolman.util.SimulationHelper
import org.midonet.odp.flows.IPFragmentType
import org.midonet.packets._


@RunWith(classOf[JUnitRunner])
class IPFragmentationTestCase extends MidolmanTestCase with VMsBehindRouterFixture
        with SimulationHelper {
    private final val log = LoggerFactory.getLogger(classOf[IPFragmentationTestCase])

    val sendingVm = 1
    val receivingVm = 2

    override def beforeTest() {
        super.beforeTest()

        flowController().underlyingActor.flowToTags.size should be === vmPorts.size

        log.info("populating the mac learning table with an arp request from each port")
        (vmPortNames, vmMacs, vmIps).zipped foreach {
            (name, mac, ip) => arpVmToRouterAndCheckReply(name, mac, ip, routerIp, routerMac)
        }
        drainProbes()
    }

    def makePacket(fragmentType: IPFragmentType): Ethernet = {
        val data = new Data().setData("Fragmented packet payload".getBytes)

        val flags = if (fragmentType == IPFragmentType.None) 0 else IPv4.IP_FLAGS_MF
        val offset = if (fragmentType == IPFragmentType.Later) 0x4321 else 0

        val ip = new IPv4().
                setSourceAddress(vmIps(sendingVm).addressAsInt).
                setDestinationAddress(vmIps(receivingVm).addressAsInt).
                setProtocol(200.toByte).
                setFlags(flags.toByte).
                setFragmentOffset(offset.toShort).
                setPayload(data)

        val eth: Ethernet = new Ethernet().
            setSourceMACAddress(vmMacs(sendingVm)).
            setDestinationMACAddress(vmMacs(receivingVm)).
            setEtherType(IPv4.ETHERTYPE)
        eth.setPayload(ip)

        eth
    }

    private def firstFragmentBatch() {
        val packet = makePacket(IPFragmentType.First)
        triggerPacketIn(vmPortNames(sendingVm), packet)

        val pktOut = expectPacketOut(vmPortNumbers(sendingVm))
        pktOut.getEtherType should be(IPv4.ETHERTYPE)
        pktOut.getPayload.getClass should be === classOf[IPv4]
        pktOut.getDestinationMACAddress should be === vmMacs(sendingVm)
        pktOut.getSourceMACAddress should be === vmMacs(receivingVm)

        val ip = pktOut.getPayload.asInstanceOf[IPv4]
        ip.getSourceAddress should be(vmIps(receivingVm).addressAsInt)
        ip.getDestinationAddress should be(vmIps(sendingVm).addressAsInt)
        ip.getProtocol should be(ICMP.PROTOCOL_NUMBER)
        ip.getPayload.getClass should be === classOf[ICMP]

        val icmp = ip.getPayload.asInstanceOf[ICMP]
        icmp.getType should be(ICMP.TYPE_UNREACH)
        icmp.getCode should be(ICMP.UNREACH_CODE.UNREACH_FRAG_NEEDED.toChar)

        triggerPacketIn(vmPortNames(sendingVm), packet)
        packetsEventsProbe.expectNoMsg()
    }

    def testFirstFragment() {
        firstFragmentBatch()
        fishForRequestOfType[WildcardFlowRemoved](wflowRemovedProbe,
                                                  Duration.parse("10 seconds"))
        firstFragmentBatch()
    }

    def testLaterFragment() {
        drainProbe(wflowAddedProbe)
        drainProbe(wflowRemovedProbe)
        val packet = makePacket(IPFragmentType.Later)
        triggerPacketIn(vmPortNames(sendingVm), packet)
        fishForRequestOfType[AddWildcardFlow](flowProbe())
        fishForRequestOfType[WildcardFlowAdded](wflowAddedProbe)
        packetsEventsProbe.expectNoMsg()
        packet.setSourceMACAddress(MAC.fromString("02:02:03:03:04:04"))
        packet.setDestinationMACAddress(MAC.fromString("02:02:06:06:08:08"))
        triggerPacketIn(vmPortNames(sendingVm), packet)
        packetsEventsProbe.expectNoMsg()
        wflowAddedProbe.expectNoMsg()
        wflowRemovedProbe.expectNoMsg()
    }

    def testNoFragmentAfterFragment() {
        var packet = makePacket(IPFragmentType.First)
        triggerPacketIn(vmPortNames(sendingVm), packet)
        expectPacketOut(vmPortNumbers(sendingVm))

        packet = makePacket(IPFragmentType.Later)
        triggerPacketIn(vmPortNames(sendingVm), packet)

        packet = makePacket(IPFragmentType.None)
        triggerPacketIn(vmPortNames(sendingVm), packet)
        val pktOut = expectPacketOut(vmPortNumbers(receivingVm))
        pktOut should be === packet
    }
}
