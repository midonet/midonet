/*
 * Copyright (c) 2012 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.odp.protos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.common.util.concurrent.ValueFuture;

import org.midonet.netlink.BufferPool;
import org.midonet.netlink.Callback;
import org.midonet.netlink.NetlinkChannel;
import org.midonet.netlink.exceptions.NetlinkException;
import org.midonet.packets.MAC;
import org.midonet.odp.Datapath;
import org.midonet.odp.Flow;
import org.midonet.odp.FlowMatch;
import org.midonet.odp.Packet;
import org.midonet.odp.DpPort;
import org.midonet.odp.ports.InternalPort;
import org.midonet.util.BatchCollector;
import org.midonet.util.eventloop.Reactor;
import org.midonet.util.throttling.NoOpThrottlingGuard;
import org.midonet.util.throttling.NoOpThrottlingGuardFactory;
import static org.midonet.netlink.exceptions.NetlinkException.ErrorCode.*;


/**
 * Mock implementation to be used in test cases and non linux hosts.
 */
public class MockOvsDatapathConnection extends OvsDatapathConnection {

    Set<Datapath> datapaths = new HashSet<Datapath>();

    Map<Datapath, Set<DpPort>> datapathPorts
        = new HashMap<Datapath, Set<DpPort>>();

    Map<Datapath, AtomicInteger> portsIndexes
        = new HashMap<Datapath, AtomicInteger>();

    public Map<FlowMatch, Flow> flowsTable = new HashMap<FlowMatch, Flow>();
    public List<Packet> packetsSent = new ArrayList<Packet>();

    org.midonet.util.functors.Callback<Packet, ?> packetExecCb = null;
    FlowListener flowsCb = null;

    boolean initialized = false;

    AtomicInteger datapathIds = new AtomicInteger(1);

