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

import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.common.util.concurrent.SettableFuture;
import org.midonet.odp.ports.NetDevPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.netlink.AbstractNetlinkConnection;
import org.midonet.netlink.BufferPool;
import org.midonet.netlink.Callback;
import org.midonet.netlink.MockNetlinkChannel;
import org.midonet.netlink.Netlink;
import org.midonet.netlink.NetlinkChannel;
import org.midonet.netlink.NetlinkProtocol;
import org.midonet.netlink.exceptions.NetlinkException;
import org.midonet.odp.Datapath;
import org.midonet.odp.DpPort;
import org.midonet.odp.Flow;
import org.midonet.odp.FlowMatch;
import org.midonet.odp.Packet;
import org.midonet.odp.flows.FlowAction;
import org.midonet.odp.flows.FlowKey;
import org.midonet.util.BatchCollector;


/**
 * OvsDatapath protocol implementation.
 */
public abstract class OvsDatapathConnection extends AbstractNetlinkConnection {

    private static final Logger log =
        LoggerFactory.getLogger(OvsDatapathConnection.class);

    /** Initializes the state of this OvsDatapathConnection instance and
     *  executes the initial discovery of command family parameters. Users
     *  have to wait for the provided callback to be triggered before sending
     *  any requests, as the state of the OvsDatapathConnection is not defined
     *  during this initial setup phase . */
    public abstract void initialize(final Callback<Boolean> cb);

    public abstract boolean isInitialized();

    public final FuturesApi futures = new FuturesApi();

    protected OvsDatapathConnection(NetlinkChannel channel, BufferPool sendPool) {
        super(channel, sendPool);
    }

    public static OvsDatapathConnection create(Netlink.Address address,
                                               BufferPool sendPool) {

        NetlinkChannel channel;

        try {
            channel = Netlink.selectorProvider()
                .openNetlinkSocketChannel(NetlinkProtocol.NETLINK_GENERIC);

            channel.connect(address);
        } catch (Exception e) {
            log.error("Error connecting to Netlink");
            throw new RuntimeException(e);
        }

        return new OvsDatapathConnectionImpl(channel, sendPool);
    }

    public static OvsDatapathConnection create(Netlink.Address address) throws Exception {
        return create(address, new BufferPool(128, 512, 0x1000));
    }

    public static OvsDatapathConnection createMock() {
        NetlinkChannel channel =
            new MockNetlinkChannel(Netlink.selectorProvider(),
                                   NetlinkProtocol.NETLINK_GENERIC);
        return new MockOvsDatapathConnection(channel);
    }

    /**
     * Install packet-in callback for handling miss/userspace packets on a
     * specific datapath.
     */
    public void datapathsSetNotificationHandler(@Nonnull final BatchCollector<Packet> notificationHandler) {
        _doDatapathsSetNotificationHandler(notificationHandler);
    }

    protected abstract void
    _doDatapathsSetNotificationHandler(@Nonnull final BatchCollector<Packet> notificationHandler);

    /**
     * Callback based api for enumerating datapaths.
     *
     * @param callback the callback which will receive the results.
     * @see Callback
     */
    public void datapathsEnumerate(Callback<Set<Datapath>> callback) {
        datapathsEnumerate(callback, DEF_REPLY_TIMEOUT);
    }

    /**
     * Callback based api for enumerating datapaths.
     *
     * @param callback      the callback which will receive the results.
     * @param timeoutMillis the timeout we need to wait for the results.
     * @see Callback
     */
    public void datapathsEnumerate(Callback<Set<Datapath>> callback, long timeoutMillis) {
        _doDatapathsEnumerate(callback, timeoutMillis);
    }

    protected abstract void _doDatapathsEnumerate(@Nonnull Callback<Set<Datapath>> callback,
                                                  long timeoutMillis);

    /**
     * Callback based api for creating a datapath by name (with default timeout).
     *
     * @param name     the name of the datapath.
     * @param callback the callback that will be provided the operation result.
     * @see Callback
     */
    public void datapathsCreate(@Nonnull String name,
                                @Nonnull Callback<Datapath> callback) {
        datapathsCreate(name, callback, DEF_REPLY_TIMEOUT);
    }

    /**
     * Callback based api for creating a datapath by name (with custom timeout).
     *
     * @param name          the name of the datapath.
     * @param callback      the callback that will be provided the operation result.
     * @param timeoutMillis the timeout we are prepared to wait for completion.
     */
    public void datapathsCreate(@Nonnull String name,
                                @Nonnull Callback<Datapath> callback,
                                long timeoutMillis) {
        _doDatapathsCreate(name, callback, timeoutMillis);
    }

