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

import com.google.common.base.Joiner
import com.google.common.io.BaseEncoding
import java.util.{HashSet, List => JList, Map => JMap, Set, UUID}
import org.codehaus.jackson._
import org.codehaus.jackson.map._
import org.codehaus.jackson.map.module.SimpleModule
import scala.collection.JavaConverters._

import Actions._

class JsonSerialization {

    val mapper = new ObjectMapper()

    val flowHistoryModule = new SimpleModule("FlowHistoryModule",
                                             new Version(1, 0, 0, null));
    flowHistoryModule.addSerializer(classOf[FlowRecord],
                                    new JsonFlowRecordSerializer())
    mapper.registerModule(flowHistoryModule)

    def flowRecordToBuffer(record: FlowRecord): Array[Byte] = {
        mapper.writeValueAsBytes(record)
    }

    def bufferToFlowRecord(buffer: Array[Byte]): FlowRecord = {
        implicit val map = mapper.readValue(buffer,
                                            classOf[JMap[String,String]])
        mapToFlowRecord
    }

    private def mapToFlowRecord()(implicit map: JMap[String, String])
            : FlowRecord = {
        FlowRecord(readUUID("hostId"),
                   readUUID("inPort"),
                   readMatch(),
                   readNumber("cookie").toInt,
                   readUUIDList("devices"),
                   readRuleList(),
                   readSimResult(),
                   readUUIDList("outPorts"),
                   readActionList())
    }

    def writeNumber(key: String, value: Long)
                   (implicit jgen: JsonGenerator): Unit = {
        jgen.writeStringField(key, String.valueOf(value))
    }

    def readNumber(key: String)(implicit map: JMap[String, String])
            : Long = {
        if (map.containsKey(key)) {
            java.lang.Long.parseLong(map.get(key))
        } else {
            0L
        }
    }

    def writeBytes(name: String, value: Array[Byte])
                  (implicit jgen: JsonGenerator): Unit = {
        if (value != null) {
            val string = BaseEncoding.base16.encode(value)
            if (string.length > 0) {
                jgen.writeStringField(name, string)
            }
        }
    }

    def readBytes(key: String)(implicit map: JMap[String, String])
            : Array[Byte] = {
        if (map.containsKey(key)) {
            BaseEncoding.base16.decode(map.get(key))
        } else {
            null
        }
    }

    def writeString(key: String, string: String)
                   (implicit jgen: JsonGenerator): Unit = {
        if (string != null) {
            jgen.writeStringField(key, string)
        }
    }

    def readString(key: String)(implicit map: JMap[String, String])
            : String = {
        if (map.containsKey(key)) {
            map.get(key)
        } else {
            null
        }
    }

    def writeUUID(key: String, uuid: UUID)
                 (implicit jgen: JsonGenerator): Unit = {
        if (uuid != null) {
            jgen.writeStringField(key, uuid.toString)
        }
    }

    def readUUID(key: String)
                (implicit map: JMap[String, String]): UUID = {
        if (map.containsKey(key)) {
            UUID.fromString(map.get(key))
        } else {
            null
        }
    }

    def writeShortList(key: String, shorts: JList[java.lang.Short])
                      (implicit jgen: JsonGenerator): Unit = {
        if (shorts != null) {
            val shortStr = Joiner.on("\n").join(shorts)
            jgen.writeStringField(key, shortStr)
        }
    }

    def readShortList(key: String)
                     (implicit map: JMap[String, String]): JList[java.lang.Short] = {
        if (map.containsKey(key)) {
            map.get(key).split("\n").toList
                .filter(_.length() > 0)
                .map(x => java.lang.Short.valueOf(x)).asJava
        } else {
            null
        }
    }

    def writeUUIDList(key: String, uuids: JList[UUID])
                     (implicit jgen: JsonGenerator): Unit = {
        if (uuids != null) {
            val uuidStr = Joiner.on("\n").join(uuids)
            jgen.writeStringField(key, uuidStr)
        }
    }

