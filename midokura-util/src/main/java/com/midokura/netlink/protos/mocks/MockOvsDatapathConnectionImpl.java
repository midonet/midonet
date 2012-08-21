
/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.netlink.protos.mocks;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.common.util.concurrent.ValueFuture;

import com.midokura.netlink.Callback;
import com.midokura.netlink.NetlinkChannel;
import com.midokura.netlink.exceptions.NetlinkException;
import com.midokura.netlink.protos.OvsDatapathConnection;
import com.midokura.packets.MAC;
import com.midokura.sdn.dp.Datapath;
import com.midokura.sdn.dp.Flow;
import com.midokura.sdn.dp.FlowMatch;
import com.midokura.sdn.dp.Packet;
import com.midokura.sdn.dp.Port;
import com.midokura.sdn.dp.Ports;
import com.midokura.sdn.dp.ports.InternalPort;
import com.midokura.util.eventloop.Reactor;
import static com.midokura.netlink.exceptions.NetlinkException.ErrorCode.*;

/**
 * Mock implementation to be used in test cases and non linux hosts.
 */
public class MockOvsDatapathConnectionImpl extends OvsDatapathConnection {

    Set<Datapath> datapaths = new HashSet<Datapath>();

    Map<Datapath, Set<Port<?, ?>>> datapathPorts
        = new HashMap<Datapath, Set<Port<?, ?>>>();

    Map<Datapath, AtomicInteger> portsIndexes
        = new HashMap<Datapath, AtomicInteger>();

    boolean initialized = false;

    AtomicInteger datapathIds = new AtomicInteger(1);

    public MockOvsDatapathConnectionImpl(NetlinkChannel channel, Reactor reactor)
        throws Exception {
        super(channel, reactor);
    }

