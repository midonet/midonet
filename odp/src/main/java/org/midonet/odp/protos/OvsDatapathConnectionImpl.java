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
package org.midonet.odp.protos;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.netlink.BufferPool;
import org.midonet.netlink.Callback;
import org.midonet.netlink.NLFlag;
import org.midonet.netlink.NetlinkChannel;
import org.midonet.netlink.exceptions.NetlinkException;
import org.midonet.odp.OpenVSwitch;
import org.midonet.odp.Datapath;
import org.midonet.odp.DpPort;
import org.midonet.odp.Flow;
import org.midonet.odp.FlowMatch;
import org.midonet.odp.OvsProtocol;
import org.midonet.odp.Packet;
import org.midonet.odp.family.DatapathFamily;
import org.midonet.odp.family.FlowFamily;
import org.midonet.odp.family.PacketFamily;
import org.midonet.odp.family.PortFamily;
import org.midonet.odp.flows.FlowAction;
import org.midonet.odp.flows.FlowKey;
import org.midonet.util.BatchCollector;

/**
 * Netlink transport aware implementation of a OvsDatapathConnection.
 */
public class OvsDatapathConnectionImpl extends OvsDatapathConnection {

    private static final Logger log =
        LoggerFactory.getLogger("org.midonet.netlink.odp-conn");

    public OvsDatapathConnectionImpl(NetlinkChannel channel, BufferPool sendPool) {
        super(channel, sendPool);
    }

    @Override
    protected void handleNotification(short type, byte cmd, int seq, int pid,
                                      ByteBuffer buffer) {

        if (pid == 0 &&
            packetFamily.familyId == type &&
            (packetFamily.contextMiss.command() == cmd ||
                packetFamily.contextAction.command() == cmd)) {
            if (notificationHandler != null) {
                Packet packet = Packet.buildFrom(buffer);
                if (packet == null)
                    return;

                if (packetFamily.contextAction.command() == cmd) {
                    packet.setReason(Packet.Reason.FlowActionUserspace);
                } else {
                    packet.setReason(Packet.Reason.FlowTableMiss);
                }

                notificationHandler.submit(packet);
            }
        } else {
            log.error("Cannot handle notification for: {family: {}, cmd: {}}",
                      type, cmd);
        }
    }

    @Override
    protected void _doDatapathsSetNotificationHandler(@Nonnull BatchCollector<Packet> notificationHandler) {
        this.notificationHandler = notificationHandler;
    }

    private OvsProtocol protocol;
    private PacketFamily packetFamily;

    private int datapathMulticast;
    private int portMulticast;

    private BatchCollector<Packet> notificationHandler;

    private boolean initialized = false;

    @Override
    protected void endBatch() {
        if (notificationHandler != null)
            notificationHandler.endBatch();
    }

    @Override
    protected void _doDatapathsGet(String name,
                                   @Nonnull Callback<Datapath> callback,
                                   long timeoutMillis) {
        if (name == null) {
            callback.onError(new OvsDatapathInvalidParametersException(
                "given datapath name was null"));
            return;
        }

        ByteBuffer buf = getBuffer();
        protocol.prepareDatapathGet(0, name, buf);
        sendNetlinkMessage(buf, callback, Datapath.deserializer, timeoutMillis);
    }

    @Override
    protected void _doDatapathsGet(int datapathId,
                                   @Nonnull Callback<Datapath> callback,
                                   long timeoutMillis) {
        if (datapathId == 0) {
            callback.onError(new OvsDatapathInvalidParametersException(
                "datapath id should not be 0"));
            return;
        }

        ByteBuffer buf = getBuffer();
        protocol.prepareDatapathGet(datapathId, null, buf);
        sendNetlinkMessage(buf, callback, Datapath.deserializer, timeoutMillis);
    }

    @Override
    protected void _doDatapathsEnumerate(@Nonnull Callback<Set<Datapath>> callback,
                                         long timeoutMillis) {
        ByteBuffer buf = getBuffer();
        protocol.prepareDatapathEnumerate(buf);
        sendMultiAnswerNetlinkMessage(buf, callback, Datapath.deserializer, timeoutMillis);
    }

    @Override
    protected void _doDatapathsCreate(@Nonnull String name,
                                      @Nonnull Callback<Datapath> callback,
                                      long timeoutMillis) {
        ByteBuffer buf = getBuffer();
        protocol.prepareDatapathCreate(name, buf);
        sendNetlinkMessage(buf, callback, Datapath.deserializer, timeoutMillis);
    }

