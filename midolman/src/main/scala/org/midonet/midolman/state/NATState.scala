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

package org.midonet.midolman.state

import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

import scala.concurrent.duration._

import org.midonet.midolman.rules.NatTarget
import org.midonet.midolman.simulation.PacketContext
import org.midonet.midolman.state.FlowState.FlowStateKey
import org.midonet.odp.FlowMatch
import org.midonet.odp.FlowMatch.Field
import org.midonet.packets._
import org.midonet.sdn.state.FlowStateTransaction

object NatState {
    private val WILDCARD_PORT = 0

    sealed abstract class KeyType {
        def inverse: KeyType
    }

    case object FWD_SNAT extends KeyType {
        def inverse = REV_SNAT
    }
    case object FWD_DNAT extends KeyType {
        def inverse = REV_DNAT
    }
    case object FWD_STICKY_DNAT extends KeyType {
        def inverse = REV_STICKY_DNAT
    }
    case object REV_SNAT extends KeyType {
        def inverse = FWD_SNAT
    }
    case object REV_DNAT extends KeyType {
        def inverse = FWD_DNAT
    }
    case object REV_STICKY_DNAT extends KeyType {
        def inverse = FWD_STICKY_DNAT
    }

    object NatKey {
        final val USHORT = 0xffff // Constant used to prevent sign extension

        def apply(wcMatch: FlowMatch, deviceId: UUID, keyType: KeyType): NatKey = {
            val key = NatKey(keyType,
                             wcMatch.getNetworkSrcIP.asInstanceOf[IPv4Addr],
                             if (keyType eq FWD_STICKY_DNAT) WILDCARD_PORT
                             else wcMatch.getSrcPort,
                             wcMatch.getNetworkDstIP.asInstanceOf[IPv4Addr],
                             if (keyType eq REV_STICKY_DNAT) WILDCARD_PORT
                             else wcMatch.getDstPort,
                             wcMatch.getNetworkProto.byteValue(),
                             deviceId)

            if (wcMatch.getNetworkProto == ICMP.PROTOCOL_NUMBER)
                processIcmp(key, wcMatch)

            key
        }

        private def processIcmp(natKey: NatKey, wcMatch: FlowMatch): Unit =
            wcMatch.getSrcPort.byteValue() match {
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
                    // Invert src and dst because the icmpData contains the
                    // original msg that the ICMP ERROR replies to.
                    natKey.networkSrc = ipv4.getDestinationIPAddress
                    natKey.networkDst = ipv4.getSourceIPAddress
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
                        val packet = bb.slice
                        natKey.transportDst = TCP.getSourcePort(packet)
                        natKey.transportSrc = TCP.getDestinationPort(packet)
                    }
                case _ =>
                    natKey.transportSrc = 0
                    natKey.transportDst = 0
            }
        }

    case class NatKey(var keyType: KeyType,
                      var networkSrc: IPv4Addr,
                      var transportSrc: Int,
                      var networkDst: IPv4Addr,
                      var transportDst: Int,
                      var networkProtocol: Byte,
                      var deviceId: UUID) extends FlowStateKey {
        override def toString = s"nat:$keyType:$networkSrc:$transportSrc:" +
                                s"$networkDst:$transportDst:$networkProtocol"

        expiresAfter = keyType match {
            case FWD_STICKY_DNAT | REV_STICKY_DNAT => 1 day
            case _ => FlowState.DEFAULT_EXPIRATION
        }

        def returnKey(binding: NatBinding): NatKey = keyType match {
            case NatState.FWD_SNAT =>
                NatKey(keyType.inverse,
                       networkDst,
                       transportDst,
                       binding.networkAddress,
                       binding.transportPort,
                       networkProtocol,
                       deviceId)
            case NatState.FWD_DNAT | NatState.FWD_STICKY_DNAT =>
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
            case NatState.FWD_SNAT =>
                NatBinding(networkSrc, transportSrc)
            case NatState.FWD_DNAT | NatState.FWD_STICKY_DNAT =>
                NatBinding(networkDst, transportDst)
            case _ => throw new UnsupportedOperationException
        }

    }

    case class NatBinding(var networkAddress: IPv4Addr, var transportPort: Int)

    def releaseBinding(key: NatKey, binding: NatBinding, natLeaser: NatLeaser): Unit =
        if ((key.keyType eq NatState.FWD_SNAT) &&
            key.networkProtocol != ICMP.PROTOCOL_NUMBER) {
                natLeaser.freeNatBinding(key.deviceId, key.networkDst,
                                         key.transportDst, binding)
        }
}

