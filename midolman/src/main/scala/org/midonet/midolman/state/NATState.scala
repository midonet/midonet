/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.midolman.state

import java.nio.ByteBuffer
import java.util.{UUID, Random}

import org.midonet.midolman.rules.NatTarget
import org.midonet.packets.{IPv4Addr, IPv4, ICMP, TCP, UDP, IPAddr}
import org.midonet.sdn.flows.FlowTagger.FlowStateTag
import org.midonet.sdn.flows.WildcardMatch
import org.midonet.sdn.state.FlowStateTransaction

object NatState {
    private val WILDCARD_PORT = 0

    object NatKey {
        final val USHORT = 0xffff // Constant used to prevent sign extension

        sealed abstract class Type {
            def inverse: Type
        }
        case object FWD_SNAT extends Type {
            def inverse = REV_SNAT
        }
        case object FWD_DNAT extends Type {
            def inverse = REV_DNAT
        }
        case object FWD_STICKY_DNAT extends Type {
            def inverse = REV_STICKY_DNAT
        }
        case object REV_SNAT extends Type {
            def inverse = FWD_SNAT
        }
        case object REV_DNAT extends Type {
            def inverse = FWD_DNAT
        }
        case object REV_STICKY_DNAT extends Type {
            def inverse = FWD_STICKY_DNAT
        }

        def apply(wcMatch: WildcardMatch, deviceId: UUID, keyType: Type): NatKey = {
            val key = NatKey(keyType,
                             wcMatch.getNetworkSourceIP,
                             if (keyType eq FWD_STICKY_DNAT) WILDCARD_PORT
                             else wcMatch.getTransportSource,
                             wcMatch.getNetworkDestinationIP,
                             if (keyType eq REV_STICKY_DNAT) WILDCARD_PORT
                             else wcMatch.getTransportDestination,
                             wcMatch.getNetworkProtocol.byteValue(),
                             deviceId)

            if (wcMatch.getNetworkProtocol == ICMP.PROTOCOL_NUMBER)
                processIcmp(key, wcMatch)

            key
        }

        private def processIcmp(natKey: NatKey, wcMatch: WildcardMatch): Unit =
            wcMatch.getTransportSource.byteValue() match {
                case ICMP.TYPE_ECHO_REPLY | ICMP.TYPE_ECHO_REQUEST =>
                    val port = wcMatch.getIcmpIdentifier.intValue() & USHORT
                    natKey.transportSrc = port
                    natKey.transportDst = port
                case ICMP.TYPE_PARAMETER_PROBLEM | ICMP.TYPE_UNREACH |
                     ICMP.TYPE_TIME_EXCEEDED if wcMatch.getIcmpData ne null =>
                    // The nat mapping lookup should be done based on the
                    // contents of the ICMP data field.
                    val bb = ByteBuffer.wrap(wcMatch.getIcmpData)
                    val ipv4 = new IPv4
                    ipv4.deserializeHeader(bb)
                    natKey.networkProtocol = ipv4.getProtocol
                    if (natKey.networkProtocol == ICMP.PROTOCOL_NUMBER) {
                        // If replying to a prev. ICMP, mapping was done
                        // against the icmp id.
                        val icmp = new ICMP
                        icmp.deserialize(bb)
                        val port = icmp.getIdentifier & USHORT
                        natKey.transportSrc = port
                        natKey.transportDst = port
                    } else {
                        // Invert src and dst because the icmpData contains the
                        // original msg that the ICMP ERROR replies to.
                        // TCP/UDP deserialize would likely fail since ICMP data
                        // doesn't contain the full datagram.
                        val packet = bb.slice
                        natKey.transportDst = TCP.getSourcePort(packet)
                        natKey.transportSrc = TCP.getDestinationPort(packet)
                    }
                case _ =>
                    natKey.transportSrc = 0
                    natKey.transportDst = 0
            }
        }

