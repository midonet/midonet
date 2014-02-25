/*
 * Copyright (c) 2012 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.odp.protos;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import static java.lang.String.format;

import com.google.common.util.concurrent.ValueFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.netlink.BufferPool;
import org.midonet.netlink.Callback;
import org.midonet.netlink.NetlinkChannel;
import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.exceptions.NetlinkException;
import org.midonet.netlink.messages.Builder;
import org.midonet.odp.Datapath;
import org.midonet.odp.Flow;
import org.midonet.odp.FlowMatch;
import org.midonet.odp.OpenVSwitch;
import org.midonet.odp.Packet;
import org.midonet.odp.DpPort;
import org.midonet.odp.family.DatapathFamily;
import org.midonet.odp.family.FlowFamily;
import org.midonet.odp.family.PacketFamily;
import org.midonet.odp.family.PortFamily;
import org.midonet.odp.flows.FlowAction;
import org.midonet.odp.flows.FlowKey;
import org.midonet.odp.flows.FlowStats;
import org.midonet.util.BatchCollector;
import org.midonet.util.eventloop.Reactor;
import org.midonet.util.functors.Callbacks;
import org.midonet.util.functors.ComposingCallback;
import org.midonet.util.functors.Functor;
import static org.midonet.netlink.Netlink.Flag;
import static org.midonet.odp.family.FlowFamily.AttrKey;


/**
 * Netlink transport aware implementation of a OvsDatapathConnection.
 */
public class OvsDatapathConnectionImpl extends OvsDatapathConnection {

    private static final Logger log =
        LoggerFactory.getLogger(OvsDatapathConnectionImpl.class);

    public static final int FALLBACK_PORT_MULTICAT =
        OpenVSwitch.Port.fallbackMCGroup;

    public OvsDatapathConnectionImpl(NetlinkChannel channel, BufferPool sendPool)
        throws Exception {
        super(channel, sendPool);
    }

    @Override
    protected void handleNotification(short type, byte cmd, int seq, int pid,
                                      List<ByteBuffer> buffers) {

        if (pid == 0 &&
            packetFamily.familyId == type &&
            (packetFamily.contextMiss.command() == cmd ||
                packetFamily.contextAction.command() == cmd)) {
            if (notificationHandler != null) {
                Packet packet = null;

                if (buffers == null || buffers.size() != 1)
                    return;

                packet = deserializePacket(buffers.get(0));
                if (packet == null) {
                    log.info("Discarding malformed packet");
                    return;
                }

                if (packetFamily.contextAction.command() == cmd) {
                    packet.setReason(Packet.Reason.FlowActionUserspace);
                } else {
                    packet.setReason(Packet.Reason.FlowTableMiss);
                }

                notificationHandler.submit(packet);
            }
        } else {
            super.handleNotification(type, cmd, seq, pid, buffers);
        }
    }

    @Override
    protected void _doDatapathsSetNotificationHandler(@Nonnull final Datapath datapath,
                                                      @Nonnull BatchCollector<Packet> notificationHandler,
                                                      @Nonnull final Callback<Boolean> installCallback,
                                                      final long timeoutMillis) {
        this.notificationHandler = notificationHandler;

        _doPortsEnumerate(datapath, new Callback<Set<DpPort>>() {
            @Override
            public void onSuccess(final Set<DpPort> data) {
                if (data == null || data.isEmpty()) {
                    installCallback.onSuccess(true);
                    return;
                }

                ComposingCallback<DpPort, NetlinkException> portsSetCallback =
                    Callbacks.composeTo(
                        Callbacks.transform(
                            installCallback,
                            new Functor<MultiResult<DpPort>, Boolean>() {
                                @Override
                                public Boolean apply(MultiResult<DpPort> arg0) {
                                    return true;
                                }
                            }));

                for (DpPort port : data) {
                    @SuppressWarnings("unchecked")
                    Callback<DpPort> callback =
                        portsSetCallback.createCallback(
                            format("SET upcall_id on port: {}", port.getName()),
                            Callback.class
                        );

                    _doPortsSet(port, datapath, callback, timeoutMillis);
                }

                portsSetCallback.enableResultCollection();
            }

            @Override
            public void onTimeout() {
                installCallback.onTimeout();
            }

            @Override
            public void onError(NetlinkException e) {
                installCallback.onError(e);
            }
        }, timeoutMillis);
    }

