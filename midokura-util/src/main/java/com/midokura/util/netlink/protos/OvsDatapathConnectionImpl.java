/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.netlink.protos;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.common.base.Function;

import com.midokura.util.netlink.NetlinkChannel;
import com.midokura.util.netlink.NetlinkMessage;
import com.midokura.util.netlink.dp.Datapath;
import com.midokura.util.netlink.dp.Port;
import com.midokura.util.netlink.dp.Ports;
import com.midokura.util.netlink.exceptions.NetlinkException;
import com.midokura.util.netlink.family.DatapathFamily;
import com.midokura.util.netlink.family.VPortFamily;
import com.midokura.util.reactor.Reactor;
import static com.midokura.util.netlink.Netlink.Flag;

/**
 * // TODO: mtoader ! Please explain yourself.
 */
public class OvsDatapathConnectionImpl extends OvsDatapathConnection {

    public OvsDatapathConnectionImpl(NetlinkChannel channel, Reactor reactor)
        throws Exception {
        super(channel, reactor);
    }

    DatapathFamily datapathFamily;
    VPortFamily vPortFamily;

//    CommandFamily<FlowCommands> ovsFlowFamily;
//    CommandFamily<PacketCommands> ovsPacketFamily;

    int ovsVportMulticastGroup;

    @Override
    protected void _doDatapathsEnumerate(@Nonnull Callback<Set<Datapath>> callback,
                                         long timeoutMillis) {
        validateState(callback);

        NetlinkMessage message =
            newMessage(64)
                .addValue(0)
                .build();

        newRequest(datapathFamily, DatapathFamily.Cmd.GET)
            .withFlags(Flag.NLM_F_REQUEST, Flag.NLM_F_ECHO,
                       Flag.NLM_F_DUMP)
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
                            Datapath datapath = deserializeDatapath(buffer);

                            if (datapath != null)
                                datapaths.add(datapath);
                        }

