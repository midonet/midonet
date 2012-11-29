/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.layer4

import scala.collection.JavaConversions._

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID
import java.{util => ju}

import com.midokura.midolman.FlowController.{InvalidateFlowsByTag,
                                             DiscardPacket, WildcardFlowRemoved}
import com.midokura.midolman.layer3.Route
import com.midokura.midolman.layer3.Route.NextHop
import com.midokura.midolman.rules.{NatTarget, RuleResult, Condition}
import com.midokura.midolman.topology.LocalPortActive
import com.midokura.packets._
import com.midokura.midonet.cluster.data.ports.MaterializedRouterPort
import com.midokura.midolman.util.AddressConversions._
import com.midokura.midolman.{VMsBehindRouterFixture, MidolmanTestCase}

@RunWith(classOf[JUnitRunner])
class NatTestCase extends MidolmanTestCase with VMsBehindRouterFixture {
    private final val log = LoggerFactory.getLogger(classOf[NatTestCase])

    private val uplinkGatewayAddr: IntIPv4 = "180.0.1.1"
    private val uplinkGatewayMac: MAC = "02:0b:09:07:05:03"
    private val uplinkNwAddr: IntIPv4 = "180.0.1.0"
    private val uplinkNwLen = 24
    private val uplinkPortAddr: IntIPv4 = "180.0.1.2"
    private val uplinkPortMac: MAC = "02:0a:08:06:04:02"
    private var uplinkPort: MaterializedRouterPort = null
    private var uplinkPortNum: Short = 0

    private val dnatAddress = "180.0.1.100"
    private val snatAddressStart: IntIPv4 = "180.0.1.200"
    private val snatAddressEnd: IntIPv4 = "180.0.1.205"

    private var leaseManager: NatLeaseManager = null
    private var mappings = Set[String]()

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

        def fwdInFromIp: IntIPv4 =
                fwdInPacket.getPayload.asInstanceOf[IPv4].getSourceAddress
        def fwdInToIp: IntIPv4 =
                fwdInPacket.getPayload.asInstanceOf[IPv4].getDestinationAddress

        def revOutFromIp: IntIPv4 = fwdInToIp
        def revOutToIp: IntIPv4 = fwdInFromIp

        def fwdOutFromIp: IntIPv4 =
                fwdOutPacket.getPayload.asInstanceOf[IPv4].getSourceAddress
        def fwdOutToIp: IntIPv4 =
                fwdOutPacket.getPayload.asInstanceOf[IPv4].getDestinationAddress

