/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.netlink.protos;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;
import javax.annotation.Nullable;

import com.google.common.base.Function;
import com.google.common.util.concurrent.Futures;

import com.midokura.util.netlink.NetlinkChannel;
import com.midokura.util.netlink.NetlinkConnection;
import com.midokura.util.netlink.NetlinkMessage;
import com.midokura.util.netlink.family.DatapathFamily;
import com.midokura.util.netlink.family.VPortFamily;
import static com.midokura.util.netlink.Netlink.Flag;

/**
 * // TODO: Explain yourself.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 6/27/12
 */
public class OvsDatapathConnection extends NetlinkConnection {

    DatapathFamily datapathFamily;
    VPortFamily vPortFamily;

//    CommandFamily<FlowCommands> ovsFlowFamily;
//    CommandFamily<PacketCommands> ovsPacketFamily;

    int ovsVportMulticastGroup;

    public OvsDatapathConnection(NetlinkChannel channel) throws Exception {
        super(channel);
    }

    public void initialize() throws Exception {

        datapathFamily = new DatapathFamily(getFamilyId("ovs_datapath").get());

        vPortFamily = new VPortFamily(getFamilyId("ovs_vport").get());

//        ovsFlowFamily = new Family<Cmd>(getFamilyId("ovs_flow"), 1);
//        ovsPacketFamily = new Family<Cmd>(getFamilyId("ovs_packet"), 1);

        ovsVportMulticastGroup = getMulticastGroup("ovs_vport",
                                                   "ovs_vport").get();

        // TODO: create a connection socket and subscribe to ovs_vport group
    }

    public Future<Set<String>> enumerateDatapaths() throws IOException {
        ByteBuffer buf = ByteBuffer.allocateDirect(64);
        buf.order(ByteOrder.nativeOrder());

        // put in an int for the ovs_header->dp_index
        buf.putInt(0);
        buf.flip();

        Future<List<ByteBuffer>> futureRequest =
            makeRequest(
                datapathFamily,
                Flag.or(Flag.NLM_F_REQUEST, Flag.NLM_F_ECHO, Flag.NLM_F_DUMP),
                DatapathFamily.Cmd.GET,
                buf);

        return Futures.compose(
            futureRequest,
            new Function<List<ByteBuffer>, Set<String>>() {
                @Override
                public Set<String> apply(@Nullable List<ByteBuffer> buffers) {

                    if (buffers == null) {
                        return Collections.emptySet();
                    }

                    Set<String> dataPathNames = new HashSet<String>();

                    for (ByteBuffer buffer : buffers) {
                        NetlinkMessage message =
                            new NetlinkMessage(buffer);

                        dataPathNames.add(
                            message.findStringAttr(
                                DatapathFamily.Attr.NAME));
                    }

                    return dataPathNames;
                }
            });
    }

    public Future<Integer> getDatapath(String name) throws Exception {

        ByteBuffer buf = ByteBuffer.allocateDirect(64);
        buf.order(ByteOrder.nativeOrder());

        // put in an int for the ovs_header->dp_index
        buf.putInt(0);

        NetlinkMessage req = new NetlinkMessage(buf);

        // put desired name
        req.putStringAttr(DatapathFamily.Attr.NAME, name);

        buf.flip();

        Future<List<ByteBuffer>> or =
            makeRequest(
                datapathFamily,
                Flag.or(Flag.NLM_F_REQUEST, Flag.NLM_F_ECHO, Flag.NLM_F_DUMP),
                DatapathFamily.Cmd.GET,
                buf);

        return Futures.compose(or, new Function<List<ByteBuffer>, Integer>() {
            @Override
            public Integer apply(@Nullable List<ByteBuffer> input) {
                if (input == null)
                    return null;

                return input.get(0).getInt();
            }
        });
    }

    public Future<Integer> createDatapath(String name) throws Exception {
        ByteBuffer buf = ByteBuffer.allocateDirect(64);
        buf.order(ByteOrder.nativeOrder());

        NetlinkMessage req = new NetlinkMessage(buf);

        // put in an int for the ovs_header->dp_index
        buf.putInt(0);

        // put desired name
        req.putStringAttr(DatapathFamily.Attr.NAME, name);

        // put upcall pid 0
        req.putIntAttr(DatapathFamily.Attr.UPCALL_PID, 0);

        final Future<List<ByteBuffer>> or =
            makeRequest(
                datapathFamily,
                Flag.or(Flag.NLM_F_REQUEST, Flag.NLM_F_ECHO),
                DatapathFamily.Cmd.NEW,
                req.getBuffer());

        return Futures.compose(or, new Function<List<ByteBuffer>, Integer>() {
            @Override
            public Integer apply(@Nullable List<ByteBuffer> input) {
                if (input == null) {
                    return null;
                }

                return input.get(0).getInt();
            }
        });
    }

    public Future<List<ByteBuffer>> addPort(int dpIndex, int portType, String name)
        throws Exception {
        ByteBuffer buf = ByteBuffer.allocateDirect(64);
        buf.order(ByteOrder.nativeOrder());

        NetlinkMessage req = new NetlinkMessage(buf);

        // put in an int for the ovs_header->dp_index
        buf.putInt(dpIndex);

//		req.putIntAttr(OVS_VPORT_ATTR_PORT_NO, portId);
        req.putIntAttr(VPortFamily.Attr.OVS_VPORT_ATTR_TYPE, portType);
        req.putStringAttr(VPortFamily.Attr.OVS_VPORT_ATTR_NAME, name);
        req.putIntAttr(VPortFamily.Attr.OVS_VPORT_ATTR_UPCALL_PID,
                       getChannel().getRemoteAddress().getPid());
//		req.put(OVS_VPORT_ATTR_ADDRESS, ethAddr);

        return makeRequest(vPortFamily,
                           Flag.or(Flag.NLM_F_REQUEST, Flag.NLM_F_ECHO),
                           VPortFamily.Cmd.NEW,
                           req.getBuffer());
    }

}