    DatapathFamily datapathFamily;
    PortFamily portFamily;
    FlowFamily flowFamily;
    PacketFamily packetFamily;

    int datapathMulticast;
    int portMulticast;

    private BatchCollector<Packet> notificationHandler;

    @Override
    protected void endBatch() {
        if (notificationHandler != null)
            notificationHandler.endBatch();
    }

    @Override
    protected void _doDatapathsGet(Integer datapathId, String name,
                                   @Nonnull Callback<Datapath> callback,
                                   long timeoutMillis) {
        if (!validateState(callback))
            return;

        if (datapathId == null && name == null) {
            callback.onError(new OvsDatapathInvalidParametersException(
                "Either a datapath id or a datapath name should be provided"));
            return;
        }

        int dpId = datapathId != null ? datapathId : 0;

        sendNetlinkMessage(
            datapathFamily.contextGet,
            Flag.or(Flag.NLM_F_REQUEST, Flag.NLM_F_ECHO),
            Datapath.getRequest(getBuffer(), dpId, name),
            callback,
            Datapath.deserializer,
            timeoutMillis);
    }

    @Override
    protected void _doDatapathsEnumerate(@Nonnull Callback<Set<Datapath>> callback,
                                         long timeoutMillis) {
        if (!validateState(callback))
            return;

        sendNetlinkMessage(
            datapathFamily.contextGet,
            Flag.or(Flag.NLM_F_REQUEST, Flag.NLM_F_ECHO, Flag.NLM_F_DUMP),
            Datapath.enumRequest(getBuffer()),
            callback,
            Datapath.setDeserializer,
            timeoutMillis);
    }

    @Override
    protected void _doDatapathsCreate(@Nonnull String name,
                                      @Nonnull Callback<Datapath> callback,
                                      long timeoutMillis) {
        if (!validateState(callback))
            return;

        int localPid = getChannel().getLocalAddress().getPid();

        sendNetlinkMessage(
            datapathFamily.contextNew,
            Flag.or(Flag.NLM_F_REQUEST, Flag.NLM_F_ECHO),
            Datapath.createRequest(getBuffer(), localPid, name),
            callback,
            Datapath.deserializer,
            timeoutMillis);
    }

    @Override
    protected void _doDatapathsDelete(Integer datapathId, String name,
                                      @Nonnull Callback<Datapath> callback,
                                      long timeoutMillis) {
        if (!validateState(callback))
            return;

        if (datapathId == null && name == null) {
            callback.onError(new OvsDatapathInvalidParametersException(
                "Either a datapath id or a datapath name should be provided"));
            return;
        }

        int dpId = datapathId != null ? datapathId : 0;

        sendNetlinkMessage(
            datapathFamily.contextDel,
            Flag.or(Flag.NLM_F_REQUEST, Flag.NLM_F_ECHO),
            Datapath.getRequest(getBuffer(), dpId, name),
            callback,
            Datapath.deserializer,
            timeoutMillis);
    }

    @Override
    protected void _doPortsGet(final @Nullable String name,
                               final @Nullable Integer portId,
                               final @Nullable Datapath datapath,
                               final @Nonnull Callback<DpPort> callback,
                               final long timeoutMillis) {
        if (!validateState(callback))
            return;

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
        Builder builder = newMessage();
        builder.addValue(datapathIndex);
        builder.addAttr(PortFamily.Attr.UPCALL_PID, localPid);

        if (portId != null)
            builder.addAttr(PortFamily.Attr.PORT_NO, portId);

        if (name != null)
            builder.addAttr(PortFamily.Attr.NAME, name);

        NetlinkMessage message = builder.build();

        sendNetlinkMessage(
            portFamily.contextGet,
            Flag.or(Flag.NLM_F_REQUEST, Flag.NLM_F_ECHO),
            message.getBuffer(),
            callback,
            DpPort.deserializer,
            timeoutMillis);
    }

