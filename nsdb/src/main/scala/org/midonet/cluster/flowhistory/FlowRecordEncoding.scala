/*
 * Copyright 2015 Midokura SARL
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
package org.midonet.cluster.flowhistory

import java.util.{ArrayList, Arrays, Collections, HashSet}
import java.util.{List, Objects, Random, Set, UUID}

object RuleResult extends Enumeration {
    type RuleResult = Value
    val ACCEPT, CONTINUE, DROP, JUMP, REJECT, RETURN, UNKNOWN = Value
}

object SimulationResult extends Enumeration {
    type SimulationResult = Value
    val NOOP, DROP, TEMP_DROP, SEND_PACKET, ADD_VIRTUAL_WILDCARD_FLOW,
        STATE_MESSAGE, USERSPACE_FLOW, FLOW_CREATED, DUPE_FLOW,
        GENERATED_PACKET, UNKNOWN = Value
}

object Actions {
    sealed trait FlowAction
    case class Output(portNo: Int) extends FlowAction
    case class PopVlan() extends FlowAction
    case class PushVlan(tpid: Short, tci: Short) extends FlowAction
    case class Userspace(uplinkId: Int, userData: Long) extends FlowAction
    case class Arp(sip: Int, tip: Int, op: Short,
                   sha: Array[Byte], tha: Array[Byte]) extends FlowAction {
        override def hashCode(): Int =
            ((((((sip * 31) + tip) * 31) + op) * 31) +
                 Arrays.hashCode(sha)) * 31 + Arrays.hashCode(tha)

        override def equals(obj: Any): Boolean = obj match {
            case other: Arp => Objects.equals(sip, other.sip) &&
                Objects.equals(tip, other.tip) &&
                Objects.equals(op, other.op) &&
                Arrays.equals(sha, other.sha) &&
                Arrays.equals(tha, other.tha)
            case _ => false
        }
    }
    case class Ethernet(src: Array[Byte], dst: Array[Byte]) extends FlowAction {
        override def hashCode(): Int =
            Arrays.hashCode(src) * 31 + Arrays.hashCode(dst)
        override def equals(obj: Any): Boolean = obj match {
            case other: Ethernet => Arrays.equals(src, other.src) &&
                Arrays.equals(dst, other.dst)
            case _ => false
        }
    }
    case class EtherType(`type`: Short) extends FlowAction
    case class IcmpEcho(`type`: Byte, code: Byte, id: Short) extends FlowAction
    case class IcmpError(`type`: Byte, code: Byte,
                         data: Array[Byte]) extends FlowAction {
        override def hashCode(): Int =
            (((`type` * 31) + code) * 31) + Arrays.hashCode(data)

        override def equals(obj: Any): Boolean = obj match {
            case other: IcmpError => Objects.equals(`type`, other.`type`) &&
                Objects.equals(code, other.code) &&
                Arrays.equals(data, other.data)
            case _ => false
        }
    }
    case class Icmp(`type`: Byte, code: Byte) extends FlowAction
    case class IPv4(src: Int, dst: Int, proto: Byte,
                    tos: Byte, ttl: Byte, frag: Byte) extends FlowAction
    case class TCP(src: Short, dst: Short) extends FlowAction
    case class Tunnel(id: Int, ipv4_src: Int, ipv4_dst: Int,
                      flags: Short, ipv4_tos: Byte,
                      ipv4_ttl: Byte) extends FlowAction
    case class UDP(src: Short, dst: Short) extends FlowAction
    case class VLan(id: Short) extends FlowAction
}

case class TraversedRule(rule: UUID, result: RuleResult.RuleResult)

case class FlowRecordMatch(inputPortNo: Int, tunnelKey: Long,
                           tunnelSrc: Int, tunnelDst: Int,
                           ethSrc: Array[Byte], ethDst: Array[Byte],
                           etherType: Short, networkSrc: Array[Byte],
                           networkDst: Array[Byte], networkProto: Byte,
                           networkTTL: Byte, networkTOS: Byte,
                           ipFragType: Byte, srcPort: Int,
                           dstPort: Int, icmpId: Short,
                           icmpData: Array[Byte],
                           vlanIds: List[java.lang.Short]) {
    override def equals(obj: Any): Boolean = obj match {
        case other: FlowRecordMatch =>
            Objects.equals(inputPortNo, other.inputPortNo) &&
            Objects.equals(tunnelKey, other.tunnelKey) &&
            Objects.equals(tunnelSrc, other.tunnelSrc) &&
            Objects.equals(tunnelDst, other.tunnelDst) &&
            Arrays.equals(ethSrc, other.ethSrc) &&
            Arrays.equals(ethDst, other.ethDst) &&
            Objects.equals(etherType, other.etherType) &&
            Arrays.equals(networkSrc, other.networkSrc) &&
            Arrays.equals(networkDst, other.networkDst) &&
            Objects.equals(networkProto, other.networkProto) &&
            Objects.equals(networkTTL, other.networkTTL) &&
            Objects.equals(networkTOS, other.networkTOS) &&
            Objects.equals(ipFragType, other.ipFragType) &&
            Objects.equals(srcPort, other.srcPort) &&
            Objects.equals(dstPort, other.dstPort) &&
            Objects.equals(icmpId, other.icmpId) &&
            Arrays.equals(icmpData, other.icmpData) &&
            Objects.deepEquals(vlanIds, other.vlanIds)
        case _ => false
    }

}

case class FlowRecord(host: UUID, inPort: UUID,
                      flowMatch: FlowRecordMatch,
                      cookie: Int,
                      devices: List[UUID],
                      rules: List[TraversedRule],
                      simResult: SimulationResult.SimulationResult,
                      outPorts: List[UUID],
                      actions: Set[Actions.FlowAction]) {
}


object FlowRecord {
    import Actions._

    def random(): FlowRecord = {
        implicit val r = new Random

        import SimulationResult._
        val simRes = Arrays.asList(NOOP, DROP, TEMP_DROP, SEND_PACKET,
                                   ADD_VIRTUAL_WILDCARD_FLOW, STATE_MESSAGE,
                                   USERSPACE_FLOW, FLOW_CREATED, DUPE_FLOW,
                                   GENERATED_PACKET)
        Collections.shuffle(simRes)
        FlowRecord(UUID.randomUUID, UUID.randomUUID, randomMatch(r),
                   r.nextInt(), randomUUIDS(r),
                   randomRules(r), simRes.get(0),
                   randomUUIDS(r), randomActions(r))
    }

    private def randomMatch(r: Random): FlowRecordMatch = {
        val ipBytes = new Array[Byte](4)
        val macBytes = new Array[Byte](6)
        r.nextBytes(ipBytes)
        r.nextBytes(macBytes)
        val vlans = new ArrayList[java.lang.Short]
        for (i <- 0 until r.nextInt(5)) {
            vlans.add(r.nextInt().toShort)
        }

        FlowRecordMatch(r.nextInt(), r.nextLong(), r.nextInt(), r.nextInt(),
                        if (r.nextBoolean()) { macBytes } else { null },
                        if (r.nextBoolean()) { macBytes } else { null },
                        r.nextInt().toShort,
                        if (r.nextBoolean()) { ipBytes } else { null },
                        if (r.nextBoolean()) { ipBytes } else { null },
                        r.nextInt().toByte, r.nextInt().toByte,
                        r.nextInt().toByte, r.nextInt().toByte,
                        r.nextInt(), r.nextInt(),
                        r.nextInt().toShort,
                        if (r.nextBoolean()) { macBytes } else { null },
                        vlans)
    }

    private def randomActions(r: Random): Set[FlowAction] = {
        val ipBytes = new Array[Byte](4)
        val macBytes = new Array[Byte](6)
        r.nextBytes(ipBytes)
        r.nextBytes(macBytes)
        val randActions = Arrays.asList(
            Output(r.nextInt()), PopVlan(),
            PushVlan(r.nextInt().toShort, r.nextInt().toShort),
            Userspace(r.nextInt(), r.nextInt()),
            Arp(r.nextInt(), r.nextInt(), r.nextInt().toShort,
                ipBytes, ipBytes),
            Ethernet(macBytes, macBytes),
            EtherType(r.nextInt().toShort),
            IcmpEcho(r.nextInt().toByte, r.nextInt().toByte,
                     r.nextInt().toShort),
            IcmpError(r.nextInt().toByte, r.nextInt().toByte,
                      macBytes),
            Icmp(r.nextInt().toByte, r.nextInt().toByte),
            IPv4(r.nextInt(), r.nextInt(), r.nextInt().toByte,
                 r.nextInt().toByte, r.nextInt().toByte,
                 r.nextInt().toByte),
            TCP(r.nextInt().toShort, r.nextInt().toShort),
            Tunnel(r.nextInt(), r.nextInt(), r.nextInt(),
                   r.nextInt().toShort, r.nextInt().toByte,
                   r.nextInt().toByte),
            UDP(r.nextInt().toShort, r.nextInt().toShort),
            VLan(r.nextInt().toShort))
        Collections.shuffle(randActions)
        val count = r.nextInt(randActions.size)
        val actions = new HashSet[FlowAction]
        for (i <- 0 until count) {
            actions.add(randActions.get(i))
        }
        actions
    }

    private def randomRules(r: Random): List[TraversedRule] = {
        import RuleResult._
        val results = Arrays.asList(ACCEPT, CONTINUE, DROP, JUMP, REJECT, RETURN)
        val count = r.nextInt() % 20
        if (count < 0) {
            null
        } else {
            val rules = new ArrayList[TraversedRule]
            for (i <- 0 until count) {
                rules.add(TraversedRule(UUID.randomUUID,
                                        results.get(r.nextInt(results.size))))
            }
            rules
        }
    }

    private def randomUUIDS(r: Random): List[UUID] = {
        val count = r.nextInt() % 20
        if (count < 0) {
            null
        } else {
            val devices = new ArrayList[UUID]
            for (i <- 0 until count) {
                devices.add(UUID.randomUUID)
            }
            devices
        }
    }
}