    public MockOvsDatapathConnection(NetlinkChannel channel, Reactor reactor)
        throws Exception {
        super(channel, reactor, new NoOpThrottlingGuardFactory(),
              new NoOpThrottlingGuard(), new BufferPool(128, 512, 0x1000));
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

    BatchCollector<Packet> notificationHandler;

    @Override
    protected void _doDatapathsSetNotificationHandler(@Nonnull Datapath datapath,
                                                      @Nonnull BatchCollector<Packet> notificationHandler,
                                                      @Nonnull Callback<Boolean> operationCallback,
                                                      long timeoutMillis) {
        this.notificationHandler = notificationHandler;
        operationCallback.onSuccess(true);
    }

    public void triggerPacketIn(@Nonnull Packet packet) {
        notificationHandler.submit(packet);
        notificationHandler.endBatch();
    }

    public void triggerPacketsIn(@Nonnull List<Packet> packets) {
        for (Packet p : packets) {
            notificationHandler.submit(p);
        }
        notificationHandler.endBatch();
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
        datapathPorts.put(datapath, new HashSet<DpPort>());
        datapathPorts.get(datapath).add(makeDatapathPort(datapath));
        portsIndexes.put(datapath, new AtomicInteger(0));
        return datapath;
    }

    protected InternalPort makeDatapathPort(Datapath datapath) {
        InternalPort port = new InternalPort(datapath.getName());
        port.setPortNo(0);
        port.setStats(new DpPort.Stats());

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
    protected void _doPortsCreate(@Nonnull Datapath datapath, @Nonnull DpPort port,
                                  @Nonnull Callback<DpPort> callback,
                                  long timeoutMillis) {
        if ( ! datapaths.contains(datapath) ) {
            fireDeviceNotFound(callback);
            return;
        }

        port = fixupPort(datapath, port);
        datapathPorts.get(datapath).add(port);
        callback.onSuccess(port);
    }

    private DpPort fixupPort(Datapath datapath, DpPort port) {
        port.setStats(new DpPort.Stats());
        port.setPortNo(portsIndexes.get(datapath).incrementAndGet());
        return port;
    }


    @Override
    protected void _doPortsGet(@Nullable String name, @Nullable Integer portId,
                               @Nullable Datapath datapath,
                               @Nonnull Callback<DpPort> callback, long timeoutMillis) {
        Set<DpPort> ports = datapathPorts.get(datapath);

        if (ports == null) {
            fireDeviceNotFound(callback);
            return;
        }

        for (DpPort port : ports) {
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
    protected void _doPortsDelete(@Nonnull DpPort port, @Nullable Datapath datapath,
                                  @Nonnull Callback<DpPort> callback, long timeoutMillis) {
        Set<DpPort> myPorts = datapathPorts.get(datapath);

        if (myPorts == null) {
            fireDeviceNotFound(callback);
            return;
        }

        for (DpPort myPort : myPorts) {
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
    protected void _doPortsSet(@Nonnull DpPort port, @Nullable Datapath datapath, @Nonnull Callback<DpPort> callback, long timeoutMillis) {
        // no op
    }

    @Override
    protected void _doPortsEnumerate(@Nonnull Datapath datapath, @Nonnull Callback<Set<DpPort>> callback, long timeoutMillis) {
        Set<DpPort> myPorts = datapathPorts.get(datapath);

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
        Set<Flow> flows = new HashSet<Flow>();
        for(Flow flow: flowsTable.values()){
            flows.add(flow);
        }
        callback.onSuccess(flows);
    }

    @Override
    protected void _doFlowsCreate(@Nonnull Datapath datapath, @Nonnull Flow flow, @Nonnull Callback<Flow> callback, long timeout) {
        flow.setLastUsedTime(System.currentTimeMillis());
        flowsTable.put(flow.getMatch(), flow);
        callback.onSuccess(flow);
        flowsCb.flowCreated(flow);
    }

    @Override
    protected void _doFlowsDelete(@Nonnull Datapath datapath, @Nonnull Flow flow, @Nonnull Callback<Flow> callback, long timeout) {
       if(flowsTable.containsKey(flow.getMatch())){
           Flow removed = flowsTable.remove(flow.getMatch());
           callback.onSuccess(removed);
           flowsCb.flowDeleted(removed);
       }
        else{
           callback.onError(new NetlinkException(NetlinkException.ErrorCode.ENOENT));
       }
    }

    @Override
    protected void _doFlowsGet(@Nonnull Datapath datapath, @Nonnull FlowMatch match, @Nonnull Callback<Flow> callback, long timeout) {
        if(flowsTable.containsKey(match)){
            Flow flow = flowsTable.get(match);
            callback.onSuccess(flow);
        }
        else{
            callback.onSuccess(null);
        }
    }

    @Override
    protected void _doFlowsSet(@Nonnull Datapath datapath, @Nonnull Flow match, @Nonnull Callback<Flow> flowCallback, long timeout) {
        if(flowsTable.containsKey(match.getMatch())){
            flowsTable.remove(match.getMatch());
            flowsTable.put(match.getMatch(), match);
            flowCallback.onSuccess(match);
        }
        else{
            flowCallback.onError(new NetlinkException(NetlinkException.ErrorCode.ENOENT));
        }
    }


    @Override
    protected void _doFlowsFlush(@Nonnull Datapath datapath, @Nonnull Callback<Boolean> callback, long timeoutMillis) {
          flowsTable.clear();
          callback.onSuccess(true);
    }

    public void setFlowLastUsedTimeToNow(FlowMatch match){
        flowsTable.get(match).setLastUsedTime(System.currentTimeMillis());
    }

    @Override
    protected void _doPacketsExecute(@Nonnull Datapath datapath, @Nonnull Packet packet,
                                     @Nonnull Callback<Boolean> callback, long timeoutMillis) {
        packetsSent.add(packet);
        callback.onSuccess(true);
        if (packetExecCb != null)
            packetExecCb.onSuccess(packet);
    }

    public void packetsExecuteSubscribe(
            org.midonet.util.functors.Callback<Packet, ?> cb) {
        this.packetExecCb = cb;
    }

    public interface FlowListener {
        void flowCreated(Flow flow);
        void flowDeleted(Flow flow);
    }

    public void flowsSubscribe(FlowListener listener) {
        this.flowsCb = listener;
    }
}