    @Override
    protected void _doPortsDelete(@Nonnull DpPort port, @Nullable Datapath datapath,
                                  @Nonnull Callback<DpPort> callback, long timeoutMillis) {

        final int datapathIndex = datapath == null ? 0 : datapath.getIndex();
        Builder builder = newMessage();
        builder.addValue(datapathIndex);
        builder.addAttr(PortFamily.Attr.PORT_NO, port.getPortNo());

        sendNetlinkMessage(
            portFamily.contextDel,
            Flag.or(Flag.NLM_F_REQUEST, Flag.NLM_F_ECHO),
            builder.build().getBuffer(),
            callback,
            DpPort.deserializer,
            timeoutMillis);
    }

    @Override
    protected void _doPortsSet(@Nonnull final DpPort port,
                               @Nullable final Datapath datapath,
                               @Nonnull final Callback<DpPort> callback,
                               final long timeoutMillis) {
        if (!validateState(callback))
            return;

        int localPid = getChannel().getLocalAddress().getPid();
        final int datapathIndex = datapath == null ? 0 : datapath.getIndex();

        if (port.getName() == null && datapathIndex == 0) {
            callback.onError(
                new OvsDatapathInvalidParametersException(
                    "Setting a port data by id needs a valid datapath id provided."));
            return;
        }

        Builder builder = newMessage();
        builder.addValue(datapathIndex);
        builder.addAttr(PortFamily.Attr.UPCALL_PID, localPid);

        port.serializeInto(builder);

        NetlinkMessage message = builder.build();

        sendNetlinkMessage(
            portFamily.contextSet,
            Flag.or(Flag.NLM_F_REQUEST, Flag.NLM_F_ECHO),
            message.getBuffer(),
            callback,
            DpPort.deserializer,
            timeoutMillis);
    }


    @Override
    protected void _doPortsEnumerate(@Nonnull final Datapath datapath,
                                     @Nonnull Callback<Set<DpPort>> callback,
                                     long timeoutMillis) {
        if (!validateState(callback))
            return;

        Builder builder = newMessage();
        builder.addValue(datapath.getIndex());
        NetlinkMessage message = builder.build();

        sendNetlinkMessage(
            portFamily.contextGet,
            Flag.or(Flag.NLM_F_DUMP, Flag.NLM_F_ACK,
                    Flag.NLM_F_REQUEST, Flag.NLM_F_ECHO),
            message.getBuffer(),
            callback,
            DpPort.setDeserializer,
            timeoutMillis);
    }

    @Override
    protected void _doPortsCreate(@Nonnull final Datapath datapath,
                                  @Nonnull DpPort port,
                                  @Nonnull Callback<DpPort> callback,
                                  long timeoutMillis) {
        if (!validateState(callback))
            return;

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

        int localPid = getChannel().getLocalAddress().getPid();

        Builder builder = newMessage();
        builder.addValue(datapath.getIndex());
        builder.addAttr(PortFamily.Attr.UPCALL_PID, localPid);

        port.serializeInto(builder);

        NetlinkMessage message = builder.build();

        sendNetlinkMessage(
            portFamily.contextNew,
            Flag.or(Flag.NLM_F_REQUEST, Flag.NLM_F_ECHO),
            message.getBuffer(),
            callback,
            DpPort.deserializer,
            timeoutMillis);
    }

    @Override
    protected void _doFlowsEnumerate(@Nonnull Datapath datapath,
                                     @Nonnull Callback<Set<Flow>> callback,
                                     long timeoutMillis) {
        if (!validateState(callback))
            return;

        final int datapathId = datapath.getIndex() != null ? datapath.getIndex() : 0;

        if (datapathId == 0) {
            callback.onError(
                new OvsDatapathInvalidParametersException(
                    "The datapath to dump flows for needs a valid datapath id"));
            return;
        }

        Builder builder = newMessage();
        builder.addValue(datapathId);
        NetlinkMessage message = builder.build();

        sendNetlinkMessage(
            flowFamily.contextGet,
            Flag.or(Flag.NLM_F_ACK, Flag.NLM_F_REQUEST,
                    Flag.NLM_F_ECHO, Flag.NLM_F_DUMP),
            message.getBuffer(),
            callback,
            Flow.setDeserializer,
            timeoutMillis);
    }

