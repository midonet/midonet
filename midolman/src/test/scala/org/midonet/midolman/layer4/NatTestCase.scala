/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.layer4

import scala.collection.JavaConversions._

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID
import java.{util => ju}

import org.midonet.midolman.DeduplicationActor.DiscardPacket
import org.midonet.midolman.FlowController.{InvalidateFlowsByTag,
                                            WildcardFlowRemoved}
import org.midonet.midolman.layer3.Route
import org.midonet.midolman.layer3.Route.NextHop
import org.midonet.midolman.rules.{NatTarget, RuleResult, Condition}
import org.midonet.midolman.topology.{FlowTagger, LocalPortActive}
import org.midonet.cluster.data.Chain
import org.midonet.cluster.data.ports.RouterPort
import org.midonet.packets._
import org.midonet.packets.util.AddressConversions._
import org.midonet.midolman.{VMsBehindRouterFixture, MidolmanTestCase}
import org.midonet.odp.flows.IPFragmentType

@RunWith(classOf[JUnitRunner])
class NatTestCase extends MidolmanTestCase with VMsBehindRouterFixture {
    private final val log = LoggerFactory.getLogger(classOf[NatTestCase])

    private val uplinkGatewayAddr = IPv4Addr("180.0.1.1")
    private val uplinkGatewayMac: MAC = "02:0b:09:07:05:03"
    private val uplinkNwAddr = new IPv4Subnet("180.0.1.0", 24)
    private val uplinkPortAddr = IPv4Addr("180.0.1.2")
    private val uplinkPortMac: MAC = "02:0a:08:06:04:02"
    private var uplinkPort: RouterPort = null
    private var uplinkPortNum: Short = 0

    private val dnatAddress = IPv4Addr("180.0.1.100")
    private val snatAddressStart = IPv4Addr("180.0.1.200")
    private val snatAddressEnd = IPv4Addr("180.0.1.205")

    private var leaseManager: NatLeaseManager = null
    private var mappings = Set[String]()

    private var rtrOutChain: Chain = null
    private var rtrInChain: Chain = null

    private class Mapping(val key: String, val flowCount: AtomicInteger,
        val fwdInPort: String, val fwdOutPort: String,
        val fwdInPacket: Ethernet, val fwdOutPacket: Ethernet) {

        def revInPort: String = fwdOutPort
        def revOutPort: String = fwdInPort

        def fwdInFromMac: MAC = fwdInPacket.getSourceMACAddress
        def fwdInToMac: MAC = fwdInPacket.getDestinationMACAddress

        def revOutFromMac: MAC = fwdInToMac
        def revOutToMac: MAC = fwdInFromMac

        def fwdOutFromMac: MAC = fwdOutPacket.getSourceMACAddress
        def fwdOutToMac: MAC = fwdOutPacket.getDestinationMACAddress

        def revInFromMac: MAC = fwdOutToMac
        def revInToMac: MAC = fwdOutFromMac

        def fwdInFromIp: IPv4Addr = new IPv4Addr(
                fwdInPacket.getPayload.asInstanceOf[IPv4].getSourceAddress)
        def fwdInToIp: IPv4Addr = new IPv4Addr(
                fwdInPacket.getPayload.asInstanceOf[IPv4].getDestinationAddress)

        def revOutFromIp: IPv4Addr = fwdInToIp
        def revOutToIp: IPv4Addr = fwdInFromIp

        def fwdOutFromIp: IPv4Addr = new IPv4Addr(
                fwdOutPacket.getPayload.asInstanceOf[IPv4].getSourceAddress)
        def fwdOutToIp: IPv4Addr = new IPv4Addr(
                fwdOutPacket.getPayload.asInstanceOf[IPv4].getDestinationAddress)

        def revInFromIp: IPv4Addr = fwdOutToIp
        def revInToIp: IPv4Addr = fwdOutFromIp

        def fwdInFromPort: Int =
            fwdInPacket.getPayload.asInstanceOf[IPv4].
                        getPayload.asInstanceOf[TCP].getSourcePort
        def fwdInToPort: Int =
            fwdInPacket.getPayload.asInstanceOf[IPv4].
                        getPayload.asInstanceOf[TCP].getDestinationPort

        def revOutFromPort: Int = fwdInToPort
        def revOutToPort: Int = fwdInFromPort

        def fwdOutFromPort: Int =
            fwdOutPacket.getPayload.asInstanceOf[IPv4].
                         getPayload.asInstanceOf[TCP].getSourcePort
        def fwdOutToPort: Int =
            fwdOutPacket.getPayload.asInstanceOf[IPv4].
                         getPayload.asInstanceOf[TCP].getDestinationPort

        def revInFromPort: Int = fwdOutToPort
        def revInToPort: Int = fwdOutFromPort

        def matchForwardOutPacket(eth: Ethernet) {
            val ip = eth.getPayload.asInstanceOf[IPv4]
            val tcp = ip.getPayload.asInstanceOf[TCP]
            eth.getSourceMACAddress should be (fwdOutFromMac)
            eth.getDestinationMACAddress should be (fwdOutToMac)
            IPv4Addr(ip.getSourceAddress) should be (fwdOutFromIp)
            IPv4Addr(ip.getDestinationAddress) should be (fwdOutToIp)
            tcp.getSourcePort should be (fwdOutFromPort)
            tcp.getDestinationPort should be (fwdOutToPort)
        }

        def matchReturnOutPacket(eth: Ethernet) {
            val ip = eth.getPayload.asInstanceOf[IPv4]
            val tcp = ip.getPayload.asInstanceOf[TCP]
            eth.getSourceMACAddress should be (revOutFromMac)
            eth.getDestinationMACAddress should be (revOutToMac)
            IPv4Addr(ip.getSourceAddress) should be (revOutFromIp)
            IPv4Addr(ip.getDestinationAddress) should be (revOutToIp)
            tcp.getSourcePort should be (revOutFromPort)
            tcp.getDestinationPort should be (revOutToPort)
        }
    }