    def readUUIDList(key: String)
                    (implicit map: JMap[String, String]): JList[UUID] = {
        if (map.containsKey(key)) {
            map.get(key).split("\n").toList
                .filter(_.length > 0)
                .map(x => UUID.fromString(x)).asJava
        } else {
            null
        }
    }

    def writeMatch(fmatch: FlowRecordMatch)
                  (implicit jgen: JsonGenerator): Unit = {
        writeNumber("flowMatch.inputPort", fmatch.inputPortNo)
        writeNumber("flowMatch.tunnelKey", fmatch.tunnelKey)
        writeNumber("flowMatch.tunnelSrc", fmatch.tunnelSrc)
        writeNumber("flowMatch.tunnelDst", fmatch.tunnelDst)
        writeBytes("flowMatch.ethSrc", fmatch.ethSrc)
        writeBytes("flowMatch.ethDst", fmatch.ethDst)
        writeNumber("flowMatch.etherType", fmatch.etherType)
        writeBytes("flowMatch.networkSrc", fmatch.networkSrc)
        writeBytes("flowMatch.networkDst", fmatch.networkDst)
        writeNumber("flowMatch.networkProto", fmatch.networkProto)
        writeNumber("flowMatch.networkTTL", fmatch.networkTTL)
        writeNumber("flowMatch.networkTOS", fmatch.networkTOS)
        writeNumber("flowMatch.IPFragType", fmatch.ipFragType)
        writeNumber("flowMatch.srcPort", fmatch.srcPort)
        writeNumber("flowMatch.dstPort", fmatch.dstPort)
        writeNumber("flowMatch.icmpIdentifier", fmatch.icmpId)
        writeBytes("flowMatch.icmpData", fmatch.icmpData)
        writeShortList("flowMatch.vlanIds", fmatch.vlanIds)
    }

    private def readMatch()(implicit map: JMap[String, String]): FlowRecordMatch = {
        FlowRecordMatch(readNumber("flowMatch.inputPort").toInt,
                        readNumber("flowMatch.tunnelKey").toLong,
                        readNumber("flowMatch.tunnelSrc").toInt,
                        readNumber("flowMatch.tunnelDst").toInt,
                        readBytes("flowMatch.ethSrc"),
                        readBytes("flowMatch.ethDst"),
                        readNumber("flowMatch.etherType").toShort,
                        readBytes("flowMatch.networkSrc"),
                        readBytes("flowMatch.networkDst"),
                        readNumber("flowMatch.networkProto").toByte,
                        readNumber("flowMatch.networkTTL").toByte,
                        readNumber("flowMatch.networkTOS").toByte,
                        readNumber("flowMatch.IPFragType").toByte,
                        readNumber("flowMatch.srcPort").toInt,
                        readNumber("flowMatch.dstPort").toInt,
                        readNumber("flowMatch.icmpIdentifier").toShort,
                        readBytes("flowMatch.icmpData"),
                        readShortList("flowMatch.vlanIds"))
    }

