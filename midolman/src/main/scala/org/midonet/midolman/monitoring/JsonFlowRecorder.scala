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

package org.midonet.midolman.monitoring

import scala.collection.JavaConverters._
import java.nio.ByteBuffer
import java.util.{ArrayList, List, UUID}

import com.google.common.base.Joiner
import com.google.common.io.BaseEncoding
import org.codehaus.jackson._
import org.codehaus.jackson.map._
import org.codehaus.jackson.map.annotate._

import org.midonet.midolman.PacketWorkflow.SimulationResult
import org.midonet.midolman.config.FlowHistoryConfig
import org.midonet.midolman.simulation.PacketContext
import org.midonet.odp.FlowMatch
import org.midonet.odp.flows._
import org.midonet.packets.{IPv4Addr, MAC}

class JsonFlowRecorder(hostId: UUID, config: FlowHistoryConfig)
        extends AbstractFlowRecorder(config) {

    val mapper = new ObjectMapper()
    val record = new JsonFlowRecord(hostId)

    override def encodeRecord(pktContext: PacketContext,
                              simRes: SimulationResult): ByteBuffer = {
        record.reset(pktContext, simRes)
        ByteBuffer.wrap(mapper.writeValueAsBytes(record))
    }
}

@JsonSerialize(using=classOf[JsonFlowRecordSerializer])
case class JsonFlowRecord(hostId: UUID) extends FlowRecord {
    // actions getters
}

class JsonFlowRecordSerializer extends JsonSerializer[JsonFlowRecord] {

    override def serialize(record: JsonFlowRecord,
                           jgen: JsonGenerator,
                           provider: SerializerProvider): Unit = {
        jgen.writeStartObject()
        writeStringField(jgen, "type", "flowhistory")
        jgen.writeNumberField("cookie", record.cookie)
        writeStringField(jgen, "hostUUID", record.hostId)
        writeStringField(jgen, "inPort", record.inPort)

        writeStringField(jgen, "outPorts",
                         Joiner.on("\n").join(record.outPorts))
        writeStringField(jgen, "rules",
                         Joiner.on("\n").join(record.rules))
        writeStringField(jgen, "devices",
                         Joiner.on("\n").join(record.devices))
        if (record.simResult != null) {
            writeStringField(jgen, "simulationResult",
                             record.simResult.getClass
                                 .getSimpleName.replace("$", ""))
        }

        jgen.writeNumberField("flowMatch.inputPort",
                              record.flowMatch.getInputPortNumber)
        jgen.writeNumberField("flowMatch.tunnelKey",
                              record.flowMatch.getTunnelKey)
        jgen.writeNumberField("flowMatch.tunnelSrc",
                              record.flowMatch.getTunnelSrc)
        jgen.writeNumberField("flowMatch.tunnelDst",
                              record.flowMatch.getTunnelDst)
        writeStringField(jgen, "flowMatch.ethSrc",
                         record.flowMatch.getEthSrc)
        writeStringField(jgen, "flowMatch.ethDst",
                         record.flowMatch.getEthDst)
        jgen.writeNumberField("flowMatch.etherType",
                              record.flowMatch.getEtherType)
        writeStringField(jgen, "flowMatch.networkSrc",
                         record.flowMatch.getNetworkSrcIP)
        writeStringField(jgen, "flowMatch.networkDst",
                         record.flowMatch.getNetworkDstIP)
        jgen.writeNumberField("flowMatch.networkProto",
                              record.flowMatch.getNetworkProto)
        jgen.writeNumberField("flowMatch.networkTTL",
                              record.flowMatch.getNetworkTTL)
        jgen.writeNumberField("flowMatch.networkTOS",
                              record.flowMatch.getNetworkTOS)
        writeStringField(jgen, "flowMatch.IPFragType",
                         record.flowMatch.getIpFragmentType)
        jgen.writeNumberField("flowMatch.srcPort",
                              record.flowMatch.getSrcPort)
        jgen.writeNumberField("flowMatch.dstPort",
                              record.flowMatch.getDstPort)
        jgen.writeNumberField("flowMatch.icmpIdentifier",
                              record.flowMatch.getIcmpIdentifier)
        writeStringField(jgen, "flowMatch.icmpData",
                         BaseEncoding.base16.encode(
                             record.flowMatch.getIcmpData))
        writeStringField(jgen, "flowMatch.vlanIds",
                         Joiner.on(", ").join(record.flowMatch.getVlanIds))
        writeActions(jgen, record.actions)
        jgen.writeEndObject()
    }