    override def beforeTest() {
        super.beforeTest()

        uplinkPort = newRouterPort(
            router, uplinkPortMac, uplinkPortAddr.toString,
            uplinkNwAddr.getAddress.toString, uplinkNwAddr.getPrefixLen)
        uplinkPort should not be null
        materializePort(uplinkPort, host, "uplinkPort")
        requestOfType[LocalPortActive](portsProbe)

        uplinkPortNum =
            vifToLocalPortNumber(uplinkPort.getId).getOrElse(0.toShort)
        uplinkPortNum should not be (0)

        var route = newRoute(router, "0.0.0.0", 0, "0.0.0.0", 0,
            NextHop.PORT, uplinkPort.getId, uplinkGatewayAddr.toString, 1)
        route should not be null

        route = newRoute(router, "0.0.0.0", 0,
            uplinkNwAddr.getAddress.toString, uplinkNwAddr.getPrefixLen,
            NextHop.PORT, uplinkPort.getId, intToIp(Route.NO_GATEWAY), 10)
        route should not be null

        // create NAT rules
        log.info("creating chains")
        rtrOutChain = newOutboundChainOnRouter("rtrOutChain", router)
        rtrInChain = newInboundChainOnRouter("rtrInChain", router)

        // 0.- Reverse NAT rules
        val revDnatRule = newReverseNatRuleOnChain(rtrOutChain, 1,
            new Condition(), RuleResult.Action.CONTINUE, isDnat = true)
        revDnatRule should not be null

        val revSnatRule = newReverseNatRuleOnChain(rtrInChain, 1,
            new Condition(), RuleResult.Action.CONTINUE, isDnat = false)
        revSnatRule should not be null

        // 1.- DNAT -> from !vm-network to dnatAddress --> dnat(vm-network)
        log.info("adding DNAT rule")
        val dnatCond = new Condition()
        dnatCond.nwSrcIp = new IPv4Subnet(
                           IPv4Addr.fromString(vmNetworkIp.toUnicastString),
                           vmNetworkIp.getPrefixLen)
        dnatCond.nwSrcInv = true
        dnatCond.nwDstIp = new IPv4Subnet(dnatAddress, 32)
        /*
         * (Galo): removed this since it prevents matching on ICMP packets for
         * testDnatPing
         * dnatCond.tpDstStart = 80.toInt
         * dnatCond.tpDstEnd = 80.toInt
         */
        val dnatTarget =  new NatTarget(vmIps.head, vmIps.last, 80, 80)
        val dnatRule = newForwardNatRuleOnChain(rtrInChain, 2, dnatCond,
                RuleResult.Action.CONTINUE, Set(dnatTarget), isDnat = true)
        dnatRule should not be null

        // 2.- SNAT -> from vm-network to !vm-network  dstPort=22 --> snat(uplinkPortAddr)
        log.info("adding SNAT rule")
        val snatCond = new Condition()
        snatCond.nwSrcIp = new IPv4Subnet(
                           IPv4Addr.fromString(vmNetworkIp.toUnicastString), 24)
        snatCond.nwDstIp = new IPv4Subnet(
                           IPv4Addr.fromString(vmNetworkIp.toUnicastString), 24)
        snatCond.nwDstInv = true
        // FIXME(guillermo) why does a port range of 0:0 allow 0 to be picked as source port??
        val snatTarget = new NatTarget(snatAddressStart, snatAddressEnd, 10001.toShort, 65535.toShort)
        val snatRule = newForwardNatRuleOnChain(rtrOutChain, 2, snatCond,
                RuleResult.Action.CONTINUE, Set(snatTarget), isDnat = false)
        snatRule should not be null

        clusterDataClient().routersUpdate(router)

        // get hold of the NatLeaseManager object
        leaseManager = natMappingFactory().
            get(router.getId).asInstanceOf[NatLeaseManager]
        leaseManager should not be null

        // feed the router arp cache with the uplink gateway's mac address
        feedArpCache("uplinkPort", uplinkGatewayAddr.addr, uplinkGatewayMac,
            uplinkPortAddr.addr, uplinkPortMac)
        fishForRequestOfType[DiscardPacket](discardPacketProbe)
        drainProbes()

        // feed the router's arp cache with each of the vm's addresses
        (vmPortNames, vmMacs, vmIps).zipped foreach {
            (name, mac, ip) =>
                feedArpCache(name, ip.addr, mac,
                    routerIp.getAddress.addr, routerMac)
                fishForRequestOfType[DiscardPacket](discardPacketProbe)
        }
        drainProbes()
    }