    def writeActionList(actions: Set[FlowAction])
                       (implicit jgen: JsonGenerator): Unit = {
        if (actions != null) {
            val iter = actions.iterator
            while (iter.hasNext()) {
                iter.next() match {
                    case a: Output =>
                        writeNumber("action.output.portno", a.portNo)
                    case a: PopVlan =>
                        jgen.writeStringField("action.popVlan", "true")
                    case a: PushVlan =>
                        writeNumber("action.pushVlan.tpid", a.tpid)
                        writeNumber("action.pushVlan.tci", a.tci)
                    case a: Userspace =>
                        writeNumber("action.userspace.uplinkId", a.uplinkId)
                        writeNumber("action.userspace.userdata", a.userData)
                    case a: Arp =>
                        writeNumber("action.arp.sip", a.sip)
                        writeNumber("action.arp.tip", a.tip)
                        writeNumber("action.arp.op", a.op)
                        writeBytes("action.arp.sha", a.sha)
                        writeBytes("action.arp.tha", a.tha)
                    case a: Ethernet =>
                        writeBytes("action.eth.src", a.src)
                        writeBytes("action.eth.dst", a.dst)
                    case a: EtherType =>
                        writeNumber("action.ethertype.type", a.`type`)
                    case a: IcmpEcho =>
                        writeNumber("action.icmp.echo.type", a.`type`)
                        writeNumber("action.icmp.echo.code", a.`code`)
                        writeNumber("action.icmp.echo.id", a.id)
                    case a: IcmpError =>
                        writeNumber("action.icmp.error.type", a.`type`)
                        writeNumber("action.icmp.error.code", a.code)
                        writeBytes("action.icmp.error.id", a.data)
                    case a: Icmp =>
                        writeNumber("action.icmp.type", a.`type`)
                        writeNumber("action.icmp.code", a.code)
                    case a: IPv4 =>
                        writeNumber("action.ipv4.src", a.src)
                        writeNumber("action.ipv4.dst", a.dst)
                        writeNumber("action.ipv4.proto", a.proto)
                        writeNumber("action.ipv4.tos", a.tos)
                        writeNumber("action.ipv4.ttl", a.ttl)
                        writeNumber("action.ipv4.frag", a.frag)
                    case a: TCP =>
                        writeNumber("action.tcp.src", a.src)
                        writeNumber("action.tcp.dst", a.dst)
                    case a: Tunnel =>
                        writeNumber("action.tunnel.id", a.id)
                        writeNumber("action.tunnel.ipv4_src", a.ipv4_src)
                        writeNumber("action.tunnel.ipv4_dst", a.ipv4_dst)
                        writeNumber("action.tunnel.flags", a.flags)
                        writeNumber("action.tunnel.ipv4_tos", a.ipv4_tos)
                        writeNumber("action.tunnel.ipv4_ttl", a.ipv4_ttl)
                    case a: UDP =>
                        writeNumber("action.udp.src", a.src)
                        writeNumber("action.udp.dst", a.dst)
                    case a: VLan =>
                        writeNumber("action.vlan.id", a.id)
                    case _ =>
                }
            }
        }
    }

