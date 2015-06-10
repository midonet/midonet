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
import org.midonet.odp.flows.*;
import org.midonet.odp.ports.InternalPort;
import org.midonet.util.BatchCollector;

import static org.midonet.ErrorCode.*;

/**
 * Mock implementation to be used in test cases and non linux hosts.
 */
public class MockOvsDatapathConnection extends OvsDatapathConnection {

    private final Set<Datapath> datapaths;
    private final Map<Datapath, Set<DpPort>> datapathPorts;
    private final Map<Datapath, AtomicInteger> portsIndexes;
    public final Map<FlowMatch, Flow> flowsTable = new ConcurrentHashMap<>();
    public final List<Packet> packetsSent;

    AtomicInteger datapathIds = new AtomicInteger(1);

    public MockOvsDatapathConnection(NetlinkChannel channel) {
        super(channel, new BufferPool(128, 512, 0x1000));
        this.datapaths = Collections.newSetFromMap(
                            new ConcurrentHashMap<Datapath,Boolean>());
        this.datapathPorts = new ConcurrentHashMap<>();
        this.portsIndexes = new ConcurrentHashMap<>();
        this.packetsSent = new ArrayList<>();
    }

    @Override
    protected void handleNotification(short type, byte cmd, int seq,
                                      int pid, ByteBuffer buffer) { /* no op */ }

    BatchCollector<Packet> notificationHandler;

    @Override
    protected void _doDatapathsSetNotificationHandler(@Nonnull BatchCollector<Packet> notificationHandler) {
        this.notificationHandler = notificationHandler;
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
        Set<Flow> flows = new HashSet<>();
        for(Flow flow: flowsTable.values()){
            flows.add(flow);
        }
        callback.onSuccess(flows);
    }

    @Override
    protected void _doFlowsCreate(@Nonnull Datapath datapath, @Nonnull Flow flow, Callback<Flow> callback, long timeout) {
        flow.setLastUsedMillis(System.currentTimeMillis());
        flowsTable.put(flow.getMatch(), flow);
        if (callback != null)
            callback.onSuccess(flow);
    }

    @Override
    protected void _doFlowsDelete(@Nonnull Datapath datapath,
                                  @Nonnull Iterable<FlowKey> keys,
                                  @Nonnull Callback<Flow> callback, long timeout) {
        FlowMatch match = new FlowMatch(keys);
        Flow removed = flowsTable.remove(match);
        if (removed != null) {
            callback.onSuccess(removed);
        } else {
            callback.onError(new NetlinkException(ENOENT));
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
            flowCallback.onError(new NetlinkException(ENOENT));
        }
    }


    @Override
    protected void _doFlowsFlush(@Nonnull Datapath datapath, @Nonnull Callback<Boolean> callback, long timeoutMillis) {
        flowsTable.clear();
        callback.onSuccess(true);
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
    }
}