    protected abstract void _doDatapathsCreate(@Nonnull String name,
                                               @Nonnull Callback<Datapath> callback,
                                               long timeoutMillis);

    /**
     * Callback based api for retrieving datapath information.
     *
     * @param name     the name of the datapath
     * @param callback the callback that will receive information.
     */
    public void datapathsGet(@Nonnull String name, Callback<Datapath> callback) {
        datapathsGet(name, callback, DEF_REPLY_TIMEOUT);
    }

    /**
     * Callback based api for retrieving datapath information.
     *
     * @param name          the name of the datapath
     * @param callback      the callback that will receive information.
     * @param timeoutMillis the timeout we are willing to wait for response.
     */
    public void datapathsGet(@Nonnull String name, Callback<Datapath> callback,
                             long timeoutMillis) {
        _doDatapathsGet(name, callback, timeoutMillis);
    }

    protected abstract void _doDatapathsGet(String name,
                                            Callback<Datapath> callback,
                                            long defReplyTimeout);

    /**
     * Callback based api for retrieving datapath information.
     *
     * @param datapathId the name of the datapath
     * @param callback   the callback that will receive information.
     */
    public void datapathsGet(int datapathId, Callback<Datapath> callback) {
        datapathsGet(datapathId, callback, DEF_REPLY_TIMEOUT);
    }

    /**
     * Callback based api for retrieving datapath information.
     *
     * @param datapathId    the name of the datapath
     * @param callback      the callback that will receive information.
     * @param timeoutMillis the timeout we are willing to wait for response.
     */
    public void datapathsGet(int datapathId, Callback<Datapath> callback,
                             long timeoutMillis) {
        _doDatapathsGet(datapathId, callback, DEF_REPLY_TIMEOUT);
    }

    protected abstract void _doDatapathsGet(int datapathId,
                                            Callback<Datapath> callback,
                                            long defReplyTimeout);

    /**
     * Callback based api for creating a datapath by name (with default timeout).
     *
     * @param datapathId the id of the datapath
     * @param callback   the callback that will be provided the operation result.
     * @see Callback
     */
    public void datapathsDelete(int datapathId,
                                @Nonnull Callback<Datapath> callback) {
        datapathsDelete(datapathId, callback, DEF_REPLY_TIMEOUT);
    }

    /**
     * Callback based api for creating a datapath by name (with custom timeout).
     *
     * @param datapathId    the id of the datapath.
     * @param callback      the callback that will be provided the operation result.
     * @param timeoutMillis the timeout we are prepared to wait for completion.
     */
    public void datapathsDelete(int datapathId,
                                @Nonnull Callback<Datapath> callback,
                                long timeoutMillis) {
        _doDatapathsDelete(datapathId, callback, timeoutMillis);
    }

    protected abstract void _doDatapathsDelete(int datapathId,
                                               @Nonnull Callback<Datapath> callback,
                                               long timeoutMillis);

    /**
     * Callback based api for creating a datapath by name (with default timeout).
     *
     * @param name     the name of the datapath.
     * @param callback the callback that will be provided the operation result.
     * @see Callback
     */
    public void datapathsDelete(@Nonnull String name,
                                @Nonnull Callback<Datapath> callback) {
        datapathsDelete(name, callback, DEF_REPLY_TIMEOUT);
    }

    /**
     * Callback based api for creating a datapath by name (with custom timeout).
     *
     * @param name          the name of the datapath.
     * @param callback      the callback that will be provided the operation result.
     * @param timeoutMillis the timeout we are prepared to wait for completion.
     */
    public void datapathsDelete(@Nonnull String name,
                                @Nonnull Callback<Datapath> callback,
                                long timeoutMillis) {
        _doDatapathsDelete(name, callback, timeoutMillis);
    }

    protected abstract void _doDatapathsDelete(String name,
                                               @Nonnull Callback<Datapath> callback,
                                               long timeoutMillis);

    /**
     * Callback based api for retrieving a port by name.
     *
     * @param portName the name of the port.
     * @param datapath the datapath that this port should be located on.
     * @param callback the callback that will be provided the operation result.
     */
    public void portsGet(final @Nonnull String portName,
                         final @Nullable Datapath datapath,
                         final @Nonnull Callback<DpPort> callback) {
        portsGet(portName, datapath, callback, DEF_REPLY_TIMEOUT);
    }

    /**
     * Callback based api for retrieving a port by name.
     *
     * @param portName      the name of the port.
     * @param datapath      the datapath that this port should be located on.
     * @param callback      the callback that will be provided the operation result.
     * @param timeoutMillis the timeout we are prepared to wait for completion.
     */
    public void portsGet(final @Nonnull String portName,
                         final @Nullable Datapath datapath,
                         final @Nonnull Callback<DpPort> callback,
                         final long timeoutMillis) {
        _doPortsGet(portName, null, datapath, callback, timeoutMillis);
    }