    private def readActionList()
                              (implicit map: JMap[String, String]): Set[FlowAction] = {
        val actions = new HashSet[FlowAction]
        if (map.containsKey("action.output.portno")) {
            actions.add(Output(readNumber("action.output.portno").toInt))
        }
        if (map.containsKey("action.popVlan")) {
            actions.add(PopVlan())
        }
        if (map.containsKey("action.pushVlan.tpid")) {
            actions.add(PushVlan(readNumber("action.pushVlan.tpid").toShort,
                                 readNumber("action.pushVlan.tci").toShort))
        }
        if (map.containsKey("action.userspace.uplinkId")) {
            actions.add(Userspace(readNumber("action.userspace.uplinkId").toInt,
                                  readNumber("action.userspace.userdata")))
        }
        if (map.containsKey("action.arp.sip")) {
            actions.add(Arp(readNumber("action.arp.sip").toInt,
                            readNumber("action.arp.tip").toInt,
                            readNumber("action.arp.op").toShort,
                            readBytes("action.arp.sha"),
                            readBytes("action.arp.tha")))
        }
        if (map.containsKey("action.eth.src")) {
            actions.add(Ethernet(readBytes("action.eth.src"),
                                 readBytes("action.eth.dst")))
        }
        if (map.containsKey("action.ethertype.type")) {
            actions.add(EtherType(readNumber("action.ethertype.type").toShort))
        }
        if (map.containsKey("action.icmp.echo.type")) {
            actions.add(IcmpEcho(readNumber("action.icmp.echo.type").toByte,
                                 readNumber("action.icmp.echo.code").toByte,
                                 readNumber("action.icmp.echo.id").toShort))
        }
        if (map.containsKey("action.icmp.error.type")) {
            actions.add(IcmpError(readNumber("action.icmp.error.type").toByte,
                                  readNumber("action.icmp.error.code").toByte,
                                  readBytes("action.icmp.error.id")))
        }
        if (map.containsKey("action.icmp.type")) {
            actions.add(Icmp(readNumber("action.icmp.type").toByte,
                             readNumber("action.icmp.code").toByte))
        }
        if (map.containsKey("action.ipv4.src")) {
            actions.add(IPv4(readNumber("action.ipv4.src").toInt,
                             readNumber("action.ipv4.dst").toInt,
                             readNumber("action.ipv4.proto").toByte,
                             readNumber("action.ipv4.tos").toByte,
                             readNumber("action.ipv4.ttl").toByte,
                             readNumber("action.ipv4.frag").toByte))
        }
        if (map.containsKey("action.tcp.src")) {
            actions.add(TCP(readNumber("action.tcp.src").toShort,
                            readNumber("action.tcp.dst").toShort))
        }
        if (map.containsKey("action.tunnel.id")) {
            actions.add(Tunnel(readNumber("action.tunnel.id").toInt,
                               readNumber("action.tunnel.ipv4_src").toInt,
                               readNumber("action.tunnel.ipv4_dst").toInt,
                               readNumber("action.tunnel.flags").toShort,
                               readNumber("action.tunnel.ipv4_tos").toByte,
                               readNumber("action.tunnel.ipv4_ttl").toByte))
        }
        if (map.containsKey("action.udp.src")) {
            actions.add(UDP(readNumber("action.udp.src").toShort,
                            readNumber("action.udp.dst").toShort))
        }
        if (map.containsKey("action.vlan.id")) {
            actions.add(VLan(readNumber("action.vlan.id").toShort))
        }
        actions
    }

    def writeSimResult(simResult: SimulationResult.SimulationResult)
                      (implicit jgen: JsonGenerator): Unit = {
        if (simResult != null) {
            jgen.writeStringField("simResult", simResult.toString)
        }
    }

    def readSimResult()
                     (implicit map: JMap[String, String])
            : SimulationResult.SimulationResult = {
        if (map.containsKey("simResult")) {
            SimulationResult.withName(map.get("simResult"))
        } else {
            null
        }
    }

    def writeRuleList(rules: JList[TraversedRule])
                     (implicit jgen: JsonGenerator): Unit = {
        if (rules != null) {
            val rulesStr = Joiner.on("\n").join(
                rules.asScala.map(
                    r => { s"${r.rule} (${r.result})" }).asJava)
            jgen.writeStringField("rules", rulesStr)
        }
    }

    def readRuleList()
                    (implicit map: JMap[String, String]): JList[TraversedRule] = {
        if (map.containsKey("rules")) {
            map.get("rules").split("\n").toList
                .filter(_.length > 0)
                .map(x => {
                    val parts = x.split(" ")
                    TraversedRule(UUID.fromString(parts(0)),
                                  RuleResult.withName(parts(1).replace(")", "")
                                                          .replace("(", "")))
                }
            ).asJava
        } else {
            null
        }
    }

    class JsonFlowRecordSerializer extends JsonSerializer[FlowRecord] {
        override def serialize(record: FlowRecord,
                               jgen: JsonGenerator,
                               provider: SerializerProvider): Unit = {
            implicit val gen = jgen
            jgen.writeStartObject()
            writeString("type", "flowhistory")
            writeUUID("hostId", record.host)
            writeUUID("inPort", record.inPort)
            writeMatch(record.flowMatch)
            writeNumber("cookie", record.cookie)
            writeUUIDList("devices", record.devices)
            writeRuleList(record.rules)
            writeSimResult(record.simResult)
            writeUUIDList("outPorts", record.outPorts)
            writeActionList(record.actions)
            jgen.writeEndObject()
        }
    }
}