    def updateAndDiffMappings(): Set[String] = {
        val oldMappings = mappings
        mappings = leaseManager.fwdKeys.keySet.toSet
        mappings -- oldMappings
    }

    def expectTranslatedPacket(): Ethernet = {
        val pktOut = requestOfType[PacketsExecute](packetsEventsProbe).packet
        val eth = applyOutPacketActions(pktOut)
        log.info("packet translated: {}", eth)
        log.info("packet ports: {}", getOutPacketPorts(pktOut))
        eth
    }

    def testConntrack() {
        log.info("creating chains")
        val brChain = newInboundChainOnBridge("brChain", bridge)
        brChain should not be null

        val vmPortIds: ju.Set[UUID] = new ju.HashSet[UUID]()
        vmPortIds ++= vmPorts.map { p => p.getId }.toSet

        val returnCond = new Condition()
        returnCond.matchReturnFlow = true
        returnCond.inPortIds = vmPortIds
        returnCond.inPortInv = true
        val returnRule = newLiteralRuleOnChain(
            brChain, 1, returnCond, RuleResult.Action.ACCEPT)
        returnRule should not be null

        val dropIncomingCond = new Condition()
        dropIncomingCond.inPortIds = vmPortIds
        dropIncomingCond.inPortInv = true
        val dropIncomingRule = newLiteralRuleOnChain(
            brChain, 2, dropIncomingCond, RuleResult.Action.DROP)
        dropIncomingRule should not be null

        val forwardCond = new Condition()
        forwardCond.inPortIds = vmPortIds
        forwardCond.matchForwardFlow = true
        val forwardRule = newLiteralRuleOnChain(
            brChain, 3, forwardCond, RuleResult.Action.ACCEPT)
        forwardRule should not be null

        clusterDataClient().bridgesUpdate(bridge)

        testSnat()
    }