    @Override
    public Future<Boolean> initialize() throws Exception {
        initialized = true;
        ValueFuture<Boolean> future = ValueFuture.create();
        future.set(true);
        return future;
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    Callback<Packet> notificationHandler;

    @Override
    protected void _doDatapathsSetNotificationHandler(@Nonnull Datapath datapath,
                                                      @Nonnull Callback<Packet> notificationHandler,
                                                      @Nonnull Callback<Boolean> operationCallback,
                                                      long timeoutMillis) {
        this.notificationHandler = notificationHandler;
        operationCallback.onSuccess(true);
    }

    public void triggerPacketIn(@Nonnull Packet packet) {
        notificationHandler.onSuccess(packet);
    }

    @Override
    protected void _doDatapathsEnumerate(@Nonnull Callback<Set<Datapath>> callback, long timeoutMillis) {
        callback.onSuccess(datapaths);
    }

    @Override
    protected void _doDatapathsCreate(@Nonnull String name, @Nonnull Callback<Datapath> callback, long timeoutMillis) {
        callback.onSuccess(newDatapath(name));
    }

    private Datapath newDatapath(String name) {
        Datapath datapath = new Datapath(datapathIds.incrementAndGet(), name);
        datapaths.add(datapath);
        datapathPorts.put(datapath, new HashSet<Port<?, ?>>());
        datapathPorts.get(datapath).add(makeDatapathPort(datapath));
        portsIndexes.put(datapath, new AtomicInteger(0));
        return datapath;
    }

    protected InternalPort makeDatapathPort(Datapath datapath) {
        InternalPort port =
            Ports.newInternalPort(datapath.getName())
                 .setPortNo(0);

        port.setOptions(port.newOptions())
            .setStats(new Port.Stats())
            .setAddress(MAC.random().getAddress());

        return port;
    }

    @Override
    protected void _doDatapathsDelete(Integer datapathId, String name, @Nonnull Callback<Datapath> callback, long timeoutMillis) {
        for (Datapath datapath : datapaths) {
            if (datapathId != null && datapath.getIndex().equals(datapathId)) {
                deleteDatapath(datapath);
                callback.onSuccess(datapath);
                return;
            }

            if (name != null && datapath.getName().equals(name)) {
                deleteDatapath(datapath);
                callback.onSuccess(datapath);
                return;
            }
        }

        fireDeviceNotFound(callback);
    }

    @Override
    protected void _doDatapathsGet(Integer datapathId, String name, Callback<Datapath> callback, long defReplyTimeout) {
        for (Datapath datapath : datapaths) {
            if (datapathId != null && datapath.getIndex().equals(datapathId)) {
                callback.onSuccess(datapath);
                return;
            }

            if (name != null && datapath.getName().equals(name)) {
                callback.onSuccess(datapath);
                return;
            }
        }

        fireDeviceNotFound(callback);
    }


    private void deleteDatapath(Datapath datapath) {
        datapaths.remove(datapath);
        datapathPorts.remove(datapath);
        portsIndexes.remove(datapath);
    }

    @Override
    protected void _doPortsCreate(@Nonnull Datapath datapath, @Nonnull Port<?, ?> port,
                                  @Nonnull Callback<Port<?, ?>> callback,
                                  long timeoutMillis) {
        if ( ! datapaths.contains(datapath) ) {
            fireDeviceNotFound(callback);
            return;
        }

        port = fixupPort(datapath, port);
        datapathPorts.get(datapath).add(port);
        callback.onSuccess(port);
    }

    private Port<?, ?> fixupPort(Datapath datapath, Port<?, ?> port) {

        port.setStats(new Port.Stats())
            .setPortNo(portsIndexes.get(datapath).incrementAndGet());

        if (port.getAddress() == null) {
            port.setAddress(MAC.random().getAddress());
        }
        if (port.getOptions() == null) {
            port.setOptions();
        }

        return port;
    }


    @Override
    protected void _doPortsGet(@Nullable String name, @Nullable Integer portId,
                               @Nullable Datapath datapath,
                               @Nonnull Callback<Port<?, ?>> callback, long timeoutMillis) {
        Set<Port<?, ?>> ports = datapathPorts.get(datapath);

        if (ports == null) {
            fireDeviceNotFound(callback);
            return;
        }

        for (Port<?, ?> port : ports) {
            if (portId != null && port.getPortNo().equals(portId)) {
                callback.onSuccess(port);
                return;
            }

            if (name != null && port.getName().equals(name)) {
                callback.onSuccess(port);
                return;
            }
        }

        fireDeviceNotFound(callback);
    }

    @Override
    protected void _doPortsDelete(@Nonnull Port<?, ?> port, @Nullable Datapath datapath,
                                  @Nonnull Callback<Port<?, ?>> callback, long timeoutMillis) {
        Set<Port<?, ?>> myPorts = datapathPorts.get(datapath);

        if (myPorts == null) {
            fireDeviceNotFound(callback);
            return;
        }

        for (Port<?, ?> myPort : myPorts) {
            if (port.getPortNo() != null && myPort.getPortNo().equals(port.getPortNo())) {
                myPorts.remove(myPort);
                callback.onSuccess(myPort);
                return;
            }

            if (port.getName() != null && myPort.getName().equals(port.getName())) {
                myPorts.remove(myPort);
                callback.onSuccess(myPort);
                return;
            }
        }

        fireDeviceNotFound(callback);
    }

    @Override
    protected void _doPortsSet(@Nonnull Port<?, ?> port, @Nullable Datapath datapath, @Nonnull Callback<Port<?, ?>> callback, long timeoutMillis) {
        // no op
    }

    @Override
    protected void _doPortsEnumerate(@Nonnull Datapath datapath, @Nonnull Callback<Set<Port<?, ?>>> callback, long timeoutMillis) {
        Set<Port<?, ?>> myPorts = datapathPorts.get(datapath);

        if (myPorts == null) {
            fireDeviceNotFound(callback);
            return;
        }

        callback.onSuccess(myPorts);
    }

    private void fireDeviceNotFound(Callback<?> callback) {
        callback.onError(new NetlinkException(ENODEV));
    }

    @Override
    protected void _doFlowsEnumerate(Datapath datapath, @Nonnull Callback<Set<Flow>> callback, long timeoutMillis) {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    protected void _doFlowsCreate(@Nonnull Datapath datapath, @Nonnull Flow flow, @Nonnull Callback<Flow> callback, long timeout) {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    protected void _doFlowsDelete(@Nonnull Datapath datapath, @Nonnull Flow flow, @Nonnull Callback<Flow> callback, long timeout) {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    protected void _doFlowsGet(@Nonnull Datapath datapath, @Nonnull FlowMatch match, @Nonnull Callback<Flow> callback, long timeout) {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    protected void _doFlowsSet(@Nonnull Datapath datapath, @Nonnull Flow match, @Nonnull Callback<Flow> flowCallback, long timeout) {
        //To change body of implemented methods use File | Settings | File Templates.
    }


    @Override
    protected void _doFlowsFlush(@Nonnull Datapath datapath, @Nonnull Callback<Boolean> callback, long timeoutMillis) {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    protected void _doPacketsExecute(@Nonnull Datapath datapath, @Nonnull Packet packet,
                                     @Nonnull Callback<Boolean> callback, long timeoutMillis) {
        //To change body of implemented methods use File | Settings | File Templates.
    }
}