    /**
     * Callback based api for retrieving a port by id.
     *
     * @param portId   the id of the port.
     * @param datapath the datapath that this port should be located on.
     * @param callback the callback that will be called with the result.
     */
    public void portsGet(final int portId,
                         final @Nonnull Datapath datapath,
                         final @Nonnull Callback<DpPort> callback) {
        portsGet(portId, datapath, callback, DEF_REPLY_TIMEOUT);
    }

    /**
     * Callback based api for retrieving a port by id.
     *
     * @param portId        the id of the port.
     * @param datapath      the datapath that this port should be located on.
     * @param callback      the callback that will be called with the result.
     * @param timeoutMillis the timeout we are willing to wait for a result.
     */
    public void portsGet(final int portId,
                         final @Nonnull Datapath datapath,
                         final @Nonnull Callback<DpPort> callback,
                         final long timeoutMillis) {
        _doPortsGet(null, portId, datapath, callback, timeoutMillis);
    }

    protected abstract void _doPortsGet(final @Nullable String name,
                                        final @Nullable Integer portId,
                                        final @Nullable Datapath datapath,
                                        final @Nonnull Callback<DpPort> callback,
                                        final long timeoutMillis);

    /**
     * Callback based api for deleting a port.
     *
     * @param port     the name of the port.
     * @param datapath the datapath that this port should be located on.
     * @param callback the callback that will be provided the operation result.
     */
    public void portsDelete(final @Nonnull DpPort port,
                            final @Nullable Datapath datapath,
                            final @Nonnull Callback<DpPort> callback) {
        portsDelete(port, datapath, callback, DEF_REPLY_TIMEOUT);
    }

    /**
     * Callback based api for deleting an existing port.
     *
     * @param port          the port to delete.
     * @param datapath      the datapath that this port should be located on.
     * @param callback      the callback that will be provided the operation result.
     * @param timeoutMillis the timeout we are prepared to wait for completion.
     */
    public void portsDelete(final @Nonnull DpPort port,
                            final @Nullable Datapath datapath,
                            final @Nonnull Callback<DpPort> callback,
                            final long timeoutMillis) {
        _doPortsDelete(port, datapath, callback, timeoutMillis);
    }

    protected abstract void _doPortsDelete(final @Nonnull DpPort port,
                                           final @Nullable Datapath datapath,
                                           final @Nonnull Callback<DpPort> callback,
                                           final long timeoutMillis);

    /**
     * Callback based api for updating port information.
     *
     * @param port     the port description.
     * @param datapath the datapath that this port should be located on.
     * @param callback the callback that will be called with the result.
     */
    public void portsSet(final @Nonnull DpPort port,
                         final @Nullable Datapath datapath,
                         final @Nonnull Callback<DpPort> callback) {
        portsSet(port, datapath, callback, DEF_REPLY_TIMEOUT);
    }

    /**
     * Callback based api for retrieving a port by id.
     *
     * @param port          the updated port description.
     * @param datapath      the datapath this port should be located on.
     * @param callback      the callback that will be called with the result.
     * @param timeoutMillis the timeout we are willing to wait for a result.
     */
    public void portsSet(final @Nonnull DpPort port,
                         final @Nullable Datapath datapath,
                         final @Nonnull Callback<DpPort> callback,
                         final long timeoutMillis) {
        _doPortsSet(port, datapath, callback, timeoutMillis);
    }

    protected abstract void _doPortsSet(@Nonnull final DpPort port,
                                        @Nullable final Datapath datapath,
                                        @Nonnull final Callback<DpPort> callback,
                                        final long timeoutMillis);

    /**
     * Callback based api for listing ports of a datapath.
     *
     * @param datapath is the datapath we want to list ports from.
     * @param callback is the callback that will be notified by the reply
     * @see Callback
     */
    public void portsEnumerate(@Nonnull Datapath datapath,
                               @Nonnull Callback<Set<DpPort>> callback) {
        portsEnumerate(datapath, callback, DEF_REPLY_TIMEOUT);
    }

    /**
     * Callback based api for listing ports of a datapath.
     *
     * @param datapath      the datapath we want to list ports from.
     * @param callback      the callback that will be notified by the reply
     * @param timeoutMillis the timeout we are prepared to wait for completion.
     * @see Callback
     */
    public void portsEnumerate(@Nonnull Datapath datapath,
                               @Nonnull Callback<Set<DpPort>> callback,
                               final long timeoutMillis) {
        _doPortsEnumerate(datapath, callback, timeoutMillis);
    }