    def testDnat() {
        log.info("Sending a tcp packet that should be DNAT'ed")
        injectTcp("uplinkPort", uplinkGatewayMac, IPv4Addr("62.72.82.1"), 20301,
                                uplinkPortMac, dnatAddress, 80,
                                syn = true)
        var pktOut = requestOfType[PacketsExecute](packetsEventsProbe).packet
        var outPorts = getOutPacketPorts(pktOut)
        drainProbes()
        val newMappings: Set[String] = updateAndDiffMappings()
        newMappings.size should be (1)
        leaseManager.fwdKeys.get(newMappings.head).flowCount.get should be (1)
        outPorts.size should be (1)

        val mapping = new Mapping(newMappings.head,
                leaseManager.fwdKeys.get(newMappings.head).flowCount,
                "uplinkPort", localPortNumberToName(outPorts.head).get,
                pktOut.getPacket,
                applyOutPacketActions(pktOut))

        log.info("Sending a return tcp packet")
        injectTcp(mapping.revInPort,
            mapping.revInFromMac, mapping revInFromIp, mapping.revInFromPort,
            mapping.revInToMac, mapping.revInToIp, mapping.revInToPort,
            ack = true, syn = true)
        pktOut = requestOfType[PacketsExecute](packetsEventsProbe).packet
        outPorts = getOutPacketPorts(pktOut)
        drainProbes()
        updateAndDiffMappings().size should be (0)
        outPorts.size should be (1)
        localPortNumberToName(outPorts.head) should be (Some(mapping.fwdInPort))
        mapping.matchReturnOutPacket(applyOutPacketActions(pktOut))
        mapping.flowCount.get should be (1)

        log.debug("sending a second forward packet, from a different router")
        injectTcp("uplinkPort",
            MAC.fromString("02:99:99:77:77:77"), IPv4Addr("62.72.82.1"), 20301,
            uplinkPortMac, dnatAddress, 80, ack = true)
        pktOut = requestOfType[PacketsExecute](packetsEventsProbe).packet
        outPorts = getOutPacketPorts(pktOut)
        drainProbes()
        updateAndDiffMappings().size should be (0)
        outPorts.size should be (1)
        localPortNumberToName(outPorts.head) should be (Some(mapping.fwdOutPort))
        mapping.matchForwardOutPacket(applyOutPacketActions(pktOut))
        mapping.flowCount.get should be (2)

        drainProbe(wflowRemovedProbe)
        flowController() ! InvalidateFlowsByTag(
            FlowTagger.invalidateFlowsByDevice(router.getId))
        requestOfType[WildcardFlowRemoved](wflowRemovedProbe)
        requestOfType[WildcardFlowRemoved](wflowRemovedProbe)
        requestOfType[WildcardFlowRemoved](wflowRemovedProbe)

        mapping.flowCount.get should be (0)
        leaseManager.fwdKeys.size should be (0)
    }

    def testSnat() {
        log.info("Sending a tcp packet that should be SNAT'ed")
        injectTcp(vmPortNames.head, vmMacs.head, vmIps.head, 30501,
            routerMac, IPv4Addr("62.72.82.1"), 22,
            syn = true)
        var pktOut = requestOfType[PacketsExecute](packetsEventsProbe).packet
        var outPorts = getOutPacketPorts(pktOut)
        drainProbes()
        val newMappings: Set[String] = updateAndDiffMappings()
        newMappings.size should be (1)
        leaseManager.fwdKeys.get(newMappings.head).flowCount.get should be (1)
        outPorts.size should be (1)
        localPortNumberToName(outPorts.head) should be (Some("uplinkPort"))

        val mapping = new Mapping(newMappings.head,
            leaseManager.fwdKeys.get(newMappings.head).flowCount,
            vmPortNames.head, "uplinkPort",
            pktOut.getPacket,
            applyOutPacketActions(pktOut))

        log.info("Sending a return tcp packet")
        injectTcp(mapping.revInPort,
            mapping.revInFromMac, mapping revInFromIp, mapping.revInFromPort,
            mapping.revInToMac, mapping.revInToIp, mapping.revInToPort,
            ack = true, syn = true)

        pktOut = requestOfType[PacketsExecute](packetsEventsProbe).packet
        outPorts = getOutPacketPorts(pktOut)
        drainProbes()
        updateAndDiffMappings().size should be (0)
        outPorts.size should be (1)
        localPortNumberToName(outPorts.head) should be (Some(mapping.fwdInPort))
        mapping.matchReturnOutPacket(applyOutPacketActions(pktOut))
        mapping.flowCount.get should be (1)

        log.debug("sending a second forward packet, from a different network card")
        injectTcp(vmPortNames.head, "02:34:34:43:43:20", vmIps.head, 30501,
            routerMac, IPv4Addr("62.72.82.1"), 22,
            ack = true)
        pktOut = requestOfType[PacketsExecute](packetsEventsProbe).packet
        outPorts = getOutPacketPorts(pktOut)
        drainProbes()
        updateAndDiffMappings().size should be (0)
        outPorts.size should be (1)
        localPortNumberToName(outPorts.head) should be (Some(mapping.fwdOutPort))
        mapping.matchForwardOutPacket(applyOutPacketActions(pktOut))
        mapping.flowCount.get should be (2)

        drainProbe(wflowRemovedProbe)
        flowController() ! InvalidateFlowsByTag(
            FlowTagger.invalidateFlowsByDevice(router.getId))
        requestOfType[WildcardFlowRemoved](wflowRemovedProbe)
        requestOfType[WildcardFlowRemoved](wflowRemovedProbe)
        requestOfType[WildcardFlowRemoved](wflowRemovedProbe)

        mapping.flowCount.get should be (0)
        leaseManager.fwdKeys.size should be (0)
    }

    // -----------------------------------------------
    // -----------------------------------------------
    //             ICMP over NAT tests
    // -----------------------------------------------
    // -----------------------------------------------

