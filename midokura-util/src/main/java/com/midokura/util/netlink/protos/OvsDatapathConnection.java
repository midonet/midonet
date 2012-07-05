/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.netlink.protos;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.common.base.Function;
import com.google.common.util.concurrent.ValueFuture;

import com.midokura.util.netlink.NetlinkChannel;
import com.midokura.util.netlink.NetlinkMessage;
import com.midokura.util.netlink.dp.Datapath;
import com.midokura.util.netlink.dp.Port;
import com.midokura.util.netlink.dp.Ports;
import com.midokura.util.netlink.family.DatapathFamily;
import com.midokura.util.netlink.family.VPortFamily;
import com.midokura.util.reactor.Reactor;
import static com.midokura.util.netlink.Netlink.Flag;

/**
 * OvsDatapath protocol implementation.
 */
public class OvsDatapathConnection extends NetlinkConnection {

    DatapathFamily datapathFamily;
    VPortFamily vPortFamily;

//    CommandFamily<FlowCommands> ovsFlowFamily;
//    CommandFamily<PacketCommands> ovsPacketFamily;

    int ovsVportMulticastGroup;

    public boolean isInitialized() {
        return state == State.Initialized;
    }

    enum State {
        Initializing, ErrorInInitialization, Initialized
    }

    State state;
    String initializationErrorCause;

    public OvsDatapathConnection(NetlinkChannel channel, Reactor reactor)
        throws Exception {
        super(channel, reactor);
    }

    public void initialize() throws Exception {

        state = State.Initializing;

        final Callback<Short> vPortFamilyBuilder = new StateAwareCallback<Short>() {
            @Override
            public void onSuccess(Short data) {
                vPortFamily = new VPortFamily(data);
                state = State.Initialized;
            }
        };

        final Callback<Short> dataPathFamilyBuilder = new StateAwareCallback<Short>() {
            @Override
            public void onSuccess(Short data) {
                datapathFamily = new DatapathFamily(data);
                getFamilyId("ovs_vport", vPortFamilyBuilder);
            }
        };

        getFamilyId("ovs_datapath", dataPathFamilyBuilder);

//        ovsFlowFamily = new Family<Cmd>(getFamilyId("ovs_flow"), 1);
//        ovsPacketFamily = new Family<Cmd>(getFamilyId("ovs_packet"), 1);

//        ovsVportMulticastGroup = getMulticastGroup("ovs_vport",
//                                                   "ovs_vport").get();

        // TODO: create a connection socket and subscribe to ovs_vport group
    }

    public Future<Set<Datapath>> enumerateDatapaths() {
        ValueFuture<Set<Datapath>> future = ValueFuture.create();
        enumerateDatapaths(wrapFuture(future));
        return future;
    }

    public void enumerateDatapaths(Callback<Set<Datapath>> callback) {
        enumerateDatapaths(callback, DEF_REPLY_TIMEOUT);
    }

    public void enumerateDatapaths(Callback<Set<Datapath>> callback, long timeoutMillis) {

        validateState(callback);

        NetlinkMessage message =
            newMessage(64)
                .addIntValue(0)
                .build();

        newRequest(datapathFamily, DatapathFamily.Cmd.GET)
            .withFlags(Flag.NLM_F_REQUEST, Flag.NLM_F_ECHO, Flag.NLM_F_DUMP)
            .withPayload(message.buf)
            .withCallback(
                callback,
                new Function<List<ByteBuffer>, Set<Datapath>>() {
                    @Override
                    public Set<Datapath> apply(@Nullable List<ByteBuffer> input) {
                        if (input == null) {
                            return Collections.emptySet();
                        }

                        Set<Datapath> datapaths = new HashSet<Datapath>();

                        for (ByteBuffer buffer : input) {
                            NetlinkMessage msg =
                                new NetlinkMessage(buffer);

                            datapaths.add(
                                new Datapath(
                                    msg.getInt(),
                                    msg.getAttrValue(DatapathFamily.Attr.NAME)
                                ));
                        }

                        return datapaths;
                    }
                }
            )
            .withTimeout(timeoutMillis)
            .send();
    }