    protected abstract void _doPortsEnumerate(@Nonnull Datapath datapath,
                                              @Nonnull Callback<Set<DpPort>> callback,
                                              long timeoutMillis);

    /**
     * Callback based api for adding a new port to a datapath.
     *
     * @param datapath the datapath we want to list ports from.
     * @param port     the specification of the port we want to create.
     * @param callback the callback that will be notified by the reply.
     */
    public void portsCreate(@Nonnull Datapath datapath, @Nonnull DpPort port,
                            @Nonnull Callback<DpPort> callback) {
        portsCreate(datapath, port, callback, DEF_REPLY_TIMEOUT);
    }

    /**
     * Callback based api for adding a new port to a datapath.
     *
     * @param datapath      the datapath we want to list ports from.
     * @param port          the specification of the port we want to create.
     * @param callback      the callback that will be notified by the reply.
     * @param timeoutMillis the timeout we are prepared to wait for the reply.
     */
    public void portsCreate(@Nonnull Datapath datapath, @Nonnull DpPort port,
                            @Nonnull Callback<DpPort> callback, long timeoutMillis) {

        _doPortsCreate(datapath, port, callback, timeoutMillis);
    }

    protected abstract void _doPortsCreate(@Nonnull Datapath datapath,
                                           @Nonnull DpPort port,
                                           @Nonnull Callback<DpPort> callback,
                                           long timeoutMillis);

    /**
     * Callback based api for enumerating flows.
     *
     * @param datapath the name of the datapath
     * @param callback the callback that will receive information.
     */
    public void flowsEnumerate(@Nonnull final Datapath datapath,
                               @Nonnull final Callback<Set<Flow>> callback) {
        flowsEnumerate(datapath, callback, DEF_REPLY_TIMEOUT);
    }

    /**
     * Callback based api for enumerating flows.
     *
     * @param datapath      the name of the datapath
     * @param callback      the callback that will receive information.
     * @param timeoutMillis the timeout we are willing to wait for response.
     */
    public void flowsEnumerate(@Nonnull final Datapath datapath,
                               @Nonnull final Callback<Set<Flow>> callback,
                               long timeoutMillis) {
        _doFlowsEnumerate(datapath, callback, timeoutMillis);
    }

    protected abstract void _doFlowsEnumerate(Datapath datapath,
                                              @Nonnull final Callback<Set<Flow>> callback,
                                              long timeoutMillis);

    /**
     * Callback based api for for flushing all the flows belonging to a datapath.
     *
     * @param datapath the name of the datapath
     * @param callback the callback that will receive the operation result.
     */
    public void flowsFlush(@Nonnull final Datapath datapath,
                           @Nonnull final Callback<Boolean> callback) {
        flowsFlush(datapath, callback, DEF_REPLY_TIMEOUT);
    }

    /**
     * Callback based api for for flushing all the flows belonging to a datapath.
     *
     * @param datapath      the name of the datapath
     * @param callback      the callback that will receive the operation result.
     * @param timeoutMillis the timeout we are willing to wait for response.
     */
    public void flowsFlush(@Nonnull final Datapath datapath,
                           @Nonnull final Callback<Boolean> callback,
                           long timeoutMillis) {
        _doFlowsFlush(datapath, callback, timeoutMillis);
    }

    protected abstract void _doFlowsFlush(@Nonnull final Datapath datapath,
                                          @Nonnull final Callback<Boolean> callback,
                                          long timeoutMillis);

    /**
     * Callback based api for creating a flow.
     *
     * @param datapath the name of the datapath
     * @param flow     the flow that we want to install
     * @param callback a callback which will receive the installed flow
     */
    public void flowsCreate(@Nonnull final Datapath datapath,
                            @Nonnull final Flow flow,
                            @Nonnull final Callback<Flow> callback) {
        flowsCreate(datapath, flow, callback, DEF_REPLY_TIMEOUT);
    }

    /**
     * Fire-and-forget API for creating a flow.
     *
     * @param datapath      the name of the datapath
     * @param flow          the flow that we want to install
     */
    public void flowsCreate(@Nonnull final Datapath datapath,
                            @Nonnull final Flow flow) throws NetlinkException {
        try {
            _doFlowsCreate(datapath, flow, null, DEF_REPLY_TIMEOUT);
        } catch (RuntimeException wrapper) {
            unwrapNetlinkException(wrapper);
        }
    }