    private def pingExpectDnated(outPort: String,
                                 srcMac: MAC, srcIp: IPv4Addr,
                                 dstMac: MAC, dstIp: IPv4Addr,
                                 icmpId: Short, icmpSeq: Short): Mapping = {

        log.info("Sending a PING that should be DNAT'ed")
        val icmpReq = injectIcmpEchoReq(outPort,
            srcMac, srcIp, dstMac, dstIp, icmpId, icmpSeq)

        val pktOut = requestOfType[PacketsExecute](packetsEventsProbe).packet
        val outPorts = getOutPacketPorts(pktOut)
        outPorts.size should be (1)

        val newMappings: Set[String] = updateAndDiffMappings()
        newMappings.size should be (1)
        leaseManager.fwdKeys.get(newMappings.head).flowCount.get should be (1)

        val eth = applyOutPacketActions(pktOut)
        val mapping = new Mapping(newMappings.head,
            leaseManager.fwdKeys.get(newMappings.head).flowCount,
            outPort, localPortNumberToName(outPorts.head).get,
            pktOut.getPacket,
            eth)

        var ipPak = eth.getPayload.asInstanceOf[IPv4]
        ipPak should not be null
        ipPak = eth.getPayload.asInstanceOf[IPv4]
        ipPak.getProtocol should be (ICMP.PROTOCOL_NUMBER)
        ipPak.getSourceAddress should be (srcIp.addr)
        ipPak.getDestinationAddress should be (mapping.fwdOutToIp.addr)

        val icmpPak = ipPak.getPayload.asInstanceOf[ICMP]
        icmpPak should not be null
        icmpPak.getType should be (ICMP.TYPE_ECHO_REQUEST)
        icmpPak.getIdentifier should be (icmpReq.getIdentifier)
        icmpPak.getQuench should be (icmpReq.getQuench)

        mapping
    }

    private def pongExpectRevDnated(outPort: String,
                                    srcMac: MAC, srcIp: IPv4Addr,
                                    dstMac: MAC, dstIp: IPv4Addr,
                                    origSrcIp: IPv4Addr, icmpId: Short, icmpSeq: Short) {

        log.info("Send ICMP reply into the router, should be revSnat'ed")
        val icmpReply = injectIcmpEchoReply(outPort, srcMac, srcIp,
            icmpId, icmpSeq, dstMac, dstIp)

        // Expect the packet delivered back to the source, MAC should be learnt
        // already so expect straight on the bridge's port
        val pktOut = requestOfType[PacketsExecute](packetsEventsProbe).packet
        val outPorts = getOutPacketPorts(pktOut)
        outPorts.size should be (1)
        localPortNumberToName(outPorts.head) should be (Some("uplinkPort"))

        val eth = applyOutPacketActions(pktOut)
        val ipPak = eth.getPayload.asInstanceOf[IPv4]
        ipPak should not be null
        ipPak.getProtocol should be (ICMP.PROTOCOL_NUMBER)
        ipPak.getSourceAddress should be (dnatAddress.addr)
        ipPak.getDestinationAddress should be (origSrcIp.addr)
        val icmpPak = ipPak.getPayload.asInstanceOf[ICMP]
        icmpPak should not be null
        icmpPak.getType should be (ICMP.TYPE_ECHO_REPLY)
        icmpPak.getIdentifier should be (icmpReply.getIdentifier)

    }