    @Override
    protected void _doDatapathsDelete(String name,
                                      @Nonnull Callback<Datapath> callback,
                                      long timeoutMillis) {
        if (name == null) {
            callback.onError(new OvsDatapathInvalidParametersException(
                "given datapath name was null"));
            return;
        }

        ByteBuffer buf = getBuffer();
        protocol.prepareDatapathDel(0, name, buf);
        sendNetlinkMessage(buf, callback, Datapath.deserializer, timeoutMillis);
    }

    @Override
    protected void _doDatapathsDelete(int datapathId,
                                      @Nonnull Callback<Datapath> callback,
                                      long timeoutMillis) {
        if (datapathId == 0) {
            callback.onError(new OvsDatapathInvalidParametersException(
                "datapath id should not be 0"));
            return;
        }

        ByteBuffer buf = getBuffer();
        protocol.prepareDatapathDel(datapathId, null, buf);
        sendNetlinkMessage(buf, callback, Datapath.deserializer, timeoutMillis);
    }

    @Override
    protected void _doPortsGet(final @Nullable String name,
                               final @Nullable Integer portId,
                               final @Nullable Datapath datapath,
                               final @Nonnull Callback<DpPort> callback,
                               final long timeoutMillis) {
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

        int datapathId = datapath == null ? 0 : datapath.getIndex();
        ByteBuffer buf = getBuffer();
        protocol.prepareDpPortGet(datapathId, portId, name, buf);
        sendNetlinkMessage(buf, callback, DpPort.deserializer, timeoutMillis);
    }

    @Override
    protected void _doPortsDelete(@Nonnull DpPort port,
                                  @Nullable Datapath datapath,
                                  @Nonnull Callback<DpPort> callback,
                                  long timeoutMillis) {

        int datapathId = datapath == null ? 0 : datapath.getIndex();
        ByteBuffer buf = getBuffer();
        protocol.prepareDpPortDelete(datapathId, port, buf);
        sendNetlinkMessage(buf, callback, DpPort.deserializer, timeoutMillis);
    }

    @Override
    protected void _doPortsSet(@Nonnull final DpPort port,
                               @Nullable final Datapath datapath,
                               @Nonnull final Callback<DpPort> callback,
                               final long timeoutMillis) {
        int datapathId = datapath == null ? 0 : datapath.getIndex();

        if (port.getName() == null && datapathId == 0) {
            callback.onError(
                new OvsDatapathInvalidParametersException(
                    "Setting a port data by id needs a valid datapath id provided."));
            return;
        }

        ByteBuffer buf = getBuffer();
        protocol.prepareDpPortCreate(datapathId, port, buf);
        sendNetlinkMessage(buf, callback, DpPort.deserializer, timeoutMillis);
    }


    @Override
    protected void _doPortsEnumerate(@Nonnull final Datapath datapath,
                                     @Nonnull Callback<Set<DpPort>> callback,
                                     long timeoutMillis) {
        ByteBuffer buf = getBuffer();
        protocol.prepareDpPortEnum(datapath.getIndex(), buf);
        sendMultiAnswerNetlinkMessage(buf, callback, DpPort.deserializer, timeoutMillis);
    }

    @Override
    protected void _doPortsCreate(@Nonnull final Datapath datapath,
                                  @Nonnull DpPort port,
                                  @Nonnull Callback<DpPort> callback,
                                  long timeoutMillis) {
        ByteBuffer buf = getBuffer();
        protocol.prepareDpPortCreate(datapath.getIndex(), port, buf);
        sendNetlinkMessage(buf, callback, DpPort.deserializer, timeoutMillis);
    }

    @Override
    protected void _doFlowsEnumerate(@Nonnull Datapath datapath,
                                     @Nonnull Callback<Set<Flow>> callback,
                                     long timeoutMillis) {
        int datapathId = datapath.getIndex();

        if (datapathId == 0) {
            callback.onError(
                new OvsDatapathInvalidParametersException(
                    "The datapath to dump flows for needs a valid datapath id"));
            return;
        }

        ByteBuffer buf = getBuffer();
        protocol.prepareFlowEnum(datapathId, buf);
        sendMultiAnswerNetlinkMessage(buf, callback, Flow.deserializer, timeoutMillis);
    }