    /**
     * Callback based api for creating a flow.
     *
     * @param datapath      the name of the datapath
     * @param flow          the flow that we want to install
     * @param callback      the callback which will receive the installed flow
     * @param timeoutMillis the amount of time we should wait for the response
     */
    public void flowsCreate(@Nonnull final Datapath datapath,
                            @Nonnull final Flow flow,
                            @Nonnull final Callback<Flow> callback,
                            long timeoutMillis) {
        _doFlowsCreate(datapath, flow, callback, timeoutMillis);
    }

    protected abstract void _doFlowsCreate(@Nonnull final Datapath datapath,
                                           @Nonnull final Flow flow,
                                           final Callback<Flow> callback,
                                           final long timeout);

    /**
     * Callback based api for deleting a flow.
     *
     * @param datapath the name of the datapath
     * @param keys     the flow keys that we want to install
     * @param callback a callback which will receive the installed flow
     */
    public void flowsDelete(@Nonnull final Datapath datapath,
                            @Nonnull final Iterable<FlowKey> keys,
                            @Nonnull final Callback<Flow> callback) {
        flowsDelete(datapath, keys, callback, DEF_REPLY_TIMEOUT);
    }

    /**
     * Callback based api for deleting a flow.
     *
     * @param datapath      the name of the datapath
     * @param keys          the flow keys that we want to install
     * @param callback      the callback which will receive the installed flow
     * @param timeoutMillis the amount of time we should wait for the response
     */
    public void flowsDelete(@Nonnull final Datapath datapath,
                            @Nonnull final Iterable<FlowKey> keys,
                            @Nonnull final Callback<Flow> callback,
                            long timeoutMillis) {
        _doFlowsDelete(datapath, keys, callback, timeoutMillis);
    }

    protected abstract void _doFlowsDelete(@Nonnull final Datapath datapath,
                                           @Nonnull final Iterable<FlowKey> keys,
                                           @Nonnull final Callback<Flow> callback,
                                           final long timeout);

    /**
     * Callback based api for retrieving a flow.
     *
     * @param datapath the datapath
     * @param match    the flowMatch for the flow we want to retrieve
     * @param callback the callback which will receive the flow data
     */
    public void flowsGet(@Nonnull final Datapath datapath,
                         @Nonnull final FlowMatch match,
                         @Nonnull final Callback<Flow> callback) {
        flowsGet(datapath, match, callback, DEF_REPLY_TIMEOUT);
    }

    /**
     * Callback based api for retrieving a flow.
     *
     * @param datapath      the datapath
     * @param match         the flowMatch for the flow we want to retrieve
     * @param callback      the callback which will receive the flow data
     * @param timeoutMillis the amount of time we should wait for the response
     */
    public void flowsGet(@Nonnull final Datapath datapath,
                         @Nonnull final FlowMatch match,
                         @Nonnull final Callback<Flow> callback,
                         long timeoutMillis) {
        _doFlowsGet(datapath, match, callback, timeoutMillis);
    }

    protected abstract void _doFlowsGet(@Nonnull final Datapath datapath,
                                        @Nonnull final FlowMatch match,
                                        @Nonnull final Callback<Flow> callback,
                                        long timeout);

    /**
     * Callback based api for updating a flow.
     *
     * @param datapath the datapath
     * @param flow     the flow we want to update (it should exists)
     * @param callback the callback which will receive the updated flow
     */
    public void flowsSet(@Nonnull final Datapath datapath,
                         @Nonnull final Flow flow,
                         @Nonnull final Callback<Flow> callback) {
        flowsSet(datapath, flow, callback, DEF_REPLY_TIMEOUT);
    }

    /**
     * Callback based api for updating a flow.
     *
     * @param datapath      the datapath
     * @param flow          the flow we want to update (it should exists)
     * @param callback      the callback which will receive the updated flow
     * @param timeoutMillis the timeout to use
     */
    public void flowsSet(@Nonnull final Datapath datapath,
                         @Nonnull final Flow flow,
                         @Nonnull final Callback<Flow> callback,
                         long timeoutMillis) {
        _doFlowsSet(datapath, flow, callback, timeoutMillis);
    }

    protected abstract void _doFlowsSet(@Nonnull final Datapath datapath,
                                        @Nonnull final Flow match,
                                        @Nonnull final Callback<Flow> flowCallback,
                                        long timeout);

    /**
     * Callback based api for executing actions on a packet
     *
     * @param datapath is the datapath on which we want to send the packet.
     * @param packet   is the packet we want to send. It needs to have both
     *                 the keys and the actions parameters set.
     * @param callback is the callback which will receive the operation completion
     *                 status
     */
    public void packetsExecute(@Nonnull Datapath datapath,
                               @Nonnull Packet packet,
                               @Nonnull List<FlowAction> actions,
                               @Nonnull Callback<Boolean> callback) {
        packetsExecute(datapath, packet, actions, callback, DEF_REPLY_TIMEOUT);
    }