    private void validateState(Callback callback) {
        switch (state) {
            case Initialized:
                return;
            case ErrorInInitialization:
                callback.onError(-1,
                                 "datapath connection had errors in initialization");
                break;
            case Initializing:
                callback.onError(-2,
                                 "Datapath connection is not initialized yet.");
                break;
        }
    }

    public void getDatapath(String name, Callback<Integer> callback)
        throws Exception {

        validateState(callback);

        NetlinkMessage message =
            newMessage(64)
                .addIntValue(0)
                .addAttr(DatapathFamily.Attr.NAME, name)
                .build();

        newRequest(datapathFamily, DatapathFamily.Cmd.GET)
            .withFlags(Flag.NLM_F_REQUEST, Flag.NLM_F_ECHO, Flag.NLM_F_DUMP)
            .withPayload(message.buf)
            .withCallback(
                callback,
                new Function<List<ByteBuffer>, Integer>() {
                    @Override
                    public Integer apply(@Nullable List<ByteBuffer> input) {
                        if (input == null)
                            return null;

                        return input.get(0).getInt();
                    }
                })
            .send();
    }

    public void createDatapath(String name, Callback<Integer> callback)
        throws Exception {
        createDatapath(name, callback, DEF_REPLY_TIMEOUT);
    }

    public void createDatapath(String name, Callback<Integer> callback, long timeoutMillis)
        throws Exception {

        validateState(callback);

        NetlinkMessage message =
            newMessage(64)
                .addIntValue(0)
                .addAttr(DatapathFamily.Attr.NAME, name)
                .addAttr(DatapathFamily.Attr.UPCALL_PID, 0)
                .build();

        newRequest(datapathFamily, DatapathFamily.Cmd.NEW)
            .withFlags(Flag.NLM_F_REQUEST, Flag.NLM_F_ECHO)
            .withPayload(message.buf)
            .withCallback(
                callback,
                new Function<List<ByteBuffer>, Integer>() {
                    @Override
                    public Integer apply(@Nullable List<ByteBuffer> input) {
                        if (input == null) {
                            return null;
                        }

                        return input.get(0).getInt();
                    }
                })
            .withTimeout(timeoutMillis)
            .send();
    }

    public Future<Set<Port>> enumeratePorts(int datapathIndex) {
        ValueFuture<Set<Port>> valueFuture = ValueFuture.create();
        enumeratePorts(datapathIndex, wrapFuture(valueFuture));
        return valueFuture;
    }

    public void enumeratePorts(int datapathIndex, Callback<Set<Port>> callback) {
        enumeratePorts(datapathIndex, callback, DEF_REPLY_TIMEOUT);
    }

    public void enumeratePorts(final int dpIndex, Callback<Set<Port>> callback, final long timeoutMillis) {

        validateState(callback);

        NetlinkMessage message = newMessage()
            .addIntValue(dpIndex)
            .build();

        newRequest(vPortFamily, VPortFamily.Cmd.GET)
            .withFlags(Flag.NLM_F_DUMP, Flag.NLM_F_ECHO,
                       Flag.NLM_F_REQUEST, Flag.NLM_F_ACK)
            .withPayload(message.buf)
            .withCallback(
                callback,
                new Function<List<ByteBuffer>, Set<Port>>() {
                    @Override
                    public Set<Port> apply(@Nullable List<ByteBuffer> input) {
                        if (input == null) {
                            return Collections.emptySet();
                        }

                        Set<Port> ports = new HashSet<Port>();

                        for (ByteBuffer buffer : input) {
                            NetlinkMessage msg = new NetlinkMessage(
                                buffer);

                            // read the datapath id;
                            int actualDpIndex = msg.getInt();
                            if (actualDpIndex != dpIndex)
                                continue;

                            String name =
                                msg.getAttrValue(VPortFamily.Attr.NAME);
                            Integer type =
                                msg.getAttrValue(VPortFamily.Attr.PORT_TYPE);

                            Port port = Ports.newPortByType(
                                portTypeToEnumValue(type), name);

                            port.setAddress(
                                msg.getAttrValue(VPortFamily.Attr.ADDRESS)
                            );
                            port.setPortNo(
                                msg.getAttrValue(VPortFamily.Attr.PORT_NO)
                            );

                            ports.add(port);
                        }

                        return ports;
                    }
                })
            .withTimeout(timeoutMillis)
            .send();
    }

