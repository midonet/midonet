/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.netlink.protos;

import java.util.Set;
import java.util.concurrent.Future;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.common.util.concurrent.ValueFuture;

import com.midokura.util.netlink.NetlinkChannel;
import com.midokura.util.netlink.dp.Datapath;
import com.midokura.util.netlink.dp.Flow;
import com.midokura.util.netlink.dp.Port;
import com.midokura.util.netlink.dp.Ports;
import com.midokura.util.reactor.Reactor;

/**
 * OvsDatapath protocol implementation.
 */
public abstract class OvsDatapathConnection extends NetlinkConnection {

    public abstract void initialize() throws Exception;

    public abstract boolean isInitialized();

    protected OvsDatapathConnection(NetlinkChannel channel, Reactor reactor)
        throws Exception {
        super(channel, reactor);
    }

    public static OvsDatapathConnection create(NetlinkChannel channel, Reactor reactor)
        throws Exception {
        return new OvsDatapathConnectionImpl(channel, reactor);
    }

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
     *
     * @see Callback
     */
    public void datapathsEnumerate(Callback<Set<Datapath>> callback) {
        datapathsEnumerate(callback, DEF_REPLY_TIMEOUT);
    }

    /**
     * Callback based api for enumerating datapaths.
     *
     * @param callback the callback which will receive the results.
     * @param timeoutMillis the timeout we need to wait for the results.
     *
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
     *
     * @return A future that hold the created datapath object
     *
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
     *
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
     *
     * @return A future that hold the delete datapath object
     *
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
     * @param callback the callback that will be provided the operation result.
     *
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
     *
     * @return A future that hold the created datapath object
     *
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
     *
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
     * @param portName  the name of the port we want to retrieve information for.
     * @param datapath  the datapath owning the port.
     *
     * @return a future
     */
    public Future<Port> portsGet(final @Nonnull String portName,
                                 final @Nullable Datapath datapath) {
        ValueFuture<Port> future = ValueFuture.create();
        portsGet(portName, datapath, wrapFuture(future), DEF_REPLY_TIMEOUT);
        return future;
    }

    /**
     * Callback based api for retrieving a port by name.
     *
     * @param portName      the name of the port.
     * @param datapath      the datapath that this port should be located on.
     * @param callback      the callback that will be provided the operation result.
     */
    public void portsGet(final @Nonnull String portName,
                         final @Nullable Datapath datapath,
                         final @Nonnull Callback<Port> callback) {
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
                         final @Nonnull Callback<Port> callback,
                         final long timeoutMillis) {
        _doPortsGet(portName, null, datapath, callback, timeoutMillis);
    }

    /**
     * Future based api to retrieve port information.
     *
     * @param datapath the datapath which holds the port
     * @param port the port we want to retrieve information for
     * @return a future
     */
    public Future<Port> portsGet(final int portId,
                                 final @Nonnull Datapath datapath) {
        ValueFuture<Port> future = ValueFuture.create();
        portsGet(portId, datapath, wrapFuture(future), DEF_REPLY_TIMEOUT);
        return future;
    }

    /**
     * Callback based api for retrieving a port by id.
     *
     * @param portId      the id of the port.
     * @param datapath    the datapath that this port should be located on.
     * @param callback    the callback that will be called with the result.
     */
    public void portsGet(final int portId,
                         final @Nonnull Datapath datapath,
                         final @Nonnull Callback<Port> callback) {
        portsGet(portId, datapath, callback, DEF_REPLY_TIMEOUT);
    }

    /**
     * Callback based api for retrieving a port by id.
     *
     * @param portId      the id of the port.
     * @param datapath    the datapath that this port should be located on.
     * @param callback    the callback that will be called with the result.
     * @param timeoutMillis the timeout we are willing to wait for a result.
     */
    public void portsGet(final int portId,
                         final @Nonnull Datapath datapath,
                         final @Nonnull Callback<Port> callback,
                         final long timeoutMillis) {
        _doPortsGet(null, portId, datapath, callback, timeoutMillis);
    }

    protected abstract void _doPortsGet(final @Nullable String name,
                                        final @Nullable Integer portId,
                                        final @Nullable Datapath datapath,
                                        final @Nonnull Callback<Port> callback,
                                        final long timeoutMillis);