    @Override
    protected void _doFlowsCreate(@Nonnull final Datapath datapath,
                                  @Nonnull final Flow flow,
                                  Callback<Flow> callback,
                                  final long timeoutMillis) {
        int datapathId = datapath.getIndex();

        if (datapathId == 0) {
            NetlinkException ex = new OvsDatapathInvalidParametersException(
                    "The datapath to dump flows for needs a valid datapath id");
            propagateError(callback, ex);
            return;
        }

        // allows to see failing flow create requests if debug logging is on.
        if (callback == null && log.isDebugEnabled()) {
            callback = new LoggingCallback<Flow>() {
                public String requestString() { return "flow create"; }
                public String dataString() { return flow.toString(); }
            };
        }

        ByteBuffer buf = getBuffer();
        protocol.prepareFlowCreate(datapathId, flow, callback != null, buf);
        sendNetlinkMessage(buf, callback, Flow.deserializer, timeoutMillis);
    }

    @Override
    protected void _doFlowsDelete(@Nonnull final Datapath datapath,
                                  @Nonnull final Iterable<FlowKey> keys,
                                  @Nonnull final Callback<Boolean> callback,
                                  final long timeoutMillis) {
        int datapathId = datapath.getIndex();

        if (datapathId == 0) {
            callback.onError(
                new OvsDatapathInvalidParametersException(
                    "The datapath to dump flows for needs a valid datapath id"));
            return;
        }

        ByteBuffer buf = getBuffer();
        protocol.prepareFlowDelete(datapathId, keys, buf);
        sendNetlinkMessage(buf, callback, alwaysTrueReader, timeoutMillis);
    }

    @Override
    protected void _doFlowsFlush(@Nonnull final Datapath datapath,
                                 @Nonnull final Callback<Boolean> callback,
                                 long timeoutMillis) {
        int datapathId = datapath.getIndex();

        if (datapathId == 0) {
            callback.onError(
                new OvsDatapathInvalidParametersException(
                    "The datapath to dump flows for needs a valid datapath id"));
            return;
        }

        ByteBuffer buf = getBuffer();
        protocol.prepareFlowFlush(datapathId, buf);
        sendNetlinkMessage(buf, callback, alwaysTrueReader, timeoutMillis);
    }

    @Override
    protected void _doFlowsGet(@Nonnull Datapath datapath, @Nonnull FlowMatch match,
                               @Nonnull Callback<Flow> callback, long timeoutMillis) {

        int datapathId = datapath.getIndex();

        if (datapathId == 0) {
            callback.onError(
                new OvsDatapathInvalidParametersException(
                    "The datapath to get the flow from needs a valid datapath id"));
            return;
        }

        ByteBuffer buf = getBuffer();
        protocol.prepareFlowGet(datapathId, match, buf);
        sendNetlinkMessage(buf, callback, Flow.deserializer, timeoutMillis);
    }

    @Override
    protected void _doFlowsSet(@Nonnull final Datapath datapath,
                               @Nonnull final Flow flow,
                               @Nonnull final Callback<Flow> callback,
                               long timeoutMillis) {

        int datapathId = datapath.getIndex();

        if (datapathId == 0) {
            callback.onError(
                new OvsDatapathInvalidParametersException(
                    "The datapath to get the flow from needs a valid datapath id"));
            return;
        }

        if (flow.hasEmptyMatch()) {
            callback.onError(
                new OvsDatapathInvalidParametersException(
                    "The flow should have a FlowMatch object set up (with non empty key set)."
                )
            );
            return;
        }

        ByteBuffer buf = getBuffer();
        protocol.prepareFlowSet(datapathId, flow, buf);
        sendNetlinkMessage(buf, callback, Flow.deserializer, timeoutMillis);
    }

    private void propagateError(Callback<?> callback, NetlinkException ex) {
        if (callback != null)
            callback.onError(ex);
        else
            throw new RuntimeException(ex);
    }

    @Override
    protected void _doPacketsExecute(@Nonnull Datapath datapath,
                                     @Nonnull final Packet packet,
                                     @Nonnull final List<FlowAction> actions,
                                     Callback<Boolean> callback,
                                     long timeoutMillis) {
        int datapathId = datapath.getIndex();

        if (datapathId == 0) {
            NetlinkException ex = new OvsDatapathInvalidParametersException(
                "The datapath to get the flow from needs a valid datapath id");
            propagateError(callback, ex);
            return;
        }

        List<FlowKey> keys = packet.getMatch().getKeys();

        if (keys.isEmpty()) {
            NetlinkException ex = new OvsDatapathInvalidParametersException(
                "The packet should have a FlowMatch object set up (with non empty key set).");
            propagateError(callback, ex);
            return;
        }

        if (actions == null || actions.isEmpty()) {
            NetlinkException ex = new OvsDatapathInvalidParametersException(
                    "The packet should have an action set up.");
            propagateError(callback, ex);
            return;
        }

        // allows to see failing packet execute requests if debug logging is on.
        if (callback == null && log.isDebugEnabled()) {
            callback = new LoggingCallback<Boolean>() {
                public String requestString() {
                    return "packet execute";
                }
                public String dataString() {
                    return Arrays.toString(actions.toArray()) + " on " + packet;
                }
            };
        }

        ByteBuffer buf = getBuffer();
        protocol.preparePacketExecute(datapathId, packet, actions,
                                      callback != null, buf);
        sendNetlinkMessage(buf, callback, alwaysTrueReader, timeoutMillis);
    }

