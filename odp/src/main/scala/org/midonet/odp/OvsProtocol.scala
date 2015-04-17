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

import org.midonet.netlink.{NLFlag, NetlinkMessage, NetlinkMessageWrapper, NetlinkRequestContext}
import org.midonet.odp.flows.{FlowAction, FlowActions, FlowKey, FlowKeys}

/**
 * This class contains methods that prepare a Netlink message in the context
 * of the OVS protocol.
 * TODO: When there are no Java callers, these methods can directly
 *       receive a NetlinkMessageWrapper.
 */
final class OvsProtocol(val families: OvsNetlinkFamilies) {

    private val datapathFamily = families.datapathFamily
    private val portFamily = families.portFamily
    private val flowFamily = families.flowFamily
    private val packetFamily = families.packetFamily

    def messageFor(buf: ByteBuffer, datapathId: Int,
                   ctx: NetlinkRequestContext) = {
        val message = NetlinkMessageWrapper(buf).withContext(ctx)
        buf.putInt(datapathId)
        message
    }

    def enum(buf: ByteBuffer, pid: Int, datapathId: Int, ctx: NetlinkRequestContext) =
        messageFor(buf, datapathId, ctx)
            .withFlags(NLFlag.REQUEST | NLFlag.Get.DUMP)
            .finalize(pid)

    def prepareDatapathGet(pid: Int, datapathId: Int, name: String, buf: ByteBuffer): Unit = {
        import org.midonet.odp.OpenVSwitch.Datapath.Attr

        val message = messageFor(buf, datapathId, datapathFamily.contextGet)
            .withFlags(NLFlag.REQUEST)
        if (name ne null) {
            NetlinkMessage.writeStringAttr(buf, Attr.Name, name)
        }
        message.finalize(pid)
    }

    def prepareDatapathEnumerate(pid: Int, buf: ByteBuffer): Unit =
        enum(buf, 0, pid, datapathFamily.contextGet)

    def prepareDatapathCreate(pid: Int, name: String, buf: ByteBuffer): Unit = {
        import org.midonet.odp.OpenVSwitch.Datapath.Attr

        val message = messageFor(buf, 0, datapathFamily.contextNew)
            .withFlags(NLFlag.REQUEST | NLFlag.ECHO)
        NetlinkMessage.writeIntAttr(buf, Attr.UpcallPID, pid)
        if (name ne null) {
            NetlinkMessage.writeStringAttr(buf, Attr.Name, name)
        }
        message.finalize(pid)
    }

    def prepareDatapathDel(pid: Int, datapathId: Int, name: String, buf: ByteBuffer): Unit = {
        import org.midonet.odp.OpenVSwitch.Datapath.Attr

        val message = messageFor(buf, datapathId, datapathFamily.contextDel)
            .withFlags(NLFlag.REQUEST | NLFlag.ECHO)
        if (name ne null) {
            NetlinkMessage.writeStringAttr(buf, Attr.Name, name)
        }
        message.finalize(pid)
    }

    def prepareDpPortGet(pid: Int, datapathId: Int, portId: Integer,
                         portName: String, buf: ByteBuffer): Unit = {
        import org.midonet.odp.OpenVSwitch.Port.Attr

        val message = messageFor(buf, datapathId, portFamily.contextGet)
            .withFlags(NLFlag.REQUEST)
        NetlinkMessage.writeIntAttr(buf, Attr.UpcallPID, pid)
        if (portId != null) {
            NetlinkMessage.writeIntAttr(buf, Attr.PortNo, portId)
        }
        if (portName != null) {
            NetlinkMessage.writeStringAttr(buf, Attr.Name, portName)
        }
        message.finalize(pid)
    }

    def prepareDpPortEnum(pid: Int, datapathId: Int, buf: ByteBuffer): Unit =
        enum(buf, pid, datapathId, portFamily.contextGet)

    def prepareDpPortCreate(pid: Int, datapathId: Int, port: DpPort, buf: ByteBuffer): Unit =
        portRequest(buf, pid, datapathId, port, portFamily.contextNew)

    def prepareDpPortSet(pid: Int, datapathId: Int, port: DpPort, buf: ByteBuffer): Unit =
        portRequest(buf, pid, datapathId, port, portFamily.contextSet)

    def prepareDpPortDelete(pid: Int, datapathId: Int, port: DpPort, buf: ByteBuffer): Unit =
        portRequest(buf, pid, datapathId, port, portFamily.contextDel)

    private def portRequest(buf: ByteBuffer, pid: Int, datapathId: Int,
                            port: DpPort, ctx: NetlinkRequestContext): Unit = {
        import org.midonet.odp.OpenVSwitch.Port.Attr

        val message = messageFor(buf, datapathId, ctx)
            .withFlags(NLFlag.REQUEST | NLFlag.ECHO)
        NetlinkMessage.writeIntAttr(buf, Attr.UpcallPID, pid)
        port.serializeInto(buf)
        message.finalize(pid)
    }