    @Override
    protected void _doFlowsCreate(@Nonnull final Datapath datapath,
                                  @Nonnull final Flow flow,
                                  final Callback<Flow> callback,
                                  final long timeoutMillis) {
        if (!validateState(callback))
            return;

        final int datapathId = datapath.getIndex() != null ? datapath.getIndex() : 0;

        if (datapathId == 0) {
            NetlinkException ex = new OvsDatapathInvalidParametersException(
                    "The datapath to dump flows for needs a valid datapath id");
            propagateError(callback, ex);
            return;
        }

        Builder builder = newMessage();
        builder.addValue(datapathId);
        builder.addAttrs(AttrKey.ACTIONS, flow.getActions());

        FlowMatch match = flow.getMatch();
        if (match != null)
            builder.addAttrs(AttrKey.KEY, match.getKeys());

        NetlinkMessage message = builder.build();

        short flags = 0;
        if (callback != null) {
            flags = (short) (Flag.NLM_F_CREATE.getValue() |
                             Flag.NLM_F_REQUEST.getValue() |
                             Flag.NLM_F_ECHO.getValue());
        } else {
            flags = (short) (Flag.NLM_F_CREATE.getValue() |
                             Flag.NLM_F_REQUEST.getValue());
        }

        sendNetlinkMessage(
            flowFamily.contextNew,
            flags,
            message.getBuffer(),
            callback,
            Flow.deserializer,
            timeoutMillis);
    }

    @Override
    protected void _doFlowsDelete(@Nonnull final Datapath datapath,
                                  @Nonnull final Flow flow,
                                  @Nonnull final Callback<Flow> callback,
                                  final long timeoutMillis) {
        if (!validateState(callback))
            return;

        final int datapathId = datapath.getIndex() != null ? datapath.getIndex() : 0;

        if (datapathId == 0) {
            callback.onError(
                new OvsDatapathInvalidParametersException(
                    "The datapath to dump flows for needs a valid datapath id"));
            return;
        }

        FlowMatch match = flow.getMatch();

        if ( match == null ) {
            callback.onError(
                new OvsDatapathInvalidParametersException(
                    "The flow to delete should have a non null FlowMatch attached"));
            return;
        }

        Builder builder = newMessage();
        builder.addValue(datapathId);
        builder.addAttrs(AttrKey.KEY, match.getKeys());

        NetlinkMessage message = builder.build();

        sendNetlinkMessage(
            flowFamily.contextDel,
            (short) (Flag.NLM_F_REQUEST.getValue() | Flag.NLM_F_ECHO.getValue()),
            message.getBuffer(),
            callback,
            Flow.deserializer,
            timeoutMillis);
    }


    @Override
    protected void _doFlowsFlush(@Nonnull final Datapath datapath,
                                 @Nonnull final Callback<Boolean> callback,
                                 long timeoutMillis) {
        if (!validateState(callback))
            return;

        final int datapathId = datapath.getIndex() != null ? datapath.getIndex() : 0;

        if (datapathId == 0) {
            callback.onError(
                new OvsDatapathInvalidParametersException(
                    "The datapath to dump flows for needs a valid datapath id"));
            return;
        }

        Builder builder = newMessage();
        builder.addValue(datapathId);
        NetlinkMessage message = builder.build();

        sendNetlinkMessage(
            flowFamily.contextDel,
            Flag.or(Flag.NLM_F_REQUEST, Flag.NLM_F_ACK),
            message.getBuffer(),
            callback,
            alwaysTrueTranslator,
            timeoutMillis);
    }

    @Override
    protected void _doFlowsGet(@Nonnull Datapath datapath, @Nonnull FlowMatch match,
                               @Nonnull Callback<Flow> callback, long timeoutMillis) {

        if (!validateState(callback))
            return;

        final int datapathId = datapath.getIndex() != null ? datapath.getIndex() : 0;

        if (datapathId == 0) {
            callback.onError(
                new OvsDatapathInvalidParametersException(
                    "The datapath to get the flow from needs a valid datapath id"));
            return;
        }

        Builder builder = newMessage();
        builder.addValue(datapathId);
        builder.addAttrs(AttrKey.KEY, match.getKeys());

        sendNetlinkMessage(
            flowFamily.contextGet,
            Flag.or(Flag.NLM_F_REQUEST, Flag.NLM_F_ECHO),
            builder.build().getBuffer(),
            callback,
            Flow.deserializer,
            timeoutMillis);
    }