    /**
     * Future based api to updating port information.
     *
     * @param port the port we want to retrieve information for
     * @param datapath the datapath which holds the port
     *
     * @return a future holding the updated port information.
     */
    public Future<Port> portsSet(final @Nonnull Port port,
                                 final @Nullable Datapath datapath) {
        ValueFuture<Port> future = ValueFuture.create();
        portsSet(port, datapath, wrapFuture(future), DEF_REPLY_TIMEOUT);
        return future;
    }

    /**
     * Callback based api for updating port information.
     *
     * @param port        the port description.
     * @param datapath    the datapath that this port should be located on.
     *
     * @param callback    the callback that will be called with the result.
     */
    public void portsSet(final @Nonnull Port port,
                         final @Nullable Datapath datapath,
                         final @Nonnull Callback<Port> callback) {
        portsSet(port, datapath, callback, DEF_REPLY_TIMEOUT);
    }

    /**
     * Callback based api for retrieving a port by id.
     *
     * @param port          the updated port description.
     * @param datapath      the datapath this port should be located on.
     * @param callback      the callback that will be called with the result.
     *
     * @param timeoutMillis the timeout we are willing to wait for a result.
     */
    public void portsSet(final @Nonnull Port port,
                         final @Nullable Datapath datapath,
                         final @Nonnull Callback<Port> callback,
                         final long timeoutMillis) {
        _doPortsSet(port, datapath, callback, timeoutMillis);
    }

    protected abstract void _doPortsSet(@Nonnull final Port port,
                                        @Nullable final Datapath datapath,
                                        @Nonnull final Callback<Port> callback,
                                        final long timeoutMillis);

    /**
     * Future based api for listing ports of a datapath.
     *
     * @param datapath is the datapath we want to list ports from.
     * @return A future that holds the set of ports visible on the path
     * @see Future
     */
    public Future<Set<Port>> portsEnumerate(@Nonnull Datapath datapath) {
        ValueFuture<Set<Port>> valueFuture = ValueFuture.create();
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
                               @Nonnull Callback<Set<Port>> callback) {
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
                               @Nonnull Callback<Set<Port>> callback,
                               final long timeoutMillis) {
        _doPortsEnumerate(datapath, callback, timeoutMillis);
    }

    protected abstract void _doPortsEnumerate(Datapath datapath, Callback<Set<Port>> callback, long timeoutMillis);

    /**
     * Future based api for adding a new port to a datapath.
     *
     * @param datapath the datapath we want to list ports from.
     * @param port     the specification of the port we want to create.
     * @return A future that holds the newly create port.
     * @see Future
     * @see Ports
     */
    public Future<Port> portsCreate(@Nonnull Datapath datapath, @Nonnull Port port) {
        ValueFuture<Port> valueFuture = ValueFuture.create();
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
    public void portsCreate(@Nonnull Datapath datapath, @Nonnull Port port,
                            @Nonnull Callback<Port> callback) {
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
    public void portsCreate(@Nonnull Datapath datapath, @Nonnull Port port,
                            @Nonnull Callback<Port> callback, long timeoutMillis) {

        _doPortsCreate(datapath, port, callback, timeoutMillis);
    }

    protected abstract void _doPortsCreate(@Nonnull Datapath datapath,
                                           @Nonnull Port port,
                                           @Nonnull Callback<Port> callback,
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
    public void datapathsGet(@Nonnull String name, Callback<Datapath> callback, long timeoutMillis) {
        _doDatapathsGet(null, name, callback, DEF_REPLY_TIMEOUT);
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

    public Future<Set<Flow>> flowsEnumerate(@Nonnull final Datapath datapath) {
        ValueFuture<Set<Flow>> flowsFuture = ValueFuture.create();
        flowsEnumerate(datapath, wrapFuture(flowsFuture));
        return flowsFuture;
    }

    public void flowsEnumerate(@Nonnull final Datapath datapath,
                               @Nonnull final Callback<Set<Flow>> callback) {
        flowsEnumerate(datapath, callback, DEF_REPLY_TIMEOUT);
    }

    public void flowsEnumerate(@Nonnull final Datapath datapath,
                               @Nonnull final Callback<Set<Flow>> callback,
                               long timeoutMillis) {
        _doFlowsEnumerate(datapath, callback, timeoutMillis);
    }

    protected abstract void _doFlowsEnumerate(@Nonnull final Datapath datapath,
                                              @Nonnull final Callback<Set<Flow>> callback,
                                              long timeoutMillis);
}