    /**
     * Assuming that expectIcmpData was sent, this method will expect an
     * ICMP UNREACHABLE HOST back on the given port, containing in its data
     * the expectIcmpData
     *
     * @param port
     * @param expectIcmpData
     */
    private def expectICMPError(port: String, expectIcmpData: IPv4) {
        // we should have a packet coming out of the expected port
        val pktOut = requestOfType[PacketsExecute](packetsEventsProbe).packet
        val outPorts = getOutPacketPorts(pktOut)
        outPorts.size should be (1)
        localPortNumberToName(outPorts.head) should be (Some(port))

        // the ip packet should be addressed as expected
        val ethApplied = applyOutPacketActions(pktOut)
        val ipv4 = ethApplied.getPayload.asInstanceOf[IPv4]

        log.debug("Received reply {}", pktOut)
        ipv4 should not be null
        ipv4.getSourceAddress should be (expectIcmpData.getDestinationAddress.addressAsInt)
        ipv4.getDestinationAddress should be (expectIcmpData.getSourceAddress.addressAsInt)
        // The ICMP contents should be as expected
        val icmp = ipv4.getPayload.asInstanceOf[ICMP]
        icmp should not be null
        icmp.getType should be (ICMP.TYPE_UNREACH)
        icmp.getCode should be (ICMP.UNREACH_CODE.UNREACH_HOST.toByte)

        // Now we need to look inside the ICMP data that not only should
        // bring the data contained in the original packet that this ICMP is
        // replying to, but also should do NAT on the L3 and L4 data contained
        // in it
        val icmpData = ByteBuffer.wrap(icmp.getData)
        val actualIcmpData = new IPv4()
        actualIcmpData.deserializeHeader(icmpData)
        actualIcmpData.getProtocol should be (expectIcmpData.getProtocol)
        actualIcmpData.getSourceAddress should be (expectIcmpData.getSourceAddress)
        actualIcmpData.getDestinationAddress should be (expectIcmpData.getDestinationAddress)
        actualIcmpData.getTtl should be (expectIcmpData.getTtl - 1)

        // The parts of the TCP payload inside the ICMP error
        expectIcmpData.getProtocol match {
            case ICMP.PROTOCOL_NUMBER =>
                val icmpPayload = expectIcmpData.getPayload
                val expect = ju.Arrays.copyOfRange(
                    icmpPayload.serialize(), 0, 8)
                val actual = ju.Arrays.copyOfRange(
                    icmpPayload.serialize(), 0, 8)
                expect should be (actual)
            case TCP.PROTOCOL_NUMBER =>
                val tcpPayload = expectIcmpData.getPayload.asInstanceOf[TCP]
                tcpPayload.getSourcePort should be (icmpData.getShort)
                tcpPayload.getDestinationPort should be (icmpData.getShort)
                tcpPayload.getSeqNo should be (icmpData.getShort)
                tcpPayload.getAckNo should be (icmpData.getShort)
        }
    }

    /**
     * Pings from src (assumed a VM in the private network) to a dst (assumed
     * a host beyond the gateway). Expects that the router outputs the packet
     * on the gateway link, with the srcIp translated
     */
    private def pingExpectSnated(port: String, srcMac: MAC, srcIp: IPv4Addr,
                                 dstMac: MAC, dstIp: IPv4Addr,
                                 icmpId: Short, icmpSeq: Short,
                                 transSrcIpStart: IPv4Addr,
                                 transSrcIpEnd: IPv4Addr) = {

        log.info("Sending a PING that should be NAT'ed")
        val icmpReq = injectIcmpEchoReq(port,
                        srcMac, srcIp,
                        dstMac, dstIp,
                        icmpId, icmpSeq)

        val pktOut = requestOfType[PacketsExecute](packetsEventsProbe).packet
        val outPorts = getOutPacketPorts(pktOut)
        outPorts.size should be === 1
        localPortNumberToName(outPorts.head) should be === Some("uplinkPort")

        val eth = applyOutPacketActions(pktOut)
        var ipPak = eth.getPayload.asInstanceOf[IPv4]
        ipPak should not be null
        ipPak = eth.getPayload.asInstanceOf[IPv4]
        ipPak.getProtocol should be (ICMP.PROTOCOL_NUMBER)
        ipPak.getDestinationAddress should be === dstIp.addr

        val snatedIp = IPv4Addr.fromInt(ipPak.getSourceAddress)
        transSrcIpStart.range(transSrcIpEnd).find {
            case ip: IPv4Addr if ip equals snatedIp => true
            case _ => false
        } shouldBe Some(snatedIp)

        val icmpPak = ipPak.getPayload.asInstanceOf[ICMP]
        icmpPak should not be null
        icmpPak.getType should be === ICMP.TYPE_ECHO_REQUEST
        icmpPak.getIdentifier should be === icmpReq.getIdentifier
        icmpPak.getQuench should be === icmpReq.getQuench

        snatedIp

    }

    /**
     * Puts an ICMP reply from a host beyond the gateway on the router's uplink
     * port with a destination on the router's uplink IP. Expects that the
     * origSrcIp (a VM) receives the packet on its port, with the dstIp
     * translated to origSrcIp
     */
    private def pongExpectRevNatd(port: String, srcMac: MAC, srcIp: IPv4Addr,
                                      dstMac: MAC, dstIp: IPv4Addr,
                                      icmpId: Short, icmpSeq: Short,
                                      origSrcIp: IPv4Addr) {

        log.info("Send ICMP reply into the router, should be revSnat'ed")
        val icmpReply = injectIcmpEchoReply(port, srcMac, srcIp,
                                            icmpId, icmpSeq, dstMac, dstIp)

        // Expect the packet delivered back to the source, MAC should be learnt
        // already so expect straight on the bridge's port
        val pktOut = requestOfType[PacketsExecute](packetsEventsProbe).packet
        val outPorts = getOutPacketPorts(pktOut)
        outPorts.size should be (1)

        // localPortNumberToName(outPorts.head) should be (Some())

        // TODO (galo) check mappings?
        val eth = applyOutPacketActions(pktOut)
        val ipPak = eth.getPayload.asInstanceOf[IPv4]
        ipPak should not be null
        ipPak.getProtocol should be (ICMP.PROTOCOL_NUMBER)
        ipPak.getSourceAddress should be (srcIp.addr)
        ipPak.getDestinationAddress should be (origSrcIp.addr)
        val icmpPak = ipPak.getPayload.asInstanceOf[ICMP]
        icmpPak should not be null
        icmpPak.getType should be === ICMP.TYPE_ECHO_REPLY
        icmpPak.getIdentifier should be === icmpReply.getIdentifier

    }