    case class NatKey(var keyType: NatKey.Type,
                      var networkSrc: IPAddr,
                      var transportSrc: Int,
                      var networkDst: IPAddr,
                      var transportDst: Int,
                      var networkProtocol: Byte,
                      var deviceId: UUID) extends FlowStateTag {
        override def toString = s"nat:$keyType:$networkSrc:$transportSrc:" +
                                s"$networkDst:$transportDst:$networkProtocol"

        def returnKey(binding: NatBinding): NatKey = keyType match {
            case NatKey.FWD_SNAT =>
                NatKey(keyType.inverse,
                       networkDst,
                       transportDst,
                       binding.networkAddress,
                       binding.transportPort,
                       networkProtocol,
                       deviceId)
            case NatKey.FWD_DNAT | NatKey.FWD_STICKY_DNAT =>
                NatKey(keyType.inverse,
                       binding.networkAddress,
                       binding.transportPort,
                       networkSrc,
                       transportSrc,
                       networkProtocol,
                       deviceId)
            case _ => throw new UnsupportedOperationException
        }

        def returnBinding: NatBinding = keyType match {
            case NatKey.FWD_SNAT =>
                NatBinding(networkSrc, transportSrc)
            case NatKey.FWD_DNAT | NatKey.FWD_STICKY_DNAT =>
                NatBinding(networkDst, transportDst)
            case _ => throw new UnsupportedOperationException
        }

    }

    case class NatBinding(var networkAddress: IPAddr,
                          var transportPort: Int)
}

trait NatState extends FlowState {
    import org.midonet.midolman.state.NatState._
    import org.midonet.midolman.state.NatState.NatKey._

    var natTx: FlowStateTransaction[NatKey, NatBinding] = _
    private val rand = new Random

    def applyDnat(deviceId: UUID, natTargets: Array[NatTarget]): Boolean =
        applyDnat(deviceId, FWD_DNAT, natTargets)

    def applyStickyDnat(deviceId: UUID, natTargets: Array[NatTarget]): Boolean =
        applyDnat(deviceId, FWD_STICKY_DNAT, natTargets)

    private def applyDnat(deviceId: UUID, natType: NatKey.Type,
                          natTargets: Array[NatTarget]): Boolean =
        if (isNatSupported) {
            val natKey = NatKey(pktCtx.wcmatch, deviceId, natType)
            val binding = getOrAllocateNatBinding(natKey, natTargets)
            dnatTransformation(natKey, binding)
            true
        } else false

    private def dnatTransformation(natKey: NatKey, binding: NatBinding): Unit = {
        pktCtx.wcmatch.setNetworkDestination(binding.networkAddress)
        if (natKey.networkProtocol != ICMP.PROTOCOL_NUMBER)
            pktCtx.wcmatch.setTransportDestination(binding.transportPort)
    }

    def applySnat(deviceId: UUID, natTargets: Array[NatTarget]): Boolean =
        if (isNatSupported) {
            val natKey = NatKey(pktCtx.wcmatch, deviceId, FWD_SNAT)
            val binding = getOrAllocateNatBinding(natKey, natTargets)
            snatTransformation(natKey, binding)
            true
        } else false

    def snatTransformation(natKey: NatKey, binding: NatBinding): Unit = {
        pktCtx.wcmatch.setNetworkSource(binding.networkAddress)
        if (natKey.networkProtocol != ICMP.PROTOCOL_NUMBER)
            pktCtx.wcmatch.setTransportSource(binding.transportPort)
    }

    def reverseDnat(deviceId: UUID): Boolean =
        reverseDnat(deviceId, REV_DNAT)

    def reverseStickyDnat(deviceId: UUID): Boolean =
        reverseDnat(deviceId, REV_STICKY_DNAT)

    private def reverseDnat(deviceId: UUID, natType: NatKey.Type): Boolean = {
        if (isNatSupported) {
            val natKey = NatKey(pktCtx.wcmatch, deviceId, natType)
            val binding = natTx.get(natKey)
            if (binding ne null)
                return reverseDnatTransformation(natKey, binding)
        }
        false
    }