    private void unwrapNetlinkException(RuntimeException ex) throws NetlinkException {
        Throwable cause = ex.getCause();
        if (cause instanceof NetlinkException)
            throw (NetlinkException) cause;
        else
            throw ex;
    }

    /**
     * Fire-and-forget API for executing actions on a packet
     *
     * @param datapath is the datapath on which we want to send the packet.
     * @param packet   is the packet we want to send. It needs to have both
     *                 the keys and the actions parameters set.
     */
    public void packetsExecute(@Nonnull Datapath datapath,
                               @Nonnull Packet packet,
                               @Nonnull List<FlowAction> actions) throws NetlinkException {
        try {
            _doPacketsExecute(datapath, packet, actions, null, DEF_REPLY_TIMEOUT);
        } catch (RuntimeException wrapper) {
            unwrapNetlinkException(wrapper);
        }
    }

    /**
     * Callback based api for executing actions on a packet
     *
     * @param datapath      is the datapath on which we want to send the packet.
     * @param packet        is the packet we want to send. It needs to have both
     *                      the keys and the actions parameters set.
     * @param callback      is the callback which will receive the operation completion
     *                      status
     * @param timeoutMillis is the timeout we want to wait until the operation
     *                      should complete
     */
    public void packetsExecute(@Nonnull Datapath datapath,
                               @Nonnull Packet packet,
                               @Nonnull List<FlowAction> actions,
                               @Nonnull Callback<Boolean> callback,
                               long timeoutMillis) {
        _doPacketsExecute(datapath, packet, actions, callback, timeoutMillis);
    }

    protected abstract void _doPacketsExecute(@Nonnull Datapath datapath,
                                              @Nonnull Packet packet,
                                              @Nonnull List<FlowAction> actions,
                                              Callback<Boolean> callback,
                                              long timeoutMillis);

    /**
     * Callback based api for adding an interface to the datapath.
     *
     * @param datapath      the datapath to which an interface is added.
     * @param interfaceName the name of the interface to be added.
     * @param callback      the callback which will receive the added dp-port.
     * @param timeoutMills  the timeout until the operation should complete
     */
    public void addInterface(@Nonnull final Datapath datapath,
                             @Nonnull final String interfaceName,
                             final Callback<DpPort> callback,
                             long timeoutMills) {
        DpPort boundPort = new NetDevPort(interfaceName);
        _doPortsCreate(datapath, boundPort, callback, timeoutMills);
    }

    /**
     * Callback based api for deleting an interface on the datapath.
     *
     * @param datapath       the datapath from which an interface is removed.
     * @param interfaceName the name of the interface to be deleted.
     * @param callback      the callback which will receive the deleted dp-port.
     * @param timeoutMills  the timeout until the operation should complete
     */
    public void deleteInterface(@Nonnull final Datapath datapath,
                                @Nonnull final String interfaceName,
                                final Callback<DpPort> callback,
                                final long timeoutMills)
            throws InterruptedException, ExecutionException {
        DpPort deletingPort = OvsDatapathConnection.this.futures.portsGet(
                interfaceName, datapath).get();
        _doPortsDelete(deletingPort, datapath, callback, timeoutMills);
    }

    public class FuturesApi {

        public Future<Boolean> initialize() {
            final SettableFuture<Boolean> future = SettableFuture.create();
            final Callback<Boolean> initStatusCallback = wrapFuture(future);
            OvsDatapathConnection.this.initialize(initStatusCallback);
            return future;
        }

        /**
         * Future based api for enumerating datapaths.
         *
         * @return A future that hold the set of enumerated datapaths.
         */
        public Future<Set<Datapath>> datapathsEnumerate() {
            SettableFuture<Set<Datapath>> future = SettableFuture.create();
            OvsDatapathConnection.this.datapathsEnumerate(wrapFuture(future));
            return future;
        }

        /**
         * Future based api for creating a datapath by name.
         *
         * @param name the name of the datapath.
         * @return A future that hold the created datapath object
         * @see Future
         */
        public Future<Datapath> datapathsCreate(@Nonnull String name)
                throws Exception {
            SettableFuture<Datapath> future = SettableFuture.create();
            OvsDatapathConnection.this.datapathsCreate(name, wrapFuture(future));
            return future;
        }

        /**
         * Future based api for deleting a datapath by id.
         *
         * @param datapathId the id of the datapath.
         * @return A future that hold the delete datapath object
         * @see Future
         */
        public Future<Datapath> datapathsDelete(int datapathId)
                throws Exception {
            SettableFuture<Datapath> future = SettableFuture.create();
            OvsDatapathConnection.this.datapathsDelete(datapathId, wrapFuture(future));
            return future;
        }