trait NatState extends FlowState { this: PacketContext =>
    import org.midonet.midolman.state.NatState._
    import org.midonet.midolman.state.NatState.NatKey._

    var natTx: FlowStateTransaction[NatKey, NatBinding] = _
    var natLeaser: NatLeaser = _

    def applyDnat(deviceId: UUID, natTargets: Array[NatTarget]): Boolean =
        applyDnat(deviceId, FWD_DNAT, natTargets)

    def applyStickyDnat(deviceId: UUID, natTargets: Array[NatTarget]): Boolean =
        applyDnat(deviceId, FWD_STICKY_DNAT, natTargets)

    private def applyDnat(deviceId: UUID, natType: KeyType,
                          natTargets: Array[NatTarget]): Boolean =
        if (isNatSupported) {
            val natKey = NatKey(wcmatch, deviceId, natType)
            val binding = getOrAllocateNatBinding(natKey, natTargets)
            dnatTransformation(natKey, binding)
            true
        } else false

    private def dnatTransformation(natKey: NatKey, binding: NatBinding): Unit = {
        wcmatch.setNetworkDst(binding.networkAddress)
        if (!isIcmp)
            wcmatch.setDstPort(binding.transportPort)
    }

    def applySnat(deviceId: UUID, natTargets: Array[NatTarget]): Boolean =
        if (isNatSupported) {
            val natKey = NatKey(wcmatch, deviceId, FWD_SNAT)
            val binding = getOrAllocateNatBinding(natKey, natTargets)
            snatTransformation(natKey, binding)
            true
        } else false

    private def snatTransformation(natKey: NatKey, binding: NatBinding): Unit = {
        wcmatch.setNetworkSrc(binding.networkAddress)
        if (!isIcmp)
            wcmatch.setSrcPort(binding.transportPort)
    }

    def reverseDnat(deviceId: UUID): Boolean =
        reverseDnat(deviceId, REV_DNAT)

    def reverseStickyDnat(deviceId: UUID): Boolean =
        reverseDnat(deviceId, REV_STICKY_DNAT)

    private def reverseDnat(deviceId: UUID, natType: KeyType): Boolean = {
        if (isNatSupported) {
            val natKey = NatKey(wcmatch, deviceId, natType)
            addFlowTag(natKey)
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
                reverseNatOnICMPData(natKey, binding, isSnat = false)
            else false
        } else {
            wcmatch.setNetworkSrc(binding.networkAddress)
            wcmatch.setSrcPort(binding.transportPort)
            true
        }
    }

    def reverseSnat(deviceId: UUID): Boolean = {
        if (isNatSupported) {
            val natKey = NatKey(wcmatch, deviceId: UUID, REV_SNAT)
            addFlowTag(natKey)
            val binding = natTx.get(natKey)
            if (binding ne null)
                return reverseSnatTransformation(natKey, binding)
        }
        false
    }

    private def reverseSnatTransformation(natKey: NatKey, binding: NatBinding): Boolean = {
        log.debug("Found reverse SNAT. Use {} for {}", binding, natKey)
        if (isIcmp) {
            reverseNatOnICMPData(natKey, binding, isSnat = true)
        } else {
            wcmatch.setNetworkDst(binding.networkAddress)
            wcmatch.setDstPort(binding.transportPort)
            true
        }
    }

    def applyIfExists(natKey: NatKey): Boolean = {
        val binding = natTx.get(natKey)
        if (binding eq null) {
            addFlowTag(natKey)
            false
        } else natKey.keyType match {
            case NatState.FWD_DNAT | NatState.FWD_STICKY_DNAT =>
                dnatTransformation(natKey, binding)
                refKey(natKey, binding)
                true
            case NatState.REV_DNAT | NatState.REV_STICKY_DNAT =>
                reverseDnatTransformation(natKey, binding)
            case NatState.FWD_SNAT =>
                snatTransformation(natKey, binding)
                refKey(natKey, binding)
                true
            case NatState.REV_SNAT =>
                reverseSnatTransformation(natKey, binding)
        }
    }

    def deleteNatBinding(natKey: NatKey): Unit = {
        val binding = natTx.get(natKey)
        if (binding ne null) {
            addFlowTag(natKey)
            natTx.remove(natKey)
            val returnKey = natKey.returnKey(binding)
            natTx.remove(returnKey)
            addFlowTag(returnKey)
        }
    }