    public Future<Port> addPort(int datapathIndex, @Nonnull Port port) {

        ValueFuture<Port> valueFuture = ValueFuture.create();

        addPort(datapathIndex, port, wrapFuture(valueFuture));

        return valueFuture;
    }

    public void addPort(int dpIndex, @Nonnull Port port, Callback<Port> callback) {
        addPort(dpIndex, port, callback, DEF_REPLY_TIMEOUT);
    }

    public void addPort(int dpIndex, @Nonnull Port port,
                        @Nonnull Callback<Port> callback, long timeoutMillis) {

        int localPid = getChannel().getLocalAddress().getPid();

        NetlinkMessage.Builder builder = newMessage()
            .addIntValue(dpIndex)
            .addAttr(VPortFamily.Attr.PORT_TYPE,
                     portTypeToEnumValue(port.getType()))
            .addAttr(VPortFamily.Attr.NAME, port.getName())
            .addAttr(VPortFamily.Attr.UPCALL_PID, localPid);

        if (port.getPortNo() != null)
            builder.addAttr(VPortFamily.Attr.PORT_NO, port.getPortNo());

        if (port.getAddress() != null)
            builder.addAttr(VPortFamily.Attr.ADDRESS, port.getAddress());

        if (port.getOptions() != null) {
            builder.addAttr(VPortFamily.Attr.OPTIONS, port.getOptions());
        }

        NetlinkMessage message = builder.build();

        newRequest(vPortFamily, VPortFamily.Cmd.NEW)
            .withFlags(Flag.NLM_F_REQUEST, Flag.NLM_F_ECHO)
            .withPayload(message.buf)
            .withCallback(callback, new Function<List<ByteBuffer>, Port>() {
                @Override
                public Port apply(@Nullable List<ByteBuffer> input) {
                    return null;
                }
            })
            .withTimeout(timeoutMillis)
            .send();
    }

    private int portTypeToEnumValue(Port.Type portType) {

//        enum ovs_vport_type {
//            OVS_VPORT_TYPE_UNSPEC,
//            OVS_VPORT_TYPE_NETDEV,   /* network device */
//            OVS_VPORT_TYPE_INTERNAL, /* network device implemented by datapath */
//            OVS_VPORT_TYPE_PATCH = 100, /* virtual tunnel connecting two vports */
//            OVS_VPORT_TYPE_GRE,      /* GRE tunnel */
//            OVS_VPORT_TYPE_CAPWAP,   /* CAPWAP tunnel */
//            __OVS_VPORT_TYPE_MAX
//        };

        switch (portType) {
            case NetDev:
                return 1;
            case Internal:
                return 2;
            case Patch:
                return 100;
            case Gre:
                return 101;
            case CapWap:
                return 102;
        }

        return 0;
    }

    private Port.Type portTypeToEnumValue(Integer portType) {

//        enum ovs_vport_type {
//            OVS_VPORT_TYPE_UNSPEC,
//            OVS_VPORT_TYPE_NETDEV,   /* network device */
//            OVS_VPORT_TYPE_INTERNAL, /* network device implemented by datapath */
//            OVS_VPORT_TYPE_PATCH = 100, /* virtual tunnel connecting two vports */
//            OVS_VPORT_TYPE_GRE,      /* GRE tunnel */
//            OVS_VPORT_TYPE_CAPWAP,   /* CAPWAP tunnel */
//            __OVS_VPORT_TYPE_MAX
//        };

        if ( portType == null )
            return null;

        switch (portType) {
            case 1:
                return Port.Type.NetDev;
            case 2:
                return Port.Type.Internal;
            case 100:
                return Port.Type.Patch;
            case 101:
                return Port.Type.Gre;
            case 102:
                return Port.Type.CapWap;
            default:
                return null;
        }
    }

    private class StateAwareCallback<T> extends Callback<T> {

        @Override
        public void onSuccess(T data) {
            super.onSuccess(data);
        }

        @Override
        public void onTimeout() {
            state = State.ErrorInInitialization;
        }

        @Override
        public void onError(int error, String errorMessage) {
            state = State.ErrorInInitialization;
        }
    }
}
