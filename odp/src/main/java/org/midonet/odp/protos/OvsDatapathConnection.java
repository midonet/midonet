/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.odp.protos;

import java.util.Set;
import java.util.concurrent.Future;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.common.util.concurrent.ValueFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.netlink.BufferPool;
import org.midonet.netlink.Callback;
import org.midonet.netlink.Netlink;
import org.midonet.netlink.NetlinkChannel;
import org.midonet.netlink.NetlinkSelectorProvider;
import org.midonet.netlink.protos.NetlinkConnection;
import org.midonet.odp.Datapath;
import org.midonet.odp.Flow;
import org.midonet.odp.FlowMatch;
import org.midonet.odp.Packet;
import org.midonet.odp.Port;
import org.midonet.odp.Ports;
import org.midonet.util.eventloop.Reactor;
import org.midonet.util.throttling.NoOpThrottlingGuard;
import org.midonet.util.throttling.NoOpThrottlingGuardFactory;
import org.midonet.util.throttling.ThrottlingGuard;
import org.midonet.util.throttling.ThrottlingGuardFactory;


/**
 * OvsDatapath protocol implementation.
 */
public abstract class OvsDatapathConnection extends NetlinkConnection {

    private static final Logger log =
        LoggerFactory.getLogger(OvsDatapathConnection.class);

    public abstract Future<Boolean> initialize() throws Exception;

    public abstract boolean isInitialized();

    protected OvsDatapathConnection(NetlinkChannel channel, Reactor reactor,
            ThrottlingGuardFactory pendingWritesThrottlingFactory,
            ThrottlingGuard upcallThrottler,
            BufferPool sendPool) throws Exception {
        super(channel, reactor, pendingWritesThrottlingFactory,
            upcallThrottler, sendPool);
    }

    public static OvsDatapathConnection create(
            Netlink.Address address, Reactor reactor,
            ThrottlingGuardFactory pendingWritesThrottlingFactory,
            ThrottlingGuard upcallThrottler,
            BufferPool sendPool) throws Exception {

        NetlinkChannel channel;

        try {
            channel = Netlink.selectorProvider()
                .openNetlinkSocketChannel(Netlink.Protocol.NETLINK_GENERIC);

            channel.connect(address);
        } catch (Exception e) {
            log.error("Error connecting to Netlink");
            throw new RuntimeException(e);
        }

        return new OvsDatapathConnectionImpl(channel, reactor,
            pendingWritesThrottlingFactory, upcallThrottler, sendPool);
    }

    public static OvsDatapathConnection create(
            Netlink.Address address, Reactor reactor) throws Exception {
        return create(address, reactor, new NoOpThrottlingGuardFactory(),
                new NoOpThrottlingGuard(), new BufferPool(128, 512, 0x1000));
    }

    public static OvsDatapathConnection createMock(Reactor reactor) throws Exception {

        NetlinkChannel channel = Netlink.selectorProvider()
            .openMockNetlinkSocketChannel(Netlink.Protocol.NETLINK_GENERIC);

        return new MockOvsDatapathConnection(channel, reactor);
    }

    /**
     * Install packet-in callback for handling miss/userspace packets on a
     * specific datapath.
     */
    public Future<Boolean> datapathsSetNotificationHandler(@Nonnull Datapath datapath,
                                                           @Nonnull Callback<Packet> notificationHandler) {
        ValueFuture<Boolean> valueFuture = ValueFuture.create();
        datapathsSetNotificationHandler(datapath, notificationHandler,
                                        wrapFuture(valueFuture));
        return valueFuture;
    }

    /**
     * Install packet-in callback for handling miss/userspace packets on a
     * specific datapath.
     */
    public void datapathsSetNotificationHandler(@Nonnull final Datapath datapath,
                                                @Nonnull final Callback<Packet> notificationHandler,
                                                @Nonnull final Callback<Boolean> operationCallback) {
        _doDatapathsSetNotificationHandler(datapath, notificationHandler,
                                           operationCallback,
                                           DEF_REPLY_TIMEOUT);
    }