    def isNatSupported: Boolean =
        if (IPv4.ETHERTYPE == wcmatch.getEtherType) {
            val nwProto = wcmatch.getNetworkProto
            if (nwProto == ICMP.PROTOCOL_NUMBER) {
                val supported = wcmatch.getSrcPort.byteValue() match {
                    case ICMP.TYPE_ECHO_REPLY | ICMP.TYPE_ECHO_REQUEST =>
                        wcmatch.isUsed(Field.IcmpId)
                    case ICMP.TYPE_PARAMETER_PROBLEM |
                         ICMP.TYPE_TIME_EXCEEDED |
                         ICMP.TYPE_UNREACH =>
                        wcmatch.isUsed(Field.IcmpData)
                    case _ =>
                        false
                }

                if (!supported) {
                    log.debug("ICMP message not supported in NAT rules {}",
                              wcmatch)
                }
                supported
            } else {
                nwProto == UDP.PROTOCOL_NUMBER || nwProto == TCP.PROTOCOL_NUMBER
            }
        } else false

    private def reverseNatOnICMPData(
            natKey: NatKey,
            binding: NatBinding,
            isSnat: Boolean): Boolean =
        wcmatch.getSrcPort.byteValue() match {
            case ICMP.TYPE_ECHO_REPLY | ICMP.TYPE_ECHO_REQUEST =>
                if (isSnat)
                    wcmatch.setNetworkDst(binding.networkAddress)
                else
                    wcmatch.setNetworkSrc(binding.networkAddress)
                true
            case ICMP.TYPE_PARAMETER_PROBLEM | ICMP.TYPE_TIME_EXCEEDED |
                 ICMP.TYPE_UNREACH if wcmatch.getIcmpData ne null =>
                val data = wcmatch.getIcmpData
                val bb = ByteBuffer.wrap(data)
                val icmpPayload = new IPv4
                icmpPayload.deserialize(bb)
                if (isSnat) {
                    icmpPayload.setSourceAddress(binding.networkAddress)
                    if (wcmatch.getNetworkDstIP == natKey.networkDst)
                        wcmatch.setNetworkDst(binding.networkAddress)
                } else {
                    icmpPayload.setDestinationAddress(binding.networkAddress)
                    if (wcmatch.getNetworkSrcIP == natKey.networkSrc)
                        wcmatch.setNetworkSrc(binding.networkAddress)
                }
                icmpPayload.getPayload match {
                    case transport: Transport =>
                        if (isSnat)
                            transport.setSourcePort(binding.transportPort)
                        else
                            transport.setDestinationPort(binding.transportPort.toShort)
                        transport.clearChecksum()
                    case _ =>
                }
                icmpPayload.clearChecksum()
                wcmatch.setIcmpData(icmpPayload.serialize())
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

            addFlowTag(natKey)
            addFlowTag(returnKey)
        } else {
            log.debug("Found existing mapping for NAT {}->{}", natKey, binding)
            refKey(natKey, binding)
        }
        binding
    }

    private def refKey(natKey: NatKey, binding: NatBinding): Unit = {
        natTx.ref(natKey)
        addFlowTag(natKey)
        val returnKey = natKey.returnKey(binding)
        natTx.ref(returnKey)
        addFlowTag(returnKey)
    }

    private def tryAllocateNatBinding(key: NatKey,
                                      nats: Array[NatTarget]): NatBinding =
        if (isIcmp) {
            val nat = chooseRandomNatTarget(nats)
            NatBinding(chooseRandomIp(nat), key.transportDst)
        } else if (key.keyType eq FWD_SNAT) {
            natLeaser.allocateNatBinding(key.deviceId, key.networkDst,
                                         key.transportDst, nats)
        } else {
            val nat = chooseRandomNatTarget(nats)
            NatBinding(chooseRandomIp(nat), chooseRandomPort(nat))
        }

    private def isIcmp = wcmatch.getNetworkProto == ICMP.PROTOCOL_NUMBER

    private def chooseRandomNatTarget(nats: Array[NatTarget]): NatTarget =
        nats(ThreadLocalRandom.current().nextInt(nats.size))

    private def chooseRandomIp(nat: NatTarget): IPv4Addr =
        nat.nwStart.randomTo(nat.nwEnd, ThreadLocalRandom.current())

    private def chooseRandomPort(nat: NatTarget): Int = {
        val tpStart = nat.tpStart
        val tpEnd = nat.tpEnd
        ThreadLocalRandom.current().nextInt(tpEnd - tpStart + 1) + tpStart
    }
}