    def prepareFlowGet(pid: Int, datapathId: Int, fmatch: FlowMatch,
                       buf: ByteBuffer): Unit = {
        import org.midonet.odp.OpenVSwitch.Flow.Attr

        val message = messageFor(buf, datapathId, flowFamily.contextGet)
            .withFlags(NLFlag.REQUEST)
        NetlinkMessage.writeAttrSeq(buf, Attr.Key, fmatch.getKeys, FlowKeys.writer)
        message.finalize(pid)
    }

    def prepareFlowEnum(pid: Int, datapathId: Int, buf: ByteBuffer): Unit =
        enum(buf, pid, datapathId, flowFamily.contextGet)

    def prepareFlowCreate(pid: Int, datapathId: Int, keys: JList[FlowKey],
                          actions: JList[FlowAction], flowMask: FlowMask,
                          buf: ByteBuffer): Unit = {
        import org.midonet.odp.OpenVSwitch.Flow.Attr

        val message = messageFor(buf, datapathId, flowFamily.contextNew)
            .withFlags(NLFlag.REQUEST | NLFlag.New.CREATE)
        NetlinkMessage.writeAttrSeq(buf, Attr.Key, keys, FlowKeys.writer)
        // the actions list is allowed to be empty (drop flow). Nevertheless the
        // actions nested attribute header needs to be written otherwise the
        // datapath will answer back with EINVAL
        NetlinkMessage.writeAttrSeq(buf, Attr.Actions, actions, FlowActions.writer)
        if (flowMask ne null) {
            NetlinkMessage.writeAttrNested(buf, Attr.Mask, flowMask)
        }
        message.finalize(pid)
    }

    def prepareFlowSet(pid: Int, datapathId: Int, supportsFlowMask: Boolean,
                       flow: Flow, buf: ByteBuffer): Unit = {
        import org.midonet.odp.OpenVSwitch.Flow.Attr

        val message = messageFor(buf, datapathId, flowFamily.contextSet)
            .withFlags(NLFlag.REQUEST | NLFlag.ECHO)
        NetlinkMessage.writeAttrSeq(buf, Attr.Key, flow.getMatch.getKeys, FlowKeys.writer)
        // the actions list is allowed to be empty (drop flow). Nevertheless the
        // actions nested attribute header needs to be written otherwise the
        // datapath will answer back with EINVAL
        NetlinkMessage.writeAttrSeq(buf, Attr.Actions, flow.getActions, FlowActions.writer)
        if (supportsFlowMask) {
            NetlinkMessage.writeAttrNested(buf, Attr.Mask, flow.getMask)
        }
        message.finalize(pid)
    }

    def prepareFlowDelete(pid: Int, datapathId: Int,
                          keys: java.lang.Iterable[FlowKey], buf: ByteBuffer): Unit = {
        import org.midonet.odp.OpenVSwitch.Flow.Attr

        val message = messageFor(buf, datapathId, flowFamily.contextDel)
            .withFlags(NLFlag.REQUEST | NLFlag.ECHO)
        NetlinkMessage.writeAttrSeq(buf, Attr.Key, keys, FlowKeys.writer)
        message.finalize(pid)
    }

    def prepareFlowFlush(pid: Int, datapathId: Int, buf: ByteBuffer): Unit = {
        val message = messageFor(buf, datapathId, flowFamily.contextDel)
            .withFlags(NLFlag.REQUEST | NLFlag.ACK)
        message.finalize(pid)
    }

    def preparePacketExecute(pid: Int, datapathId: Int, packet: Packet,
                             actions: JList[FlowAction], buf: ByteBuffer): Unit = {
        import org.midonet.odp.OpenVSwitch.Packet.Attr

        val message = messageFor(buf, datapathId, packetFamily.contextExec)
            .withFlags(NLFlag.REQUEST)
        // TODO(pino): find out why ovs_packet_cmd_execute throws an
        // EINVAL if we put the PACKET attribute right after the
        // datapathId. I examined the ByteBuffers constructed with that
        // ordering of attributes and compared it to this one, and found
        // only the expected difference.
        NetlinkMessage.writeAttrSeq(buf, Attr.Key, packet.getMatch.getKeys, FlowKeys.writer)
        NetlinkMessage.writeAttrSeq(buf, Attr.Actions, actions, FlowActions.writer)
        NetlinkMessage.writeRawAttribute(buf, Attr.Packet, packet.getEthernet.serialize())

        message.finalize(pid)
    }
}