        /**
         * Future based api for creating a datapath by name.
         *
         * @param name the name of the datapath.
         * @return A future that hold the created datapath object
         * @see Future
         */
        public Future<Datapath> datapathsDelete(@Nonnull String name)
                throws Exception {
            SettableFuture<Datapath> future = SettableFuture.create();
            OvsDatapathConnection.this.datapathsDelete(name, wrapFuture(future));
            return future;
        }

        /**
         * Future based api to retrieve port information.
         *
         * @param portName the name of the port we want to retrieve information for.
         * @param datapath the datapath owning the port.
         * @return a future
         */
        public Future<DpPort> portsGet(final @Nonnull String portName,
                                       final @Nullable Datapath datapath) {
            SettableFuture<DpPort> future = SettableFuture.create();
            OvsDatapathConnection.this.portsGet(
                    portName, datapath, wrapFuture(future), DEF_REPLY_TIMEOUT);
            return future;
        }

        /**
         * Future based api to retrieve port information.
         *
         * @param portId   the port we want to retrieve information for
         * @param datapath the datapath which holds the port
         * @return a future
         */
        public Future<DpPort> portsGet(final int portId,
                                       @Nonnull final Datapath datapath) {
            SettableFuture<DpPort> future = SettableFuture.create();
            OvsDatapathConnection.this.portsGet(portId, datapath,
                    wrapFuture(future), DEF_REPLY_TIMEOUT);
            return future;
        }

        /**
         * Future based api to delete a port
         *
         * @param port     the name of the port we want to retrieve information for.
         * @param datapath the datapath owning the port.
         * @return a future
         */
        public Future<DpPort> portsDelete(final @Nonnull DpPort port,
                                          final @Nullable Datapath datapath) {
            SettableFuture<DpPort> future = SettableFuture.create();
            OvsDatapathConnection.this.portsDelete(port, datapath,
                    wrapFuture(future), DEF_REPLY_TIMEOUT);
            return future;
        }

        /**
         * Future based api to updating port information.
         *
         * @param port     the port we want to retrieve information for
         * @param datapath the datapath which holds the port
         * @return a future holding the updated port information.
         */
        public Future<DpPort> portsSet(final @Nonnull DpPort port,
                                       final @Nullable Datapath datapath) {
            SettableFuture<DpPort> future = SettableFuture.create();
            OvsDatapathConnection.this.portsSet(port, datapath,
                    wrapFuture(future), DEF_REPLY_TIMEOUT);
            return future;
        }

        /**
         * Future based api for listing ports of a datapath.
         *
         * @param datapath is the datapath we want to list ports from.
         * @return A future that holds the set of ports visible on the path
         * @see Future
         */
        public Future<Set<DpPort>> portsEnumerate(@Nonnull Datapath datapath) {
            SettableFuture<Set<DpPort>> SettableFuture =
                    com.google.common.util.concurrent.SettableFuture.create();
            OvsDatapathConnection.this.portsEnumerate(datapath, wrapFuture(SettableFuture));
            return SettableFuture;
        }

        /**
         * Future based api for adding a new port to a datapath.
         *
         * @param datapath the datapath we want to list ports from.
         * @param port     the specification of the port we want to create.
         * @return A future that holds the newly create port.
         * @see Future
         */
        public Future<DpPort> portsCreate(@Nonnull Datapath datapath,
                                          @Nonnull DpPort port) {
            SettableFuture<DpPort> SettableFuture =
                    com.google.common.util.concurrent.SettableFuture.create();
            OvsDatapathConnection.this.portsCreate(datapath, port, wrapFuture(SettableFuture));
            return SettableFuture;
        }

        /**
         * Future based api for retrieving datapath information.
         *
         * @param name the name of the datapath we want information for.
         * @return a future that has the required information about the datapath.
         */
        public Future<Datapath> datapathsGet(@Nonnull String name) {
            SettableFuture<Datapath> future = SettableFuture.create();
            OvsDatapathConnection.this.datapathsGet(name, wrapFuture(future));
            return future;
        }

        public Future<Flow> flowsDelete(@Nonnull final Datapath datapath,
                                        @Nonnull final Iterable<FlowKey> keys) {
            SettableFuture<Flow> flowFuture = SettableFuture.create();
            OvsDatapathConnection.this.flowsDelete(datapath, keys, wrapFuture(flowFuture));
            return flowFuture;
        }