    /**
     * See issue #435
     *
     * Two hosts from a private network send pings that should be SNAT'ed,
     * we check that the router can send each reply back to the correct host.
     */
    def testSnatPing() {

        val vm1Port = vmPortNames.head
        val vm5Port = vmPortNames.last
        val upPort = "uplinkPort"
        val src1Mac = vmMacs.head // VM inside the private network
        val src1Ip = vmIps.last
        val src5Mac = vmMacs.last // VM inside the private network
        val src5Ip = vmIps.last
        val dstMac = routerMac   // A destination beyond the gateway
        val dstIp = IPv4Addr("62.72.82.1")

        // translated source is now random

        log.info("Sending ICMP pings into private network")
        val snatIp16 = pingExpectSnated(vm1Port, src1Mac, src1Ip, dstMac, dstIp,
                                        16, 1, snatAddressStart, snatAddressEnd)
        val snatIp21 = pingExpectSnated(vm5Port, src5Mac, src5Ip, dstMac, dstIp,
                                        21, 1, snatAddressStart, snatAddressEnd)
        // TODO (galo) test that should
        log.info("Sending ICMP pings, expect userspace match")
        val snatIp16_2 = pingExpectSnated(vm1Port, src1Mac, src1Ip, dstMac,
                                          dstIp, 16, 1, snatAddressStart,
                                          snatAddressEnd)
        val snatIp21_2 = pingExpectSnated(vm5Port, src5Mac, src5Ip, dstMac,
                                          dstIp, 21, 1, snatAddressStart,
                                          snatAddressEnd)

        snatIp16 should be === snatIp16_2
        snatIp21 should be === snatIp21_2

        log.info("Sending ICMP replies")
        // Let's see if replies get into the private network
        pongExpectRevNatd(upPort, uplinkGatewayMac, dstIp, uplinkPortMac,
                          snatIp16, 16, 1, src1Ip)
        pongExpectRevNatd(upPort, uplinkGatewayMac, dstIp, uplinkPortMac,
                          snatIp21, 21, 1, src5Ip)
        pongExpectRevNatd(upPort, uplinkGatewayMac, dstIp, uplinkPortMac,
                          snatIp16, 16, 2, src1Ip)
        pongExpectRevNatd(upPort, uplinkGatewayMac, dstIp, uplinkPortMac,
                          snatIp21, 21, 2, src5Ip)

    }

    /**
     * See issue #435
     *
     * A VM sends a ping request that should be DNAT'ed, and we check that the
     * reply gets back correctly.
     */
    def testDnatPing() {
        val srcIp = IPv4Addr("62.72.82.1")
        val mp = pingExpectDnated("uplinkPort", uplinkGatewayMac, srcIp,
                                  uplinkPortMac, dnatAddress, 92, 1)
        pongExpectRevDnated(mp.revInPort, mp.revInFromMac, mp.revInFromIp,
                            mp.revInToMac, mp.revInToIp, srcIp, 92, 1)
    }

    /**
     * You send a packet from a VM to a remote addr. beyond the router,
     * this method will kindly check that it arrives, reply with an ICMP
     * UNREACHABLE, and verify that the reverse SNAT is done properly
     */
    private def verifySnatICMPErrorAfterPacket() {
        val pktOut = requestOfType[PacketsExecute](packetsEventsProbe).packet
        val outPorts = getOutPacketPorts(pktOut)
        outPorts.size should be (1)
        drainProbes()

        val newMappings: Set[String] = updateAndDiffMappings()
        newMappings.size should be (1)
        leaseManager.fwdKeys.get(newMappings.head).flowCount.get should be (1)
        localPortNumberToName(outPorts.head) should be (Some("uplinkPort"))

        val eth = pktOut.getPacket
        val ethApplied = applyOutPacketActions(pktOut)
        val mp = new Mapping(newMappings.head,
            leaseManager.fwdKeys.get(newMappings.head).flowCount,
            vmPortNames.head, "uplinkPort", eth,
            ethApplied)

        log.info("Reply with an ICMP error that should be rev SNAT'ed")
        injectIcmpUnreachable("uplinkPort", mp.revInFromMac, mp.revInToMac,
            ICMP.UNREACH_CODE.UNREACH_HOST, ethApplied)

        // We should see the ICMP reply back at the sender VM
        // Note that the second parameter is the packet sent from the origin
        // who is unaware of the NAT, eth has no actions applied so we can
        // use this one
        expectICMPError(vmPortNames.head, eth.getPayload.asInstanceOf[IPv4])
    }

