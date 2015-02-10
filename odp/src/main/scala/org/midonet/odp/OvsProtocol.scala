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

package org.midonet.odp

import java.nio.ByteBuffer
import java.util.{List => JList}
import org.midonet.netlink.{NLFlag, NetlinkMessage, NetlinkRequestContext}
import org.midonet.netlink.genl.GenlnetlinkMessageWrapper
import org.midonet.odp.flows.{FlowAction, FlowActions, FlowKey, FlowKeys}

/**
 * This class contains methods that prepare a Netlink message in the context
 * of the OVS protocol.
 * TODO: When there are no Java callers, these methods can directly
 *       receive a NetlinkMessageWrapper.
 */
sealed class OvsProtocol(pid: Int,
                         families: OvsNetlinkFamilies) {

    private val datapathFamily = families.datapathFamily
    private val portFamily = families.portFamily
    private val flowFamily = families.flowFamily
    private val packetFamily = families.packetFamily

    def messageFor(buf: ByteBuffer, datapathId: Int,
                   ctx: NetlinkRequestContext) = {
        val message = GenlnetlinkMessageWrapper(buf).withContext(ctx)
        buf.putInt(datapathId)
        message
    }

    def enum(buf: ByteBuffer, datapathId: Int, ctx: NetlinkRequestContext): Unit =
        messageFor(buf, datapathId, ctx)
            .toNetlink
            .withFlags(NLFlag.REQUEST | NLFlag.Get.DUMP)
            .toGeneric
            .finalize(pid)

    def prepareDatapathGet(datapathId: Int, name: String, buf: ByteBuffer): Unit = {
        import org.midonet.odp.OpenVSwitch.Datapath.Attr

        val message = messageFor(buf, datapathId, datapathFamily.contextGet)
            .toNetlink
            .withFlags(NLFlag.REQUEST)
        if (name ne null) {
            NetlinkMessage.writeStringAttr(buf, Attr.Name, name)
        }
        message.toGeneric.finalize(pid)
    }

    def prepareDatapathEnumerate(buf: ByteBuffer): Unit =
        enum(buf, 0, datapathFamily.contextGet)

    def prepareDatapathCreate(name: String, buf: ByteBuffer): Unit = {
        import org.midonet.odp.OpenVSwitch.Datapath.Attr

        val message = messageFor(buf, 0, datapathFamily.contextNew)
            .toNetlink
            .withFlags(NLFlag.REQUEST | NLFlag.ECHO)
        NetlinkMessage.writeIntAttr(buf, Attr.UpcallPID, pid)
        if (name ne null) {
            NetlinkMessage.writeStringAttr(buf, Attr.Name, name)
        }
        message.toGeneric.finalize(pid)
    }

    def prepareDatapathDel(datapathId: Int, name: String, buf: ByteBuffer): Unit = {
        import org.midonet.odp.OpenVSwitch.Datapath.Attr

        val message = messageFor(buf, datapathId, datapathFamily.contextDel)
            .toNetlink
            .withFlags(NLFlag.REQUEST | NLFlag.ECHO)
        if (name ne null) {
            NetlinkMessage.writeStringAttr(buf, Attr.Name, name)
        }
        message.toGeneric.finalize(pid)
    }

    def prepareDpPortGet(datapathId: Int, portId: Integer,
                         portName: String, buf: ByteBuffer): Unit = {
        import org.midonet.odp.OpenVSwitch.Port.Attr

        val message = messageFor(buf, datapathId, portFamily.contextGet)
            .toNetlink
            .withFlags(NLFlag.REQUEST)
        NetlinkMessage.writeIntAttr(buf, Attr.UpcallPID, pid)
        if (portId != null) {
            NetlinkMessage.writeIntAttr(buf, Attr.PortNo, portId)
        }
        if (portName != null) {
            NetlinkMessage.writeStringAttr(buf, Attr.Name, portName)
        }
        message.toGeneric.finalize(pid)
    }

    def prepareDpPortEnum(datapathId: Int, buf: ByteBuffer): Unit =
        enum(buf, datapathId, portFamily.contextGet)

    def prepareDpPortCreate(datapathId: Int, port: DpPort, buf: ByteBuffer): Unit =
        portRequest(buf, datapathId, port, portFamily.contextNew)

    def prepareDpPortSet(datapathId: Int, port: DpPort, buf: ByteBuffer): Unit =
        portRequest(buf, datapathId, port, portFamily.contextSet)

    def prepareDpPortDelete(datapathId: Int, port: DpPort, buf: ByteBuffer): Unit =
        portRequest(buf, datapathId, port, portFamily.contextDel)

    private def portRequest(buf: ByteBuffer, datapathId: Int, port: DpPort,
                            ctx: NetlinkRequestContext): Unit = {
        import org.midonet.odp.OpenVSwitch.Port.Attr

        val message = messageFor(buf, datapathId, ctx)
            .toNetlink
            .withFlags(NLFlag.REQUEST | NLFlag.ECHO)
        NetlinkMessage.writeIntAttr(buf, Attr.UpcallPID, pid)
        port.serializeInto(buf)
        message.toGeneric.finalize(pid)
    }

    def prepareFlowGet(datapathId: Int, fmatch: FlowMatch, buf: ByteBuffer): Unit = {
        import org.midonet.odp.OpenVSwitch.Flow.Attr

        val message = messageFor(buf, datapathId, flowFamily.contextGet)
            .toNetlink
            .withFlags(NLFlag.REQUEST)
        NetlinkMessage.writeAttrSeq(buf, Attr.Key, fmatch.getKeys, FlowKeys.writer)
        message.toGeneric.finalize(pid)
    }

    def prepareFlowEnum(datapathId: Int, buf: ByteBuffer): Unit =
        enum(buf, datapathId, flowFamily.contextGet)

    def prepareFlowCreate(datapathId: Int, supportsFlowMask: Boolean,
                          flow: Flow, buf: ByteBuffer): Unit = {
        import org.midonet.odp.OpenVSwitch.Flow.Attr

        val message = messageFor(buf, datapathId, flowFamily.contextNew)
            .toNetlink
            .withFlags(NLFlag.REQUEST | NLFlag.New.CREATE)
        NetlinkMessage.writeAttrSeq(buf, Attr.Key, flow.getMatch.getKeys, FlowKeys.writer)
        // the actions list is allowed to be empty (drop flow). Nevertheless the
        // actions nested attribute header needs to be written otherwise the
        // datapath will answer back with EINVAL
        NetlinkMessage.writeAttrSeq(buf, Attr.Actions, flow.getActions, FlowActions.writer)
        if (supportsFlowMask) {
            NetlinkMessage.writeAttrNested(buf, Attr.Mask, flow.getMask)
        }
        message.toGeneric.finalize(pid)
    }

    def prepareFlowSet(datapathId: Int, supportsFlowMask: Boolean,
                       flow: Flow, buf: ByteBuffer): Unit = {
        import org.midonet.odp.OpenVSwitch.Flow.Attr

        val message = messageFor(buf, datapathId, flowFamily.contextSet)
            .toNetlink
            .withFlags(NLFlag.REQUEST | NLFlag.ECHO)
        NetlinkMessage.writeAttrSeq(buf, Attr.Key, flow.getMatch.getKeys, FlowKeys.writer)
        // the actions list is allowed to be empty (drop flow). Nevertheless the
        // actions nested attribute header needs to be written otherwise the
        // datapath will answer back with EINVAL
        NetlinkMessage.writeAttrSeq(buf, Attr.Actions, flow.getActions, FlowActions.writer)
        if (supportsFlowMask) {
            NetlinkMessage.writeAttrNested(buf, Attr.Mask, flow.getMask)
        }
        message.toGeneric.finalize(pid)
    }

    def prepareFlowDelete(datapathId: Int, keys: java.lang.Iterable[FlowKey],
                          buf: ByteBuffer): Unit = {
        import org.midonet.odp.OpenVSwitch.Flow.Attr

        val message = messageFor(buf, datapathId, flowFamily.contextDel)
            .toNetlink
            .withFlags(NLFlag.REQUEST | NLFlag.ECHO)
        NetlinkMessage.writeAttrSeq(buf, Attr.Key, keys, FlowKeys.writer)
        message.toGeneric.finalize(pid)
    }

    def prepareFlowFlush(datapathId: Int, buf: ByteBuffer): Unit = {
        val message = messageFor(buf, datapathId, flowFamily.contextDel)
            .toNetlink
            .withFlags(NLFlag.REQUEST | NLFlag.ACK)
        message.toGeneric.finalize(pid)
    }

    def preparePacketExecute(datapathId: Int, packet: Packet, actions: JList[FlowAction],
                             buf: ByteBuffer): Unit = {
        import org.midonet.odp.OpenVSwitch.Packet.Attr

        val message = messageFor(buf, datapathId, packetFamily.contextExec)
            .toNetlink
            .withFlags(NLFlag.REQUEST)
        // TODO(pino): find out why ovs_packet_cmd_execute throws an
        // EINVAL if we put the PACKET attribute right after the
        // datapathId. I examined the ByteBuffers constructed with that
        // ordering of attributes and compared it to this one, and found
        // only the expected difference.
        NetlinkMessage.writeAttrSeq(buf, Attr.Key, packet.getMatch.getKeys, FlowKeys.writer)
        NetlinkMessage.writeAttrSeq(buf, Attr.Actions, actions, FlowActions.writer)
        NetlinkMessage.writeRawAttribute(buf, Attr.Packet, packet.getEthernet.serialize())

        message.toGeneric.finalize(pid)
    }
}