    @Override
    public void initialize(final Callback<Boolean> initStatusCallback) {
        final DatapathFamily[] datapathFamily = new DatapathFamily[1];
        final PortFamily[] portFamily = new PortFamily[1];
        final FlowFamily[] flowFamily = new FlowFamily[1];

        final Callback<Integer> portMulticastCallback =
            new ChainedCallback<Integer>(initStatusCallback) {
                @Override
                public void onSuccess(Integer data) {

                    log.debug("Got port multicast group: {}.", data);
                    if (data != null) {
                        portMulticast = data;
                    } else {
                        portMulticast = OpenVSwitch.Port.fallbackMCGroup;
                        log.info(
                            "Setting the port multicast group to fallback value: {}",
                            portMulticast);

                    }

                    protocol = new OvsProtocol(pid(), datapathFamily[0],
                                               portFamily[0], flowFamily[0],
                                               packetFamily);
                    initialized = true;
                    initStatusCallback.onSuccess(true);
                }
            };

        final Callback<Integer> datapathMulticastCallback =
            new ChainedCallback<Integer>(initStatusCallback) {
                @Override
                public void onSuccess(Integer data) {
                    log.debug("Got datapath multicast group: {}.", data);
                    if (data != null)
                        datapathMulticast = data;

                    getMulticastGroup(OpenVSwitch.Datapath.Family,
                                      OpenVSwitch.Datapath.MCGroup,
                                      portMulticastCallback);
                }
            };

        final Callback<Short> packetFamilyBuilder =
            new ChainedCallback<Short>(initStatusCallback) {
                @Override
                public void onSuccess(Short data) {
                    packetFamily = new PacketFamily(data);
                    log.debug("Got packet family id: {}.", data);
                    getMulticastGroup(OpenVSwitch.Datapath.Family,
                                      OpenVSwitch.Datapath.MCGroup,
                                      datapathMulticastCallback);
                }
            };

        final Callback<Short> flowFamilyBuilder =
            new ChainedCallback<Short>(initStatusCallback) {
                @Override
                public void onSuccess(Short data) {
                    flowFamily[0] = new FlowFamily(data);
                    log.debug("Got flow family id: {}.", data);
                    getFamilyId(OpenVSwitch.Packet.Family, packetFamilyBuilder);
                }
            };

        final Callback<Short> portFamilyBuilder =
            new ChainedCallback<Short>(initStatusCallback) {
                @Override
                public void onSuccess(Short data) {
                    portFamily[0] = new PortFamily(data);
                    log.debug("Got port family id: {}.", data);
                    getFamilyId(OpenVSwitch.Flow.Family, flowFamilyBuilder);
                }
            };

        final Callback<Short> datapathFamilyBuilder =
            new ChainedCallback<Short>(initStatusCallback) {
                @Override
                public void onSuccess(Short data) {
                    datapathFamily[0] = new DatapathFamily(data);
                    log.debug("Got datapath family id: {}.", data);
                    getFamilyId(OpenVSwitch.Port.Family, portFamilyBuilder);
                }
            };

        getFamilyId(OpenVSwitch.Datapath.Family, datapathFamilyBuilder);
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    private static abstract class ChainedCallback<T> implements Callback<T> {

        private final Callback<Boolean> statusCallback;

        public ChainedCallback(Callback<Boolean> statusCallback) {
            this.statusCallback = statusCallback;
        }

        @Override
        public void onError(NetlinkException ex) {
            if (statusCallback != null)
                statusCallback.onError(ex);
        }
    }

    /** Used for debugging failing requests that do not register callbacks. */
    private static abstract class LoggingCallback<T> implements Callback<T> {
        public abstract String requestString();
        public abstract String dataString();
        public void onSuccess(T any) { }
        public void onError(NetlinkException ex) {
            log.debug(requestString() + " request for " +
                      dataString() + " failed", ex);
        }
    }
}