    private def reverseDnatTransformation(natKey: NatKey, binding: NatBinding): Boolean = {
        log.debug("Found reverse DNAT. Use {} for {}", binding, natKey)
        if (isIcmp) {
            if (natKey.keyType ne REV_STICKY_DNAT)
                reverseNatOnICMPData(binding, isSnat = false)
            else false
        } else {
            pktCtx.wcmatch.setNetworkSource(binding.networkAddress)
            pktCtx.wcmatch.setTransportSource(binding.transportPort)
            true
        }
    }

    def reverseSnat(deviceId: UUID): Boolean = {
        if (isNatSupported) {
            val natKey = NatKey(pktCtx.wcmatch, deviceId: UUID, REV_SNAT)
            val binding = natTx.get(natKey)
            if (binding ne null)
                return reverseSnatTransformation(natKey, binding)
        }
        false
    }

    private def reverseSnatTransformation(natKey: NatKey, binding: NatBinding): Boolean = {
        log.debug("Found reverse SNAT. Use {} for {}", binding, natKey)
        if (isIcmp) {
            reverseNatOnICMPData(binding, isSnat = true)
        } else {
            pktCtx.wcmatch.setNetworkDestination(binding.networkAddress)
            pktCtx.wcmatch.setTransportDestination(binding.transportPort)
            true
        }
    }

    def applyIfExists(natKey: NatKey): Boolean = {
        val binding = natTx.get(natKey)
        if (binding eq null) {
            false
        } else natKey.keyType match {
            case NatKey.FWD_DNAT | NatKey.FWD_STICKY_DNAT =>
                dnatTransformation(natKey, binding)
                refKey(natKey, binding)
                true
            case NatKey.REV_DNAT | NatKey.REV_STICKY_DNAT =>
                reverseDnatTransformation(natKey, binding)
            case NatKey.FWD_SNAT =>
                snatTransformation(natKey, binding)
                refKey(natKey, binding)
                true
            case NatKey.REV_SNAT =>
                reverseSnatTransformation(natKey, binding)
        }
    }

    def deleteNatBinding(natKey: NatKey): Unit = {
        val binding = natTx.get(natKey)
        if (binding ne null) {
            pktCtx.addFlowTag(natKey)
            natTx.delete(natKey)
            val returnKey = natKey.returnKey(binding)
            natTx.delete(returnKey)
            pktCtx.addFlowTag(returnKey)
        }
    }

    def isNatSupported: Boolean = {
        val nwProto = pktCtx.wcmatch.getNetworkProtocol
        if (nwProto == ICMP.PROTOCOL_NUMBER) {
            val supported = pktCtx.wcmatch.getTransportSource.byteValue() match {
                case ICMP.TYPE_ECHO_REPLY | ICMP.TYPE_ECHO_REQUEST =>
                    pktCtx.wcmatch.getIcmpIdentifier ne null
                case ICMP.TYPE_PARAMETER_PROBLEM |
                     ICMP.TYPE_TIME_EXCEEDED |
                     ICMP.TYPE_UNREACH =>
                    pktCtx.wcmatch.getIcmpData ne null
                case _ =>
                    false
            }

            if (!supported) {
                log.debug("ICMP message not supported in NAT rules {}",
                          pktCtx.wcmatch)
            }
            supported
        } else {
            nwProto == UDP.PROTOCOL_NUMBER || nwProto == TCP.PROTOCOL_NUMBER
        }
    }