        def revInFromIp: IntIPv4 = fwdOutToIp
        def revInToIp: IntIPv4 = fwdOutFromIp

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
            eth.getSourceMACAddress should be === (fwdOutFromMac)
            eth.getDestinationMACAddress should be === (fwdOutToMac)
            intToIp(ip.getSourceAddress) should be === (fwdOutFromIp)
            intToIp(ip.getDestinationAddress) should be === (fwdOutToIp)
            tcp.getSourcePort should be === (fwdOutFromPort)
            tcp.getDestinationPort should be === (fwdOutToPort)
        }

        def matchReturnOutPacket(eth: Ethernet) {
            val ip = eth.getPayload.asInstanceOf[IPv4]
            val tcp = ip.getPayload.asInstanceOf[TCP]
            eth.getSourceMACAddress should be === (revOutFromMac)
            eth.getDestinationMACAddress should be === (revOutToMac)
            intToIp(ip.getSourceAddress) should be === (revOutFromIp)
            intToIp(ip.getDestinationAddress) should be === (revOutToIp)
            tcp.getSourcePort should be === (revOutFromPort)
            tcp.getDestinationPort should be === (revOutToPort)
        }
    }

    override def beforeTest() {
        super.beforeTest()

        uplinkPort = newExteriorRouterPort(router, uplinkPortMac,
            uplinkPortAddr, uplinkNwAddr, uplinkNwLen)
        uplinkPort should not be null
        materializePort(uplinkPort, host, "uplinkPort")
        requestOfType[LocalPortActive](portEventsProbe)

        uplinkPortNum = dpController().underlyingActor.
            vifToLocalPortNumber(uplinkPort.getId).getOrElse(0.toShort)
        uplinkPortNum should not be === (0)

        var route = newRoute(router, "0.0.0.0", 0, "0.0.0.0", 0,
            NextHop.PORT, uplinkPort.getId, uplinkGatewayAddr, 1)
        route should not be null

        route = newRoute(router, "0.0.0.0", 0, uplinkNwAddr, uplinkNwLen,
            NextHop.PORT, uplinkPort.getId, intToIp(Route.NO_GATEWAY), 10)
        route should not be null

        // create NAT rules
        log.info("creating chains")
        val rtrOutChain = newOutboundChainOnRouter("rtrOutChain", router)
        val rtrInChain = newInboundChainOnRouter("rtrInChain", router)

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
        dnatCond.nwSrcIp = vmNetworkIp
        dnatCond.nwSrcInv = true
        dnatCond.nwDstIp = dnatAddress
        dnatCond.tpDstStart = 80.toInt
        dnatCond.tpDstEnd = 80.toInt
        val dnatTarget =  new NatTarget(vmIps.head, vmIps.last, 80, 80)
        val dnatRule = newForwardNatRuleOnChain(rtrInChain, 2, dnatCond,
                RuleResult.Action.CONTINUE, Set(dnatTarget), isDnat = true)
        dnatRule should not be null

        // 2.- SNAT -> from vm-network to !vm-network  dstPort=22 --> snat(uplinkPortAddr)
        log.info("adding SNAT rule")
        val snatCond = new Condition()
        snatCond.nwSrcIp = vmNetworkIp
        snatCond.nwDstIp = vmNetworkIp
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
        feedArpCache("uplinkPort", uplinkGatewayAddr, uplinkGatewayMac,
            uplinkPortAddr, uplinkPortMac)
        fishForRequestOfType[DiscardPacket](flowProbe())
        drainProbes()

        // feed the router's arp cache with each of the vm's addresses
        (vmPortNames, vmMacs, vmIps).zipped foreach {
            (name, mac, ip) =>
                feedArpCache(name, ip.addressAsInt, mac,
                    routerIp.addressAsInt, routerMac)
                fishForRequestOfType[DiscardPacket](flowProbe())
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
        injectTcp("uplinkPort", uplinkGatewayMac, "62.72.82.1", 20301,
                                uplinkPortMac, dnatAddress, 80,
                                syn = true)
        var pktOut = requestOfType[PacketsExecute](packetsEventsProbe).packet
        var outPorts = getOutPacketPorts(pktOut)
        drainProbes()
        val newMappings: Set[String] = updateAndDiffMappings()
        newMappings.size should be === (1)
        leaseManager.fwdKeys.get(newMappings.head).flowCount.get should be === (1)
        outPorts.size should be === (1)

        val mapping = new Mapping(newMappings.head,
                leaseManager.fwdKeys.get(newMappings.head).flowCount,
                "uplinkPort", localPortNumberToName(outPorts.head).get,
                Ethernet.deserialize(pktOut.getData),
                applyOutPacketActions(pktOut))

        log.info("Sending a return tcp packet")
        injectTcp(mapping.revInPort,
            mapping.revInFromMac, mapping revInFromIp, mapping.revInFromPort,
            mapping.revInToMac, mapping.revInToIp, mapping.revInToPort,
            ack = true, syn = true)
        pktOut = requestOfType[PacketsExecute](packetsEventsProbe).packet
        outPorts = getOutPacketPorts(pktOut)
        drainProbes()
        updateAndDiffMappings().size should be === (0)
        outPorts.size should be === (1)
        localPortNumberToName(outPorts.head) should be === (Some(mapping.fwdInPort))
        mapping.matchReturnOutPacket(applyOutPacketActions(pktOut))
        mapping.flowCount.get should be === (1)

        log.debug("sending a second forward packet, from a different router")
        injectTcp("uplinkPort", "02:99:99:77:77:77", "62.72.82.1", 20301,
            uplinkPortMac, dnatAddress, 80,
            ack = true)
        pktOut = requestOfType[PacketsExecute](packetsEventsProbe).packet
        outPorts = getOutPacketPorts(pktOut)
        drainProbes()
        updateAndDiffMappings().size should be === (0)
        outPorts.size should be === (1)
        localPortNumberToName(outPorts.head) should be === (Some(mapping.fwdOutPort))
        mapping.matchForwardOutPacket(applyOutPacketActions(pktOut))
        mapping.flowCount.get should be === (2)

        drainProbe(flowEventsProbe)
        flowController() ! InvalidateFlowsByTag(router.getId)
        requestOfType[WildcardFlowRemoved](flowEventsProbe)
        requestOfType[WildcardFlowRemoved](flowEventsProbe)
        requestOfType[WildcardFlowRemoved](flowEventsProbe)

        mapping.flowCount.get should be === (0)
        leaseManager.fwdKeys.size should be === (0)
    }

    def testSnat() {
        log.info("Sending a tcp packet that should be SNAT'ed")
        injectTcp(vmPortNames.head, vmMacs.head, vmIps.head, 30501,
            routerMac, "62.72.82.1", 22,
            syn = true)
        var pktOut = requestOfType[PacketsExecute](packetsEventsProbe).packet
        var outPorts = getOutPacketPorts(pktOut)
        drainProbes()
        val newMappings: Set[String] = updateAndDiffMappings()
        newMappings.size should be === (1)
        leaseManager.fwdKeys.get(newMappings.head).flowCount.get should be === (1)
        outPorts.size should be === (1)
        localPortNumberToName(outPorts.head) should be === (Some("uplinkPort"))

        val mapping = new Mapping(newMappings.head,
            leaseManager.fwdKeys.get(newMappings.head).flowCount,
            vmPortNames.head, "uplinkPort",
            Ethernet.deserialize(pktOut.getData),
            applyOutPacketActions(pktOut))

        log.info("Sending a return tcp packet")
        injectTcp(mapping.revInPort,
            mapping.revInFromMac, mapping revInFromIp, mapping.revInFromPort,
            mapping.revInToMac, mapping.revInToIp, mapping.revInToPort,
            ack = true, syn = true)

        pktOut = requestOfType[PacketsExecute](packetsEventsProbe).packet
        outPorts = getOutPacketPorts(pktOut)
        drainProbes()
        updateAndDiffMappings().size should be === (0)
        outPorts.size should be === (1)
        localPortNumberToName(outPorts.head) should be === (Some(mapping.fwdInPort))
        mapping.matchReturnOutPacket(applyOutPacketActions(pktOut))
        mapping.flowCount.get should be === (1)

        log.debug("sending a second forward packet, from a different network card")
        injectTcp(vmPortNames.head, "02:34:34:43:43:20", vmIps.head, 30501,
            routerMac, "62.72.82.1", 22,
            ack = true)
        pktOut = requestOfType[PacketsExecute](packetsEventsProbe).packet
        outPorts = getOutPacketPorts(pktOut)
        drainProbes()
        updateAndDiffMappings().size should be === (0)
        outPorts.size should be === (1)
        localPortNumberToName(outPorts.head) should be === (Some(mapping.fwdOutPort))
        mapping.matchForwardOutPacket(applyOutPacketActions(pktOut))
        mapping.flowCount.get should be === (2)

        drainProbe(flowEventsProbe)
        flowController() ! InvalidateFlowsByTag(router.getId)
        requestOfType[WildcardFlowRemoved](flowEventsProbe)
        requestOfType[WildcardFlowRemoved](flowEventsProbe)
        requestOfType[WildcardFlowRemoved](flowEventsProbe)

        mapping.flowCount.get should be === (0)
        leaseManager.fwdKeys.size should be === (0)
    }
}
