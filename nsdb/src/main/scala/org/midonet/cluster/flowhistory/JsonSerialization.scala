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

import java.util.{ArrayList => JArrayList, List => JList, Map => JMap, UUID}

import scala.collection.JavaConverters._

import com.fasterxml.jackson.core.{JsonGenerator, Version}
import com.fasterxml.jackson.databind.{JsonSerializer, SerializerProvider, ObjectMapper}
import com.fasterxml.jackson.databind.module.SimpleModule
import com.google.common.base.Joiner
import com.google.common.io.BaseEncoding

import org.midonet.cluster.flowhistory.Actions._

class JsonSerialization {

    val mapper = new ObjectMapper()

    val flowHistoryModule = new SimpleModule("FlowHistoryModule",
                                             new Version(1, 0, 0, null, null, null))
    flowHistoryModule.addSerializer(classOf[FlowRecord],
                                    new JsonFlowRecordSerializer())
    mapper.registerModule(flowHistoryModule)

    def flowRecordToBuffer(record: FlowRecord): Array[Byte] = {
        mapper.writeValueAsBytes(record)
    }

    def bufferToFlowRecord(buffer: Array[Byte]): FlowRecord = {
        implicit val map = mapper.readValue(buffer,
                                            classOf[JMap[String,Object]])
        mapToFlowRecord
    }

    private def mapToFlowRecord()(implicit map: JMap[String, Object])
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