    /**
     * You send a packet from a remote addr. beyond the gateway to the DNAT
     * address, this method will kindly check that it arrives to the VM,
     * reply with an ICMP UNREACHABLE, and verify that the reverse DNAT is done
     * properly
     */
    private def verifyDnatICMPErrorAfterPacket() {
        val pktOut = requestOfType[PacketsExecute](packetsEventsProbe).packet
        val outPorts = getOutPacketPorts(pktOut)
        outPorts.size should be (1)
        drainProbes()

        val newMappings: Set[String] = updateAndDiffMappings()
        newMappings.size should be (1)
        leaseManager.fwdKeys.get(newMappings.head).flowCount.get should be (1)

        val eth = pktOut.getPacket
        val ethApplied = applyOutPacketActions(pktOut)
        val mapping = new Mapping(newMappings.head,
            leaseManager.fwdKeys.get(newMappings.head).flowCount,
            "uplinkPort", localPortNumberToName(outPorts.head).get,
            eth, ethApplied)

        log.info("TCP reached destination, {}", pktOut)

        // 10.0.0.X:80 -> 62.72.82.1:888
        log.info("Reply with an ICMP error that should be rev DNAT'ed")
        injectIcmpUnreachable(mapping.revInPort, mapping.revInFromMac,
            mapping.revInToMac, ICMP.UNREACH_CODE.UNREACH_HOST, ethApplied)

        // We should see the ICMP reply back going out on the original source
        // Note that the second parameter is the packet sent from the origin
        // who is unaware of the NAT, eth has no actions applied so we can
        // use this one
        expectICMPError("uplinkPort", eth.getPayload.asInstanceOf[IPv4])

    }

    /**
     * See issue #513
     *
     * A VM sends a TCP packet that should be SNAT'ed, an ICMP error is sent
     * back from the destination, we expect it to get to the VM
     */
    def testSnatICMPErrorAfterTCP() {
        val dstIp = IPv4Addr("62.72.82.1")
        log.info("Sending a TCP packet that should be SNAT'ed")
        injectTcp(vmPortNames.head, vmMacs.head, vmIps.head, 5555,
            routerMac, dstIp, 1111, syn = true)
        verifySnatICMPErrorAfterPacket()
    }

    /**
     * See issue #513
     *
     * A TCP packet is sent to a VM that should be DNAT'ed, the VM replies with
     * an ICMP error is sent, we expect it to get back
     */
    def testDnatICMPErrorAfterTCP() {
        val srcIp = IPv4Addr("62.72.82.1")
        log.info("Sending a TCP packet that should be DNAT'ed")
        injectTcp("uplinkPort", uplinkGatewayMac, srcIp, 888,
            uplinkPortMac, dnatAddress, 333, syn = true)
        verifyDnatICMPErrorAfterPacket()
    }

    /**
     * See issue #513
     *
     * ICMP packet is sent to a VM that should be SNAT'ed, the VM replies with
     * an ICMP error is sent, we expect it to get back
     */
    def testSnatICMPErrorAfterICMP() {
        val dstIp = IPv4Addr("62.72.82.1")
        log.info("Sending a TCP packet that should be SNAT'ed")
        injectIcmpEchoReq(
            vmPortNames.head, vmMacs.head, vmIps.head, routerMac, dstIp, 22, 55)
        verifySnatICMPErrorAfterPacket()
    }

    /**
     * See issue #513
     *
     * ICMP packet is sent to a VM that should be DNAT'ed, the VM replies with
     * an ICMP error is sent, we expect it to get back
     */
    def testDnatICMPErrorAfterICMP() {
        val srcIp = IPv4Addr("62.72.82.1")
        log.info("Sending a TCP packet that should be DNAT'ed")
        injectIcmpEchoReq("uplinkPort", uplinkGatewayMac, srcIp,
            uplinkPortMac, dnatAddress, 7, 6)
        verifyDnatICMPErrorAfterPacket()
    }

}