    private def reverseNatOnICMPData(binding: NatBinding, isSnat: Boolean): Boolean =
        pktCtx.wcmatch.getTransportSource.byteValue() match {
            case ICMP.TYPE_ECHO_REPLY | ICMP.TYPE_ECHO_REQUEST =>
                if (isSnat)
                    pktCtx.wcmatch.setNetworkDestination(binding.networkAddress)
                else
                    pktCtx.wcmatch.setNetworkSource(binding.networkAddress)
                true
            case ICMP.TYPE_PARAMETER_PROBLEM | ICMP.TYPE_TIME_EXCEEDED |
                 ICMP.TYPE_UNREACH if pktCtx.wcmatch.getIcmpData ne null =>
                val data = pktCtx.wcmatch.getIcmpData
                val dataSize = data.length
                val bb = ByteBuffer.wrap(data)
                val header = new IPv4
                header.deserializeHeader(bb)
                if (isSnat) {
                    header.setSourceAddress(binding.networkAddress.asInstanceOf[IPv4Addr])
                    pktCtx.wcmatch.setNetworkDestination(binding.networkAddress)
                } else {
                    header.setDestinationAddress(binding.networkAddress.asInstanceOf[IPv4Addr])
                    pktCtx.wcmatch.setNetworkSource(binding.networkAddress)
                }
                val ipHeadSize = dataSize - bb.remaining
                val packet = bb.slice
                var tpSrc = TCP.getSourcePort(packet).toShort
                var tpDst = TCP.getDestinationPort(packet).toShort
                if (header.getProtocol == TCP.PROTOCOL_NUMBER ||
                    header.getProtocol == UDP.PROTOCOL_NUMBER) {
                    if (isSnat)
                        tpSrc = binding.transportPort.toShort
                    else
                        tpDst = binding.transportPort.toShort
                }
                val natBB = ByteBuffer.allocate(data.length)
                natBB.put(header.serialize, 0, ipHeadSize)
                natBB.putShort(tpSrc)
                natBB.putShort(tpDst)
                bb.position(bb.position + 4)
                natBB.put(bb)
                pktCtx.wcmatch.setIcmpData(natBB.array)
                true
            case _ => false
        }

    private def getOrAllocateNatBinding(natKey: NatKey,
                                        natTargets: Array[NatTarget]): NatBinding = {
        var binding = natTx.get(natKey)
        if (binding eq null) {
            binding = tryAllocateNatBinding(natKey, natTargets)
            natTx.putAndRef(natKey, binding)
            log.debug("Obtained NAT key {}->{}", natKey, binding)

            val returnKey = natKey.returnKey(binding)
            val inverseBinding = natKey.returnBinding
            natTx.putAndRef(returnKey, inverseBinding)
            log.debug("With reverse NAT key {}->{}", returnKey, inverseBinding)

            pktCtx.addFlowTag(natKey)
            pktCtx.addFlowTag(returnKey)
        } else {
            log.debug("Found existing mapping for NAT {}->{}", natKey, binding)
            refKey(natKey, binding)
        }
        binding
    }

    private def refKey(natKey: NatKey, binding: NatBinding): Unit = {
        natTx.ref(natKey)
        pktCtx.addFlowTag(natKey)
        val returnKey = natKey.returnKey(binding)
        natTx.ref(returnKey)
        pktCtx.addFlowTag(returnKey)
    }

    private def tryAllocateNatBinding(key: NatKey,
                                      nats: Array[NatTarget]): NatBinding = {
        val nat = chooseRandomNatTarget(nats)
        val port = if (isIcmp) key.transportDst else chooseRandomPort(nat)
        NatBinding(chooseRandomIp(nat), port)
    }

    private def isIcmp = pktCtx.wcmatch.getNetworkProtocol == ICMP.PROTOCOL_NUMBER

    private def chooseRandomNatTarget(nats: Array[NatTarget]): NatTarget =
        nats(rand.nextInt(nats.size))

    private def chooseRandomIp(nat: NatTarget): IPAddr =
        nat.nwStart.randomTo(nat.nwEnd, rand)

    private def chooseRandomPort(nat: NatTarget): Int = {
        val tpStart = nat.tpStart & USHORT
        val tpEnd = nat.tpEnd & USHORT
        rand.nextInt(tpEnd - tpStart + 1) + tpStart
    }
}