    protected abstract void
    _doDatapathsSetNotificationHandler(@Nonnull final Datapath datapath,
                                       @Nonnull final Callback<Packet> notificationHandler,
                                       @Nonnull final Callback<Boolean> operationCallback,
                                       long timeoutMillis);

    /**
     * Future based api for enumerating datapaths.
     *
     * @return A future that hold the set of enumerated datapaths.
     */
    public Future<Set<Datapath>> datapathsEnumerate() {
        ValueFuture<Set<Datapath>> future = ValueFuture.create();
        datapathsEnumerate(wrapFuture(future));
        return future;
    }

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
     * Future based api for creating a datapath by name.
     *
     * @param name the name of the datapath.
     * @return A future that hold the created datapath object
     * @see Future
     */
    public Future<Datapath> datapathsCreate(@Nonnull String name)
        throws Exception {
        ValueFuture<Datapath> future = ValueFuture.create();
        datapathsCreate(name, wrapFuture(future));
        return future;
    }

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
     * Future based api for deleting a datapath by id.
     *
     * @param datapathId the id of the datapath.
     * @return A future that hold the delete datapath object
     * @see Future
     */
    public Future<Datapath> datapathsDelete(int datapathId)
        throws Exception {
        ValueFuture<Datapath> future = ValueFuture.create();
        datapathsDelete(datapathId, wrapFuture(future));
        return future;
    }

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
        _doDatapathsDelete(datapathId, null, callback, timeoutMillis);
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
        ValueFuture<Datapath> future = ValueFuture.create();
        datapathsDelete(name, wrapFuture(future));
        return future;
    }

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
        _doDatapathsDelete(null, name, callback, timeoutMillis);
    }

    protected abstract void _doDatapathsDelete(Integer datapathId, String name,
                                               @Nonnull Callback<Datapath> callback,
                                               long timeoutMillis);

    /**
     * Future based api to retrieve port information.
     *
     * @param portName the name of the port we want to retrieve information for.
     * @param datapath the datapath owning the port.
     * @return a future
     */
    public Future<Port<?, ?>> portsGet(final @Nonnull String portName,
                                       final @Nullable Datapath datapath) {
        ValueFuture<Port<?, ?>> future = ValueFuture.create();
        portsGet(portName, datapath, wrapFuture(future), DEF_REPLY_TIMEOUT);
        return future;
    }

    /**
     * Callback based api for retrieving a port by name.
     *
     * @param portName the name of the port.
     * @param datapath the datapath that this port should be located on.
     * @param callback the callback that will be provided the operation result.
     */
    public void portsGet(final @Nonnull String portName,
                         final @Nullable Datapath datapath,
                         final @Nonnull Callback<Port<?, ?>> callback) {
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
                         final @Nonnull Callback<Port<?, ?>> callback,
                         final long timeoutMillis) {
        _doPortsGet(portName, null, datapath, callback, timeoutMillis);
    }

    /**
     * Future based api to retrieve port information.
     *
     * @param portId   the port we want to retrieve information for
     * @param datapath the datapath which holds the port
     * @return a future
     */
    public Future<Port<?, ?>> portsGet(final int portId,
                                       @Nonnull final Datapath datapath) {
        ValueFuture<Port<?, ?>> future = ValueFuture.create();
        portsGet(portId, datapath, wrapFuture(future), DEF_REPLY_TIMEOUT);
        return future;
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
                         final @Nonnull Callback<Port<?, ?>> callback) {
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
                         final @Nonnull Callback<Port<?, ?>> callback,
                         final long timeoutMillis) {
        _doPortsGet(null, portId, datapath, callback, timeoutMillis);
    }

    protected abstract void _doPortsGet(final @Nullable String name,
                                        final @Nullable Integer portId,
                                        final @Nullable Datapath datapath,
                                        final @Nonnull Callback<Port<?, ?>> callback,
                                        final long timeoutMillis);

    /**
     * Future based api to delete a port
     *
     * @param port     the name of the port we want to retrieve information for.
     * @param datapath the datapath owning the port.
     * @return a future
     */
    public Future<Port<?, ?>> portsDelete(final @Nonnull Port<?, ?> port,
                                          final @Nullable Datapath datapath) {
        ValueFuture<Port<?, ?>> future = ValueFuture.create();
        portsDelete(port, datapath, wrapFuture(future), DEF_REPLY_TIMEOUT);
        return future;
    }

    /**
     * Callback based api for deleting a port.
     *
     * @param port     the name of the port.
     * @param datapath the datapath that this port should be located on.
     * @param callback the callback that will be provided the operation result.
     */
    public void portsDelete(final @Nonnull Port<?, ?> port,
                            final @Nullable Datapath datapath,
                            final @Nonnull Callback<Port<?, ?>> callback) {
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
    public void portsDelete(final @Nonnull Port<?, ?> port,
                            final @Nullable Datapath datapath,
                            final @Nonnull Callback<Port<?, ?>> callback,
                            final long timeoutMillis) {
        _doPortsDelete(port, datapath, callback, timeoutMillis);
    }

    protected abstract void _doPortsDelete(final @Nonnull Port<?, ?> port,
                                           final @Nullable Datapath datapath,
                                           final @Nonnull Callback<Port<?, ?>> callback,
                                           final long timeoutMillis);

    /**
     * Future based api to updating port information.
     *
     * @param port     the port we want to retrieve information for
     * @param datapath the datapath which holds the port
     * @return a future holding the updated port information.
     */
    public Future<Port<?, ?>> portsSet(final @Nonnull Port port,
                                       final @Nullable Datapath datapath) {
        ValueFuture<Port<?, ?>> future = ValueFuture.create();
        portsSet(port, datapath, wrapFuture(future), DEF_REPLY_TIMEOUT);
        return future;
    }

    /**
     * Callback based api for updating port information.
     *
     * @param port     the port description.
     * @param datapath the datapath that this port should be located on.
     * @param callback the callback that will be called with the result.
     */
    public void portsSet(final @Nonnull Port<?, ?> port,
                         final @Nullable Datapath datapath,
                         final @Nonnull Callback<Port<?, ?>> callback) {
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
    public void portsSet(final @Nonnull Port port,
                         final @Nullable Datapath datapath,
                         final @Nonnull Callback<Port<?, ?>> callback,
                         final long timeoutMillis) {
        _doPortsSet(port, datapath, callback, timeoutMillis);
    }

    protected abstract void _doPortsSet(@Nonnull final Port<?, ?> port,
                                        @Nullable final Datapath datapath,
                                        @Nonnull final Callback<Port<?, ?>> callback,
                                        final long timeoutMillis);

    /**
     * Future based api for listing ports of a datapath.
     *
     * @param datapath is the datapath we want to list ports from.
     * @return A future that holds the set of ports visible on the path
     * @see Future
     */
    public Future<Set<Port<?, ?>>> portsEnumerate(@Nonnull Datapath datapath) {
        ValueFuture<Set<Port<?, ?>>> valueFuture = ValueFuture.create();
        portsEnumerate(datapath, wrapFuture(valueFuture));
        return valueFuture;
    }

    /**
     * Callback based api for listing ports of a datapath.
     *
     * @param datapath is the datapath we want to list ports from.
     * @param callback is the callback that will be notified by the reply
     * @see Callback
     */
    public void portsEnumerate(@Nonnull Datapath datapath,
                               @Nonnull Callback<Set<Port<?, ?>>> callback) {
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
                               @Nonnull Callback<Set<Port<?, ?>>> callback,
                               final long timeoutMillis) {
        _doPortsEnumerate(datapath, callback, timeoutMillis);
    }

    protected abstract void _doPortsEnumerate(@Nonnull Datapath datapath,
                                              @Nonnull Callback<Set<Port<?, ?>>> callback,
                                              long timeoutMillis);

    /**
     * Future based api for adding a new port to a datapath.
     *
     * @param datapath the datapath we want to list ports from.
     * @param port     the specification of the port we want to create.
     * @return A future that holds the newly create port.
     * @see Future
     * @see Ports
     */
    public Future<Port<?, ?>> portsCreate(@Nonnull Datapath datapath,
                                          @Nonnull Port<?, ?> port) {
        ValueFuture<Port<?, ?>> valueFuture = ValueFuture.create();
        portsCreate(datapath, port, wrapFuture(valueFuture));
        return valueFuture;
    }

    /**
     * Callback based api for adding a new port to a datapath.
     *
     * @param datapath the datapath we want to list ports from.
     * @param port     the specification of the port we want to create.
     * @param callback the callback that will be notified by the reply.
     * @see Future
     * @see Ports
     */
    public void portsCreate(@Nonnull Datapath datapath, @Nonnull Port<?, ?> port,
                            @Nonnull Callback<Port<?, ?>> callback) {
        portsCreate(datapath, port, callback, DEF_REPLY_TIMEOUT);
    }

    /**
     * Callback based api for adding a new port to a datapath.
     *
     * @param datapath      the datapath we want to list ports from.
     * @param port          the specification of the port we want to create.
     * @param callback      the callback that will be notified by the reply.
     * @param timeoutMillis the timeout we are prepared to wait for the reply.
     * @see Future
     * @see Ports
     */
    public void portsCreate(@Nonnull Datapath datapath, @Nonnull Port<?, ?> port,
                            @Nonnull Callback<Port<?, ?>> callback, long timeoutMillis) {

        _doPortsCreate(datapath, port, callback, timeoutMillis);
    }

    protected abstract void _doPortsCreate(@Nonnull Datapath datapath,
                                           @Nonnull Port<?, ?> port,
                                           @Nonnull Callback<Port<?, ?>> callback,
                                           long timeoutMillis);

    /**
     * Future based api for retrieving datapath information.
     *
     * @param name the name of the datapath we want information for.
     * @return a future that has the required information about the datapath.
     */
    public Future<Datapath> datapathsGet(@Nonnull String name) {
        ValueFuture<Datapath> future = ValueFuture.create();
        datapathsGet(name, wrapFuture(future));
        return future;
    }

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
        _doDatapathsGet(null, name, callback, timeoutMillis);
    }

    /**
     * Future based callback for retrieving datapath information
     *
     * @param datapathId the id of the datapath we want information for.
     * @return a future that has the required information about the datapath.
     */
    public Future<Datapath> datapathsGet(int datapathId) {
        ValueFuture<Datapath> future = ValueFuture.create();
        datapathsGet(datapathId, wrapFuture(future));
        return future;
    }

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
        _doDatapathsGet(datapathId, null, callback, DEF_REPLY_TIMEOUT);
    }

    protected abstract void _doDatapathsGet(final Integer datapathId,
                                            final String name,
                                            final Callback<Datapath> callback,
                                            final long defReplyTimeout);

    /**
     * Future based api for enumerating flows.
     *
     * @param datapath the name of the datapath
     * @return a future that provides access to the set of flows present inside
     *         a datapath.
     */
    public Future<Set<Flow>> flowsEnumerate(@Nonnull final Datapath datapath) {
        ValueFuture<Set<Flow>> flowsFuture = ValueFuture.create();
        flowsEnumerate(datapath, wrapFuture(flowsFuture));
        return flowsFuture;
    }

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
     * Future based api for flushing all the flows belonging to a datapath.
     *
     * @param datapath is the actual datapath.
     * @return a future that provides access to the operation result.
     */
    public Future<Boolean> flowsFlush(@Nonnull final Datapath datapath) {
        ValueFuture<Boolean> flowsFuture = ValueFuture.create();
        flowsFlush(datapath, wrapFuture(flowsFuture));
        return flowsFuture;
    }

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
     * Future based api for creating a flow.
     *
     * @param datapath the name of the datapath
     * @param flow     the flow that we want to install
     * @return a future that provides access to the installed flow.
     */
    public Future<Flow> flowsCreate(@Nonnull final Datapath datapath,
                                    @Nonnull final Flow flow) {
        ValueFuture<Flow> flowFuture = ValueFuture.create();
        flowsCreate(datapath, flow, wrapFuture(flowFuture));
        return flowFuture;
    }

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
                                           @Nonnull final Callback<Flow> callback,
                                           final long timeout);

    public Future<Flow> flowsDelete(@Nonnull final Datapath datapath,
                                    @Nonnull final Flow flow) {
        ValueFuture<Flow> flowFuture = ValueFuture.create();
        flowsDelete(datapath, flow, wrapFuture(flowFuture));
        return flowFuture;
    }

    /**
     * Callback based api for deleting a flow.
     *
     * @param datapath the name of the datapath
     * @param flow     the flow that we want to install
     * @param callback a callback which will receive the installed flow
     */
    public void flowsDelete(@Nonnull final Datapath datapath,
                            @Nonnull final Flow flow,
                            @Nonnull final Callback<Flow> callback) {
        flowsDelete(datapath, flow, callback, DEF_REPLY_TIMEOUT);
    }

    /**
     * Callback based api for deleting a flow.
     *
     * @param datapath      the name of the datapath
     * @param flow          the flow that we want to install
     * @param callback      the callback which will receive the installed flow
     * @param timeoutMillis the amount of time we should wait for the response
     */
    public void flowsDelete(@Nonnull final Datapath datapath,
                            @Nonnull final Flow flow,
                            @Nonnull final Callback<Flow> callback,
                            long timeoutMillis) {
        _doFlowsDelete(datapath, flow, callback, timeoutMillis);
    }

    protected abstract void _doFlowsDelete(@Nonnull final Datapath datapath,
                                           @Nonnull final Flow flow,
                                           @Nonnull final Callback<Flow> callback,
                                           final long timeout);

    /**
     * Future based api for retrieving a flow.
     *
     * @param datapath the datapath
     * @param match    the flowMatch for the flow we want to retrieve
     * @return a future that provides access to the retrieved flow
     */
    public Future<Flow> flowsGet(@Nonnull final Datapath datapath,
                                 @Nonnull final FlowMatch match) {
        ValueFuture<Flow> flowFuture = ValueFuture.create();
        flowsGet(datapath, match, wrapFuture(flowFuture));
        return flowFuture;
    }

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
     * Future based api for updating a flow.
     *
     * @param datapath the datapath
     * @param flow     the flow we want to update (it should exists)
     * @return a future that provides access to the updated flow
     */
    public Future<Flow> flowsSet(Datapath datapath, Flow flow) {
        ValueFuture<Flow> flowFuture = ValueFuture.create();
        flowsSet(datapath, flow, wrapFuture(flowFuture));
        return flowFuture;
    }

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
     * Future based callback for executing a packet
     *
     * @param datapath is the datapath on which we want to send the packet.
     * @param packet   is the packet we want to send. It needs to have both the
     *                 keys and the actions parameters set.
     * @return a future that can be used to track the successful completion of
     *         the operation.
     */
    public Future<Boolean> packetsExecute(@Nonnull final Datapath datapath,
                                          @Nonnull final Packet packet) {
        ValueFuture<Boolean> resultFuture = ValueFuture.create();
        packetsExecute(datapath, packet, wrapFuture(resultFuture));
        return resultFuture;
    }

    /**
     * Callback based api for executing actions on a packet
     *
     * @param datapath is the datapath on which we want to send the packet.
     * @param packet   is the packet we want to send. It needs to have both
     *                 the keys and the actions parameters set.
     * @param callback is the callback which will receive the operation completion
     *                 status
     */
    public void packetsExecute(@Nonnull final Datapath datapath,
                               @Nonnull final Packet packet,
                               @Nonnull final Callback<Boolean> callback) {
        packetsExecute(datapath, packet, callback, DEF_REPLY_TIMEOUT);
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
    public void packetsExecute(@Nonnull final Datapath datapath,
                               @Nonnull final Packet packet,
                               @Nonnull final Callback<Boolean> callback,
                               long timeoutMillis) {
        _doPacketsExecute(datapath, packet, callback, timeoutMillis);
    }

    protected abstract void _doPacketsExecute(@Nonnull final Datapath datapath,
                                              @Nonnull final Packet packet,
                                              @Nonnull final Callback<Boolean> callback,
                                              long timeoutMillis);
}