    def readNumber(key: String)(implicit map: JMap[String, Object]): Long = {
        map.get(key) match {
            case s: String => java.lang.Long.parseLong(s)
            case _ => 0L
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

    def readBytes(key: String)(implicit map: JMap[String, Object])
            : Array[Byte] = {
        map.get(key) match {
            case s: String => BaseEncoding.base16.decode(s)
            case _ => null
        }
    }

    def writeString(key: String, string: String)
                   (implicit jgen: JsonGenerator): Unit = {
        if (string != null) {
            jgen.writeStringField(key, string)
        }
    }

    def readString(key: String)(implicit map: JMap[String, Object])
            : String = {
        map.get(key) match {
            case s: String => s
            case _ => null
        }
    }

    def writeUUID(key: String, uuid: UUID)
                 (implicit jgen: JsonGenerator): Unit = {
        if (uuid != null) {
            jgen.writeStringField(key, uuid.toString)
        }
    }

    def readUUID(key: String)
                (implicit map: JMap[String, Object]): UUID = {
        map.get(key) match {
            case s: String => UUID.fromString(s)
            case _ => null
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
                     (implicit map: JMap[String, Object]): JList[java.lang.Short] = {
        map.get(key) match {
            case s: String => s.split("\n").toList
                    .filter(_.length() > 0)
                    .map(x => java.lang.Short.valueOf(x)).asJava
            case _ => null
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
                    (implicit map: JMap[String, Object]): JList[UUID] = {
        map.get(key) match {
            case s: String => s.split("\n").toList
                    .filter(_.length > 0)
                    .map(x => UUID.fromString(x)).asJava
            case _ => null
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

    private def readMatch()(implicit map: JMap[String, Object]): FlowRecordMatch = {
        FlowRecordMatch(readNumber("flowMatch.inputPort").toInt,
                        readNumber("flowMatch.tunnelKey"),
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

    def writeActionList(actions: JList[FlowAction])
                       (implicit jgen: JsonGenerator): Unit = {
        jgen.writeFieldName("actions")
        jgen.writeStartArray()
        if (actions != null) {
            val iter = actions.iterator
            while (iter.hasNext) {
                jgen.writeStartObject()
                iter.next() match {
                    case a: Output =>
                        writeString("type", "output")
                        writeNumber("portno", a.portNo)
                    case a: PopVlan =>
                        writeString("type", "popVlan")
                    case a: PushVlan =>
                        writeString("type", "pushVlan")
                        writeNumber("tpid", a.tpid)
                        writeNumber("tci", a.tci)
                    case a: Userspace =>
                        writeString("type", "userspace")
                        writeNumber("uplinkId", a.uplinkId)
                        writeNumber("userdata", a.userData)
                    case a: Arp =>
                        writeString("type", "arp")
                        writeNumber("sip", a.sip)
                        writeNumber("tip", a.tip)
                        writeNumber("op", a.op)
                        writeBytes("sha", a.sha)
                        writeBytes("tha", a.tha)
                    case a: Ethernet =>
                        writeString("type", "ethernet")
                        writeBytes("src", a.src)
                        writeBytes("dst", a.dst)
                    case a: EtherType =>
                        writeString("type", "ethertype")
                        writeNumber("ethertype", a.`type`)
                    case a: IcmpEcho =>
                        writeString("type", "icmpecho")
                        writeNumber("icmptype", a.`type`)
                        writeNumber("code", a.`code`)
                        writeNumber("id", a.id)
                    case a: IcmpError =>
                        writeString("type", "icmperror")
                        writeNumber("icmptype", a.`type`)
                        writeNumber("code", a.code)
                        writeBytes("id", a.data)
                    case a: Icmp =>
                        writeString("type", "icmp")
                        writeNumber("icmptype", a.`type`)
                        writeNumber("code", a.code)
                    case a: IPv4 =>
                        writeString("type", "ipv4")
                        writeNumber("src", a.src)
                        writeNumber("dst", a.dst)
                        writeNumber("proto", a.proto)
                        writeNumber("tos", a.tos)
                        writeNumber("ttl", a.ttl)
                        writeNumber("frag", a.frag)
                    case a: TCP =>
                        writeString("type", "tcp")
                        writeNumber("src", a.src)
                        writeNumber("dst", a.dst)
                    case a: Tunnel =>
                        writeString("type", "tunnel")
                        writeNumber("id", a.id)
                        writeNumber("ipv4_src", a.ipv4_src)
                        writeNumber("ipv4_dst", a.ipv4_dst)
                        writeNumber("flags", a.flags)
                        writeNumber("ipv4_tos", a.ipv4_tos)
                        writeNumber("ipv4_ttl", a.ipv4_ttl)
                    case a: UDP =>
                        writeString("type", "udp")
                        writeNumber("src", a.src)
                        writeNumber("dst", a.dst)
                    case a: VLan =>
                        writeString("type", "vlan")
                        writeNumber("id", a.id)
                    case _ =>
                }
                jgen.writeEndObject()
            }
            jgen.writeEndArray()
        }
    }

    private def readAction(map: JMap[String, Object]): FlowAction = {
        implicit val imap = map
        map.get("type") match {
            case "output" => Output(readNumber("portno").toInt)
            case "popVlan" => PopVlan()
            case "pushVlan" => PushVlan(readNumber("tpid").toShort,
                                        readNumber("tci").toShort)
            case "userspace" => Userspace(readNumber("uplinkId").toInt,
                                          readNumber("userdata"))
            case "arp" => Arp(readNumber("sip").toInt,
                              readNumber("tip").toInt,
                              readNumber("op").toShort,
                              readBytes("sha"),
                              readBytes("tha"))
            case "ethernet" => Ethernet(readBytes("src"),
                                        readBytes("dst"))
            case "ethertype" => EtherType(readNumber("ethertype").toShort)
            case "icmpecho" => IcmpEcho(readNumber("icmptype").toByte,
                                        readNumber("code").toByte,
                                        readNumber("id").toShort)
            case "icmperror" => IcmpError(readNumber("icmptype").toByte,
                                          readNumber("code").toByte,
                                          readBytes("id"))
            case "icmp" => Icmp(readNumber("icmptype").toByte,
                                readNumber("code").toByte)
            case "ipv4" => IPv4(readNumber("src").toInt,
                                readNumber("dst").toInt,
                                readNumber("proto").toByte,
                                readNumber("tos").toByte,
                                readNumber("ttl").toByte,
                                readNumber("frag").toByte)
            case "tcp" => TCP(readNumber("src").toShort,
                              readNumber("dst").toShort)
            case "tunnel" => Tunnel(readNumber("id").toInt,
                                    readNumber("ipv4_src").toInt,
                                    readNumber("ipv4_dst").toInt,
                                    readNumber("flags").toShort,
                                    readNumber("ipv4_tos").toByte,
                                    readNumber("ipv4_ttl").toByte)
            case "udp" => UDP(readNumber("src").toShort,
                              readNumber("dst").toShort)
            case "vlan" => VLan(readNumber("id").toShort)
            case t => throw new IllegalArgumentException(
                s"Unknown action type $t")
        }
    }

    private def readActionList()(implicit map: JMap[String, Object])
    : JList[FlowAction] = {
        val actionArray = map.get("actions").asInstanceOf[JList[JMap[String,Object]]]
        val actions = new JArrayList[FlowAction]
        val iter = actionArray.iterator()
        while (iter.hasNext) {
            actions.add(readAction(iter.next()))
        }

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
                     (implicit map: JMap[String, Object])
            : SimulationResult.SimulationResult = {
        map.get("simResult") match {
            case s: String => SimulationResult.withName(s)
            case _ => null
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
                    (implicit map: JMap[String, Object]): JList[TraversedRule] = {
        map.get("rules") match {
            case s: String => s.split("\n").toList
                    .filter(_.length > 0)
                    .map(x => {
                             val parts = x.split(" ")
                             TraversedRule(UUID.fromString(parts(0)),
                                           RuleResult.withName(
                                               parts(1).replace(")", "")
                                                   .replace("(", "")))
                         }).asJava
            case _ => null
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