    private def writeStringField(jgen: JsonGenerator,
                                 name: String,
                                 value: Object): Unit = {
        if (value != null) {
            val string = value.toString
            if (string.length > 0) {
                jgen.writeStringField(name, string)
            }
        }
    }

    private def writeActions(jgen: JsonGenerator,
                             actions: List[FlowAction]): Unit = {
        def writeNumber(field: String, value: Long): Unit = {
            jgen.writeNumberField(s"action.${field}", value)
        }

        def writeString(field: String, value: String): Unit = {
            jgen.writeStringField(s"action.${field}", value)
        }

        def setKeyAction(action: FlowActionSetKey): Unit = {
            action.getFlowKey match {
                case a: FlowKeyARP =>
                    writeString("arp.sip", IPv4Addr.intToString(a.arp_sip))
                    writeString("arp.tip",IPv4Addr.intToString(a.arp_tip))
                    writeNumber("arp.op", a.arp_op)
                    writeString("arp.sha", MAC.bytesToString(a.arp_sha))
                    writeString("arp.tha", MAC.bytesToString(a.arp_tha))
                case a: FlowKeyEthernet =>
                    writeString("eth.src", MAC.bytesToString(a.eth_src))
                    writeString("eth.dst", MAC.bytesToString(a.eth_dst))
                case a: FlowKeyEtherType =>
                    writeNumber("ethertype.type", a.etherType)
                case a: FlowKeyICMPEcho =>
                    writeNumber("icmp.echo.type", a.icmp_type)
                    writeNumber("icmp.echo.code", a.icmp_code)
                    writeNumber("icmp.echo.id", a.icmp_id)
                case a: FlowKeyICMPError =>
                    writeNumber("icmp.error.type", a.icmp_type)
                    writeNumber("icmp.error.code", a.icmp_code)
                    writeString("icmp.error.id",
                                BaseEncoding.base16.encode(a.icmp_data))
                case a: FlowKeyICMP =>
                    writeNumber("icmp.type", a.icmp_type)
                    writeNumber("icmp.code", a.icmp_code)
                case a: FlowKeyIPv4 =>
                    writeString("ipv4.src",
                                IPv4Addr.intToString(a.ipv4_src))
                    writeString("ipv4.dst",
                                IPv4Addr.intToString(a.ipv4_dst))
                    writeNumber("ipv4.proto", a.ipv4_proto)
                    writeNumber("ipv4.tos", a.ipv4_tos)
                    writeNumber("ipv4.ttl", a.ipv4_ttl)
                    writeNumber("ipv4.frag", a.ipv4_frag)
                case a: FlowKeyTCP =>
                    writeNumber("tcp.src", a.tcp_src)
                    writeNumber("tcp.dst", a.tcp_dst)
                case a: FlowKeyTunnel =>
                    writeNumber("tunnel.id", a.tun_id)
                    writeString("tunnel.ipv4_src",
                                IPv4Addr.intToString(a.ipv4_src))
                    writeString("tunnel.ipv4_dst",
                                IPv4Addr.intToString(a.ipv4_dst))
                    writeNumber("tunnel.flags", a.tun_flags)
                    writeNumber("tunnel.ipv4_tos", a.ipv4_tos)
                    writeNumber("tunnel.ipv4_ttl", a.ipv4_ttl)
                case a: FlowKeyUDP =>
                    writeNumber("udp.src", a.udp_src)
                    writeNumber("udp.dst", a.udp_dst)
                case a: FlowKeyVLAN =>
                    writeNumber("vlan.id", a.vlan)
                case _ =>
            }
        }

        var i = 0
        while (i < actions.size) {
            actions.get(i) match {
                case a: FlowActionOutput =>
                    writeNumber("output.portno", a.getPortNumber)
                case a: FlowActionPopVLAN =>
                    writeString("popVlan", "true")
                case a: FlowActionPushVLAN =>
                    writeNumber("pushVlan.tpid", a.getTagProtocolIdentifier)
                    writeNumber("pushVlan.tci", a.getTagControlIdentifier)
                case a: FlowActionSetKey =>
                    setKeyAction(a)
                case a: FlowActionUserspace =>
                    writeNumber("userspace.uplinkId", a.uplinkPid)
                    writeNumber("userspace.userdata", a.userData)
                case _ =>
            }
            i += 1
        }

    }
}