    @Override
    protected void _doFlowsSet(@Nonnull final Datapath datapath,
                               @Nonnull final Flow flow,
                               @Nonnull final Callback<Flow> callback,
                               long timeoutMillis) {

        if (!validateState(callback))
            return;

        final int datapathId = datapath.getIndex() != null ? datapath.getIndex() : 0;

        if (datapathId == 0) {
            callback.onError(
                new OvsDatapathInvalidParametersException(
                    "The datapath to get the flow from needs a valid datapath id"));
            return;
        }

        FlowMatch flowMatch = flow.getMatch();

        if (flowMatch == null || flowMatch.getKeys().isEmpty()) {
            callback.onError(
                new OvsDatapathInvalidParametersException(
                    "The flow should have a FlowMatch object set up (with non empty key set)."
                )
            );
            return;
        }

        Builder builder = newMessage();
        builder.addValue(datapathId);
        builder.addAttrs(AttrKey.KEY, flowMatch.getKeys());

        if (!flow.getActions().isEmpty())
            builder.addAttrs(AttrKey.ACTIONS, flow.getActions());

        sendNetlinkMessage(
            flowFamily.contextSet,
            Flag.or(Flag.NLM_F_REQUEST, Flag.NLM_F_ECHO),
            builder.build().getBuffer(),
            callback,
            Flow.deserializer,
            timeoutMillis);
    }

    private void propagateError(Callback<?> callback, NetlinkException ex) {
        if (callback != null)
            callback.onError(ex);
        else
            throw new RuntimeException(ex);
    }

    @Override
    protected void _doPacketsExecute(@Nonnull Datapath datapath,
                                     @Nonnull Packet packet,
                                     Callback<Boolean> callback,
                                     long timeoutMillis) {
        if (!validateState(callback))
            return;

        final int datapathId = datapath.getIndex() != null ? datapath.getIndex() : 0;

        if (datapathId == 0) {
            NetlinkException ex = new OvsDatapathInvalidParametersException(
                "The datapath to get the flow from needs a valid datapath id");
            propagateError(callback, ex);
            return;
        }

        FlowMatch flowMatch = packet.getMatch();

        if (flowMatch.getKeys().isEmpty()) {
            NetlinkException ex = new OvsDatapathInvalidParametersException(
                "The packet should have a FlowMatch object set up (with non empty key set).");
            propagateError(callback, ex);
            return;
        }

        if (packet.getActions() == null || packet.getActions().isEmpty()) {
            NetlinkException ex = new OvsDatapathInvalidParametersException(
                    "The packet should have an action set up.");
            propagateError(callback, ex);
            return;
        }

        Builder builder = newMessage();
        builder.addValue(datapathId);
        builder.addAttrs(PacketFamily.AttrKey.KEY, flowMatch.getKeys());
        builder.addAttrs(PacketFamily.AttrKey.ACTIONS, packet.getActions());
        // TODO(pino): find out why ovs_packet_cmd_execute throws an
        // EINVAL if we put the PACKET attribute right after the
        // datapathId. I examined the ByteBuffers constructed with that
        // ordering of attributes and compared it to this one, and found
        // only the expected difference.
        builder.addAttr(PacketFamily.AttrKey.PACKET, packet.getPacket());

        NetlinkMessage message = builder.build();

        short flags = 0;
        if (callback != null) {
            flags = (short) (Flag.NLM_F_REQUEST.getValue() |
                             Flag.NLM_F_ACK.getValue());
        } else {
            flags = Flag.NLM_F_REQUEST.getValue();
        }

        sendNetlinkMessage(
            packetFamily.contextExec,
            flags,
            message.getBuffer(),
            callback,
            alwaysTrueTranslator,
            timeoutMillis);
    }

    private Packet deserializePacket(ByteBuffer buffer) {
        Packet packet = new Packet();

        NetlinkMessage msg = new NetlinkMessage(buffer);

        int datapathIndex = msg.getInt();
        packet
            .setPacket(msg.getAttrValueEthernet(PacketFamily.AttrKey.PACKET))
            .setMatch(
                new FlowMatch(
                    msg.getAttrValue(PacketFamily.AttrKey.KEY,
                                     FlowKey.Builder)))
            .setActions(
                msg.getAttrValue(
                    PacketFamily.AttrKey.ACTIONS, FlowAction.Builder))
            .setUserData(
                msg.getAttrValueLong(PacketFamily.AttrKey.USERDATA));

        return packet.getPacket() != null ? packet : null;
    }