                        return datapaths;
                    }
                }
            )
            .withTimeout(timeoutMillis)
            .send();

    }

    @Override
    protected void _doDatapathsCreate(@Nonnull String name,
                                      @Nonnull Callback<Datapath> callback,
                                      long timeoutMillis) {
        validateState(callback);

        int localPid = getChannel().getLocalAddress().getPid();

        NetlinkMessage message =
            newMessage()
                .addValue(0)
                .addAttr(DatapathFamily.Attr.NAME, name)
                .addAttr(DatapathFamily.Attr.UPCALL_PID, localPid)
                .build();

        newRequest(datapathFamily, DatapathFamily.Cmd.NEW)
            .withFlags(Flag.NLM_F_REQUEST, Flag.NLM_F_ECHO)
            .withPayload(message.buf)
            .withCallback(
                callback,
                new Function<List<ByteBuffer>, Datapath>() {
                    @Override
                    public Datapath apply(@Nullable List<ByteBuffer> input) {
                        if (input == null || input.size() == 0 ||
                            input.get(0) == null) {
                            return null;
                        }

                        return deserializeDatapath(input.get(0));
                    }
                })
            .withTimeout(timeoutMillis)
            .send();

    }

    @Override
    protected void _doDatapathsDelete(Integer datapathId, String name,
                                      @Nonnull Callback<Datapath> callback,
                                      long timeoutMillis) {
        validateState(callback);

        if (datapathId == null && name == null) {
            callback.onError(new OvsDatapathInvalidParametersException(
                "Either a datapath id or a datapath name should be provided"));
            return;
        }

        NetlinkMessage.Builder builder = newMessage();

        builder.addValue(datapathId != null ? datapathId : 0);

        if (name != null) {
            builder.addAttr(DatapathFamily.Attr.NAME, name);
        }

        NetlinkMessage message = builder.build();

        newRequest(datapathFamily, DatapathFamily.Cmd.DEL)
            .withFlags(Flag.NLM_F_REQUEST, Flag.NLM_F_ECHO)
            .withPayload(message.buf)
            .withCallback(
                callback,
                new Function<List<ByteBuffer>, Datapath>() {
                    @Override
                    public Datapath apply(@Nullable List<ByteBuffer> input) {
                        if (input == null || input.size() == 0 ||
                            input.get(0) == null)
                            return null;

                        return deserializeDatapath(input.get(0));
                    }
                })
            .withTimeout(timeoutMillis)
            .send();
    }


    @Override
    protected void _doPortsGet(final @Nullable String name,
                               final @Nullable Integer portId,
                               final @Nullable Datapath datapath,
                               final @Nonnull Callback<Port> callback,
                               final long timeoutMillis) {

        validateState(callback);

        int localPid = getChannel().getLocalAddress().getPid();

        if (name == null && portId == null) {
            callback.onError(
                new OvsDatapathInvalidParametersException(
                    "To get a port data you need to provide either a name " +
                        "or a port id value"));
            return;
        }

        if (name == null && datapath == null) {
            callback.onError(
                new OvsDatapathInvalidParametersException(
                    "When looking at a port by port id you also need to " +
                        "provide a valid datapath object"));
            return;
        }

        final int datapathIndex = datapath == null ? 0 : datapath.getIndex();
        NetlinkMessage.Builder builder = newMessage();
        builder.addValue(datapathIndex);
        builder.addAttr(VPortFamily.Attr.UPCALL_PID, localPid);

        if (portId != null)
            builder.addAttr(VPortFamily.Attr.PORT_NO, portId);

        if (name != null)
            builder.addAttr(VPortFamily.Attr.NAME, name);

        NetlinkMessage message = builder.build();

        newRequest(vPortFamily, VPortFamily.Cmd.GET)
            .withFlags(Flag.NLM_F_REQUEST, Flag.NLM_F_ECHO)
            .withPayload(message.buf)
            .withCallback(
                callback,
                new Function<List<ByteBuffer>, Port>() {
                    @Override
                    public Port apply(@Nullable List<ByteBuffer> input) {
                        if (input == null || input.size() == 0 ||
                            input.get(0) == null)
                            return null;

                        return deserializePort(input.get(0), datapathIndex);
                    }
                })
            .withTimeout(timeoutMillis)
            .send();
    }

    protected void _doPortsSet(@Nonnull final Port port,
                               @Nullable final Datapath datapath,
                               @Nonnull final Callback<Port> callback,
                               final long timeoutMillis) {

        validateState(callback);

        int localPid = getChannel().getLocalAddress().getPid();
        final int datapathIndex = datapath == null ? 0 : datapath.getIndex();

        if (port.getName() == null && datapathIndex == 0) {
            callback.onError(
                new OvsDatapathInvalidParametersException(
                    "Setting a port data by id needs a valid datapath id provided."));
            return;
        }

        NetlinkMessage.Builder builder = newMessage();
        builder.addValue(datapathIndex);
        builder.addAttr(VPortFamily.Attr.UPCALL_PID, localPid);

        if (port.getName() != null )
            builder.addAttr(VPortFamily.Attr.NAME, port.getName());

        if (port.getPortNo() != null )
            builder.addAttr(VPortFamily.Attr.PORT_NO, port.getPortNo());

        if (port.getType() != null)
            builder.addAttr(VPortFamily.Attr.PORT_TYPE, OvsPortType.getOvsPortTypeId(
                port.getType()));

        if (port.getAddress() != null)
            builder.addAttr(VPortFamily.Attr.ADDRESS, port.getAddress());

        if (port.getOptions() != null)
            builder.addAttr(VPortFamily.Attr.OPTIONS, port.getOptions());

        if (port.getStats() != null)
            builder.addAttr(VPortFamily.Attr.STATS, port.getStats());

        NetlinkMessage message = builder.build();

        newRequest(vPortFamily, VPortFamily.Cmd.SET)
            .withFlags(Flag.NLM_F_REQUEST, Flag.NLM_F_ECHO)
            .withPayload(message.buf)
            .withCallback(
                callback,
                new Function<List<ByteBuffer>, Port>() {
                    @Override
                    public Port apply(@Nullable List<ByteBuffer> input) {
                        if (input == null || input.size() == 0 ||
                            input.get(0) == null)
                            return null;

                        return deserializePort(input.get(0), datapathIndex);
                    }
                })
            .withTimeout(timeoutMillis)
            .send();
    }


    @Override
    protected void _doPortsEnumerate(@Nonnull final Datapath datapath,
                                     @Nonnull Callback<Set<Port>> callback,
                                     long timeoutMillis) {
        validateState(callback);

        NetlinkMessage message = newMessage()
            .addValue(datapath.getIndex())
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
                            Port port = deserializePort(buffer,
                                                        datapath.getIndex());

                            if (port == null)
                                continue;

                            ports.add(port);
                        }

                        return ports;
                    }
                })
            .withTimeout(timeoutMillis)
            .send();

    }

    @Override
    protected void _doPortsCreate(@Nonnull final Datapath datapath, @Nonnull Port port,
                                  @Nonnull Callback<Port> callback,
                                  long timeoutMillis) {
        validateState(callback);

        if (port.getName() == null) {
            callback.onError(
                new OvsDatapathInvalidParametersException(
                    "The provided port needs to have the desired name set"));
            return;
        }

        if (port.getType() == null) {
            callback.onError(
                new OvsDatapathInvalidParametersException(
                    "The provided port needs to have the type set"));
            return;
        }

        if (Port.Type
            .Tunnels
            .contains(port.getType()) && port.getOptions() == null) {
            callback.onError(
                new OvsDatapathInvalidParametersException(
                    "A tunnel port needs to have it's options set"));
            return;
        }

        int localPid = getChannel().getLocalAddress().getPid();

        NetlinkMessage.Builder builder = newMessage()
            .addValue(datapath.getIndex())
            .addAttr(VPortFamily.Attr.PORT_TYPE,
                     OvsPortType.getOvsPortTypeId(port.getType()))
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
                    if (input == null || input.size() == 0 || input.get(
                        0) == null)
                        return null;

                    return deserializePort(input.get(0), datapath.getIndex());
                }
            })
            .withTimeout(timeoutMillis)
            .send();

    }

    @Override
    protected void _doDatapathsGet(Integer datapathId, String name,
                                   @Nonnull Callback<Datapath> callback,
                                   long defReplyTimeout) {
        validateState(callback);

        if (datapathId == null && name == null) {
            callback.onError(new OvsDatapathInvalidParametersException(
                "Either a datapath id or a datapath name should be provided"));
            return;
        }

        NetlinkMessage.Builder builder = newMessage();

        builder.addValue(datapathId != null ? datapathId : 0);

        if (name != null) {
            builder.addAttr(DatapathFamily.Attr.NAME, name);
        }

        NetlinkMessage message = builder.build();

        newRequest(datapathFamily, DatapathFamily.Cmd.GET)
            .withFlags(Flag.NLM_F_REQUEST, Flag.NLM_F_ECHO)
            .withPayload(message.buf)
            .withCallback(
                callback,
                new Function<List<ByteBuffer>, Datapath>() {
                    @Override
                    public Datapath apply(@Nullable List<ByteBuffer> input) {
                        if (input == null || input.size() == 0 ||
                            input.get(0) == null)
                            return null;

                        return deserializeDatapath(input.get(0));
                    }
                })
            .send();
    }

    private Port deserializePort(ByteBuffer buffer, int dpIndex) {

        NetlinkMessage msg = new NetlinkMessage(buffer);

        // read the datapath id;
        int actualDpIndex = msg.getInt();
        if (dpIndex != 0 && actualDpIndex != dpIndex)
            return null;

        String name = msg.getAttrValue(VPortFamily.Attr.NAME);
        Integer type = msg.getAttrValue(VPortFamily.Attr.PORT_TYPE);

        if (type == null || name == null)
            return null;

        Port port = Ports.newPortByType(OvsPortType.getOvsPortTypeId(type),
                                        name);

        port.setAddress(msg.getAttrValue(VPortFamily.Attr.ADDRESS));
        port.setPortNo(msg.getAttrValue(VPortFamily.Attr.PORT_NO));

        port.setStats(
            msg.getAttrValue(VPortFamily.Attr.STATS, port.new Stats()));

        //noinspection unchecked
        port.setOptions(msg.getAttrValue(VPortFamily.Attr.OPTIONS,
                                         port.newOptions()));

        return port;
    }

    protected Datapath deserializeDatapath(ByteBuffer buffer) {

        NetlinkMessage msg = new NetlinkMessage(buffer);

        Datapath datapath = new Datapath(
            msg.getInt(),
            msg.getAttrValue(DatapathFamily.Attr.NAME)
        );

        datapath.setStats(
            msg.getAttrValue(DatapathFamily.Attr.STATS, datapath.new Stats()));

        return datapath;
    }

    private enum State {
        Initializing, ErrorInInitialization, Initialized
    }

    private State state;
    private NetlinkException stateInitializationEx;

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

    public boolean isInitialized() {
        return state == State.Initialized;
    }

    private void validateState(Callback callback) {
        switch (state) {
            case Initialized:
                return;
            case ErrorInInitialization:
                callback.onError(stateInitializationEx);
                break;
            case Initializing:
                callback.onError(new OvsDatapathNotInitializedException());
                break;
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
        public void onError(NetlinkException ex) {
            state = State.ErrorInInitialization;
            stateInitializationEx = ex;
        }
    }

    enum OvsPortType {
        //        enum ovs_vport_type {
//            OVS_VPORT_TYPE_UNSPEC,
//            OVS_VPORT_TYPE_NETDEV,   /* network device */
//            OVS_VPORT_TYPE_INTERNAL, /* network device implemented by datapath */
//            OVS_VPORT_TYPE_PATCH = 100, /* virtual tunnel connecting two vports */
//            OVS_VPORT_TYPE_GRE,      /* GRE tunnel */
//            OVS_VPORT_TYPE_CAPWAP,   /* CAPWAP tunnel */
//            __OVS_VPORT_TYPE_MAX
//        };
        NetDev(Port.Type.NetDev, 1),
        Internal(Port.Type.Internal, 2),
        Patch(Port.Type.Patch, 100),
        Gre(Port.Type.Gre, 101),
        CapWap(Port.Type.CapWap, 102);
        private final Port.Type portType;
        private final int ovsKey;

        OvsPortType(Port.Type portType, int ovsKey) {
            this.portType = portType;
            this.ovsKey = ovsKey;
        }

        static Integer getOvsPortTypeId(Port.Type type) {
            for (OvsPortType ovsPortType : OvsPortType.values()) {
                if (ovsPortType.portType == type)
                    return ovsPortType.ovsKey;
            }

            return null;
        }

        static Port.Type getOvsPortTypeId(Integer ovsKey) {
            if (ovsKey == null)
                return null;

            for (OvsPortType ovsPortType : OvsPortType.values()) {
                if (ovsPortType.ovsKey == ovsKey)
                    return ovsPortType.portType;
            }

            return null;
        }
    }
}