        /**
         * Future based callback for retrieving datapath information
         *
         * @param datapathId the id of the datapath we want information for.
         * @return a future that has the required information about the datapath.
         */
        public Future<Datapath> datapathsGet(int datapathId) {
            SettableFuture<Datapath> future = SettableFuture.create();
            OvsDatapathConnection.this.datapathsGet(datapathId, wrapFuture(future));
            return future;
        }

        /**
         * Future based api for enumerating flows.
         *
         * @param datapath the name of the datapath
         * @return a future that provides access to the set of flows present inside
         *         a datapath.
         */
        public Future<Set<Flow>> flowsEnumerate(@Nonnull final Datapath datapath) {
            SettableFuture<Set<Flow>> flowsFuture = SettableFuture.create();
            OvsDatapathConnection.this.flowsEnumerate(datapath, wrapFuture(flowsFuture));
            return flowsFuture;
        }

        /**
         * Future based api for creating a flow.
         *
         * @param datapath the name of the datapath
         * @param flow     the flow that we want to install
         * @return a future that provides access to the installed flow.
         */
        public Future<Flow> flowsCreate(@Nonnull final Datapath datapath,
                                        @Nonnull final Flow flow) {
            SettableFuture<Flow> flowFuture = SettableFuture.create();
            OvsDatapathConnection.this.flowsCreate(datapath, flow, wrapFuture(flowFuture));
            return flowFuture;
        }

        /**
         * Future based api for flushing all the flows belonging to a datapath.
         *
         * @param datapath is the actual datapath.
         * @return a future that provides access to the operation result.
         */
        public Future<Boolean> flowsFlush(@Nonnull final Datapath datapath) {
            SettableFuture<Boolean> flowsFuture = SettableFuture.create();
            OvsDatapathConnection.this.flowsFlush(datapath, wrapFuture(flowsFuture));
            return flowsFuture;
        }

        /**
         * Future based api for retrieving a flow.
         *
         * @param datapath the datapath
         * @param match    the flowMatch for the flow we want to retrieve
         * @return a future that provides access to the retrieved flow
         */
        public Future<Flow> flowsGet(@Nonnull final Datapath datapath,
                                     @Nonnull final FlowMatch match) {
            SettableFuture<Flow> flowFuture = SettableFuture.create();
            OvsDatapathConnection.this.flowsGet(datapath, match, wrapFuture(flowFuture));
            return flowFuture;
        }

        /**
         * Future based api for updating a flow.
         *
         * @param datapath the datapath
         * @param flow     the flow we want to update (it should exists)
         * @return a future that provides access to the updated flow
         */
        public Future<Flow> flowsSet(Datapath datapath, Flow flow) {
            SettableFuture<Flow> flowFuture = SettableFuture.create();
            OvsDatapathConnection.this.flowsSet(datapath, flow, wrapFuture(flowFuture));
            return flowFuture;
        }

        /**
         * Future based callback for executing a packet
         *
         * @param datapath is the datapath on which we want to send the packet.
         * @param packet   is the packet we want to send. It needs to have both the
         *                 keys and the actions parameters set.
         * @return a future that can be used to track the successful completion of
         *         the operation.
         */
        public Future<Boolean> packetsExecute(@Nonnull Datapath datapath,
                                              @Nonnull Packet packet,
                                              @Nonnull List<FlowAction> actions) {
            SettableFuture<Boolean> resultFuture = SettableFuture.create();
            OvsDatapathConnection.this.packetsExecute(
                datapath, packet, actions, wrapFuture(resultFuture));
            return resultFuture;
        }

        public Future<DpPort> addInterface(
                @Nonnull final Datapath datapath,
                @Nonnull final String interfaceName) {
            SettableFuture<DpPort> addInterfaceFuture =
                    SettableFuture.create();
            OvsDatapathConnection.this.addInterface(
                    datapath, interfaceName, wrapFuture(addInterfaceFuture),
                    DEF_REPLY_TIMEOUT);
            return addInterfaceFuture;
        }

        public Future<DpPort> deleteInterface(
                @Nonnull final Datapath datapath,
                @Nonnull final String interfaceName)
                throws InterruptedException, ExecutionException {
            SettableFuture<DpPort> deleteInterfaceFuture =
                    SettableFuture.create();
            OvsDatapathConnection.this.deleteInterface(
                    datapath, interfaceName, wrapFuture(deleteInterfaceFuture),
                    DEF_REPLY_TIMEOUT);
            return deleteInterfaceFuture;
        }
    }

    @Nonnull
    private static <T> Callback<T> wrapFuture(final SettableFuture<T> future) {
        return new Callback<T>() {
            @Override
            public void onSuccess(T data) {
                future.set(data);
            }

            @Override
            public void onError(NetlinkException e) {
                future.setException(e);
            }
        };
    }
}
