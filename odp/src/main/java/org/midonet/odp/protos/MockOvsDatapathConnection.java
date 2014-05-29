/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
 */
package org.midonet.odp.protos;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.midonet.netlink.BufferPool;
import org.midonet.netlink.Callback;
import org.midonet.netlink.NetlinkChannel;
import org.midonet.netlink.exceptions.NetlinkException;
import org.midonet.odp.*;
import org.midonet.odp.flows.FlowAction;
import org.midonet.odp.ports.InternalPort;
import org.midonet.util.BatchCollector;
import org.midonet.util.functors.Callback2;

import static org.midonet.netlink.exceptions.NetlinkException.ErrorCode.*;

/**
 * Mock implementation to be used in test cases and non linux hosts.
 */
public class MockOvsDatapathConnection extends OvsDatapathConnection {

    private final Set<Datapath> datapaths;
    private final Map<Datapath, Set<DpPort>> datapathPorts;
    private final Map<Datapath, AtomicInteger> portsIndexes;
    public final Map<FlowMatch, Flow> flowsTable;
    public final List<Packet> packetsSent;

    private Callback2<Packet,List<FlowAction>> packetExecCb = null;
    private FlowListener flowsCb = null;
    private boolean initialized = false;

    AtomicInteger datapathIds = new AtomicInteger(1);

    public MockOvsDatapathConnection(NetlinkChannel channel) {
        super(channel, new BufferPool(128, 512, 0x1000));
        this.datapaths = Collections.newSetFromMap(
                            new ConcurrentHashMap<Datapath,Boolean>());
        this.datapathPorts = new ConcurrentHashMap<Datapath, Set<DpPort>>();
        this.portsIndexes = new ConcurrentHashMap<Datapath, AtomicInteger>();
        this.flowsTable = new ConcurrentHashMap<FlowMatch, Flow>();
        this.packetsSent = new ArrayList<Packet>();
    }

    @Override
    protected void handleNotification(short type, byte cmd, int seq,
                                      int pid, ByteBuffer buffer) { /* no op */ }

    @Override
    public void initialize(Callback<Boolean> cb) {
        initialized = true;
        cb.onSuccess(true);
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    BatchCollector<Packet> notificationHandler;

    @Override
    protected void _doDatapathsSetNotificationHandler(@Nonnull BatchCollector<Packet> notificationHandler) {
        this.notificationHandler = notificationHandler;
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
        Set<DpPort> ports =
            Collections.newSetFromMap(new ConcurrentHashMap<DpPort,Boolean>());
        datapathPorts.put(datapath, ports);
        datapathPorts.get(datapath).add(makeDatapathPort(datapath));
        portsIndexes.put(datapath, new AtomicInteger(0));
        return datapath;
    }

    protected DpPort makeDatapathPort(Datapath datapath) {
        return DpPort.fakeFrom(new InternalPort(datapath.getName()), 0);
    }

    @Override
    protected void _doDatapathsDelete(String name, @Nonnull Callback<Datapath> callback, long timeoutMillis) {
        for (Datapath datapath : datapaths) {
            if (datapath.getName().equals(name)) {
                deleteDatapath(datapath);
                callback.onSuccess(datapath);
                return;
            }
        }

        fireDeviceNotFound(callback);
    }

    @Override
    protected void _doDatapathsDelete(int datapathId, @Nonnull Callback<Datapath> callback, long timeoutMillis) {
        for (Datapath datapath : datapaths) {
            if (datapath.getIndex() == datapathId) {
                deleteDatapath(datapath);
                callback.onSuccess(datapath);
                return;
            }
        }

        fireDeviceNotFound(callback);
    }

    @Override
    protected void _doDatapathsGet(String name, Callback<Datapath> callback, long defReplyTimeout) {
        for (Datapath datapath : datapaths) {
            if (datapath.getName().equals(name)) {
                callback.onSuccess(datapath);
                return;
            }
        }

        fireDeviceNotFound(callback);
    }

    @Override
    protected void _doDatapathsGet(int datapathId, Callback<Datapath> callback, long defReplyTimeout) {
        for (Datapath datapath : datapaths) {
            if (datapath.getIndex() == datapathId) {
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
        int portNo = portsIndexes.get(datapath).incrementAndGet();
        return DpPort.fakeFrom(port, portNo);
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
    protected void _doFlowsCreate(@Nonnull Datapath datapath, @Nonnull Flow flow, Callback<Flow> callback, long timeout) {
        flow.setLastUsedTime(System.currentTimeMillis());
        flowsTable.put(flow.getMatch(), flow);
        if (callback != null)
            callback.onSuccess(flow);
        if (flowsCb != null)
            flowsCb.flowCreated(flow);
    }

    @Override
    protected void _doFlowsDelete(@Nonnull Datapath datapath, @Nonnull Flow flow, @Nonnull Callback<Flow> callback, long timeout) {
       if(flowsTable.containsKey(flow.getMatch())){
           Flow removed = flowsTable.remove(flow.getMatch());
           callback.onSuccess(removed);
           if (flowsCb != null)
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
    protected void _doPacketsExecute(@Nonnull Datapath datapath,
                                     @Nonnull Packet packet,
                                     @Nonnull List<FlowAction> actions,
                                     Callback<Boolean> callback,
                                     long timeoutMillis) {
        packetsSent.add(packet);
        if (callback != null)
            callback.onSuccess(true);
        if (packetExecCb != null)
            packetExecCb.call(packet, actions);
    }

    public void packetsExecuteSubscribe(Callback2<Packet,List<FlowAction>> cb) {
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