    private enum State {
        Initializing, ErrorInInitialization, Initialized
    }

    private State state;
    private NetlinkException stateInitializationEx;

    public Future<Boolean> initialize() throws Exception {

        final ValueFuture<Boolean> future = ValueFuture.create();
        final Callback<Boolean> initStatusCallback = wrapFuture(future);

        state = State.Initializing;

        final Callback<Integer> portMulticastCallback =
            new StateAwareCallback<Integer>(initStatusCallback) {
                @Override
                public void onSuccess(Integer data) {

                    log.debug("Got port multicast group: {}.", data);
                    if (data != null) {
                        OvsDatapathConnectionImpl.this.portMulticast = data;
                    } else {
                        log.info(
                            "Setting the port multicast group to fallback value: {}",
                            PortFamily.FALLBACK_MC_GROUP);

                        OvsDatapathConnectionImpl.this.portMulticast =
                            PortFamily.FALLBACK_MC_GROUP;
                    }

                    state = State.Initialized;
                    initStatusCallback.onSuccess(true);
                }
            };

        final Callback<Integer> datapathMulticastCallback =
            new StateAwareCallback<Integer>(initStatusCallback) {
                @Override
                public void onSuccess(Integer data) {
                    log.debug("Got datapath multicast group: {}.", data);
                    if (data != null)
                        OvsDatapathConnectionImpl.this.datapathMulticast = data;

                    getMulticastGroup(PortFamily.NAME, PortFamily.MC_GROUP,
                                      portMulticastCallback);
                }
            };

        final Callback<Short> packetFamilyBuilder =
            new StateAwareCallback<Short>(initStatusCallback) {
                @Override
                public void onSuccess(Short data) {
                    packetFamily = new PacketFamily(data);
                    log.debug("Got packet family id: {}.", data);
                    getMulticastGroup(DatapathFamily.NAME,
                                      DatapathFamily.MC_GROUP,
                                      datapathMulticastCallback);
                }
            };

        final Callback<Short> flowFamilyBuilder =
            new StateAwareCallback<Short>(initStatusCallback) {
                @Override
                public void onSuccess(Short data) {
                    flowFamily = new FlowFamily(data);
                    log.debug("Got flow family id: {}.", data);
                    getFamilyId(PacketFamily.NAME, packetFamilyBuilder);
                }
            };

        final Callback<Short> portFamilyBuilder =
            new StateAwareCallback<Short>(initStatusCallback) {
                @Override
                public void onSuccess(Short data) {
                    portFamily = new PortFamily(data);
                    log.debug("Got port family id: {}.", data);
                    getFamilyId(FlowFamily.NAME, flowFamilyBuilder);
                }
            };

        final Callback<Short> datapathFamilyBuilder =
            new StateAwareCallback<Short>(initStatusCallback) {
                @Override
                public void onSuccess(Short data) {
                    datapathFamily = new DatapathFamily(data);
                    log.debug("Got datapath family id: {}.", data);
                    getFamilyId(PortFamily.NAME, portFamilyBuilder);
                }
            };

        getFamilyId(DatapathFamily.NAME, datapathFamilyBuilder);
        return future;
    }

    public boolean isInitialized() {
        return state == State.Initialized;
    }

    private boolean validateState(Callback<?> callback) {
        switch (state) {
            case ErrorInInitialization:
                propagateError(callback, stateInitializationEx);
                return false;
            case Initializing:
                propagateError(callback, new OvsDatapathNotInitializedException());
                return false;
        }

        return true;
    }

    private class StateAwareCallback<T> implements Callback<T> {

        Callback<Boolean> statusCallback;

        public StateAwareCallback() {
            this(null);
        }

        public StateAwareCallback(Callback<Boolean> statusCallback) {
            this.statusCallback = statusCallback;
        }

        @Override
        public void onSuccess(T data) {
            statusCallback.onSuccess(Boolean.TRUE);
        }

        @Override
        public void onTimeout() {
            state = State.ErrorInInitialization;
            if (statusCallback != null)
                statusCallback.onTimeout();
        }

        @Override
        public void onError(NetlinkException ex) {
            state = State.ErrorInInitialization;
            stateInitializationEx = ex;
            if (statusCallback != null)
                statusCallback.onError(ex);
        }
    }

}
