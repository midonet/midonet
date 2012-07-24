/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test.openflow;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import javax.annotation.Nullable;

import com.google.common.util.concurrent.ValueFuture;
import org.openflow.protocol.OFFlowRemoved.OFFlowRemovedReason;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFMessage;
import org.openflow.protocol.OFPhysicalPort;
import org.openflow.protocol.OFPort;
import org.openflow.protocol.OFPortStatus.OFPortReason;
import org.openflow.protocol.statistics.OFAggregateStatisticsReply;
import org.openflow.protocol.statistics.OFFlowStatisticsReply;
import org.openflow.protocol.statistics.OFPortStatisticsReply;
import org.openflow.protocol.statistics.OFTableStatistics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.util.eventloop.SelectListener;
import com.midokura.util.eventloop.SelectLoop;
import com.midokura.midolman.openflow.Controller;
import com.midokura.midolman.openflow.ControllerStub;
import com.midokura.midolman.openflow.ControllerStubImpl;
import com.midokura.midolman.openflow.SuccessHandler;
import com.midokura.midolman.openflow.TimeoutHandler;

public class ServiceController implements Controller, OpenFlowStats,
                                          SelectListener {

    static final Logger log = LoggerFactory.getLogger(ServiceController.class);
    static final byte ALL_TABLES = (byte) 0xff;

    private ScheduledExecutorService executor;
    private ControllerStubImpl controllerStub;
    private SelectLoop loop;
    private SocketChannel client;
    private Thread myThread;
    private boolean connected;
    private int portNum;

    public ServiceController(int portNum) throws IOException {
        this.portNum = portNum;
        client = SocketChannel.open();
        client.configureBlocking(false);
        client.connect(new java.net.InetSocketAddress("127.0.0.1", portNum));

        executor = Executors.newScheduledThreadPool(1);
        loop = new SelectLoop(executor);
        loop.register(client, SelectionKey.OP_CONNECT, this);
        myThread = new Thread() {
            @Override
            public void run() {
                log.debug("before doLoop which will block");
                try {
                    loop.doLoop();
                } catch (IOException e) {
                    log.error("exited doLoop because of exception", e);
                }
                log.debug("after doLoop is done");
            }
        };
        myThread.start();
    }

    @Override
    public void handleEvent(SelectionKey key) throws IOException {
        log.info("handleEvent " + key);

        if (key.isConnectable())
            log.info("Can connect...");
        else {
            loop.register(client, SelectionKey.OP_CONNECT, this);
            return;
        }
        if (client.isConnectionPending()) {
            log.info("Connection still pending... try to finish");
            client.finishConnect();
        }
        client.socket().setTcpNoDelay(true);
        controllerStub = new ControllerStubImpl(client, loop, this);
        loop.register(client, SelectionKey.OP_READ, controllerStub);
        loop.wakeup();
        controllerStub.start();
    }

    @Override
    public void onConnectionMade() {
        connected = true;
        log.info("onConnectionMade");
    }

    @Override
    public void onConnectionLost() {
        connected = false;
        log.info("onConnectionLost");
    }

    @Override
    public void onPacketIn(int bufferId, int totalLen, short inPort, byte[] data) {
        log.info("onPacketIn");
    }

    @Override
    public void onPacketIn(int bufferId, int totalLen, short inPort,
                           byte[] data, long matchingTunnelId) {
        log.info("onPacketIn");
    }

    @Override
    public void onFlowRemoved(OFMatch match, long cookie, short priority,
                              OFFlowRemovedReason reason, int durationSeconds,
                              int durationNanoseconds, short idleTimeout, long packetCount,
                              long byteCount) {
        log.info("onFlowRemoved");
    }

    @Override
    public void onFlowRemoved(OFMatch match, long cookie, short priority,
                              OFFlowRemovedReason reason, int durationSeconds,
                              int durationNanoseconds, short idleTimeout, long packetCount,
                              long byteCount, long matchingTunnelId) {
        log.info("onFlowRemoved");
    }

    @Override
    public void onPortStatus(OFPhysicalPort port, OFPortReason status) {
        log.info("onPortStatus");
    }

    @Override
    public void onMessage(OFMessage m) {
        log.info("onMessage {}", m);
    }


    @Override
    public PortStats getPortStats(short portNum) {
        return new PortStats(portNum, this, getPortReply(portNum));
    }

    @Override
    public AgFlowStats getAgFlowStats(OFMatch match) {
        return new AgFlowStats(match, this, getAggregateStats(match));
    }

    @Override
    public OFPortStatisticsReply getPortReply(short portNum) {

        final ValueFuture<OFPortStatisticsReply> futureValue = ValueFuture.create();

        controllerStub.sendPortStatsRequest(
            portNum,
            new SuccessHandler<List<OFPortStatisticsReply>>() {
                @Override
                public void onSuccess(List<OFPortStatisticsReply> data) {
                    futureValue.set(data.get(0));
                }
            },
            1200, onTimeoutCancelFuture(futureValue));

        return fromTheFuture(futureValue, "specific port stats");
    }


    @Nullable
    @Override
    public List<PortStats> getPortStats() {

        final ValueFuture<List<PortStats>> value = ValueFuture.create();

        controllerStub.sendPortStatsRequest(
            OFPort.OFPP_NONE.getValue(),
            new SuccessHandler<List<OFPortStatisticsReply>>() {
                @Override
                public void onSuccess(List<OFPortStatisticsReply> data) {
                    final List<PortStats> result = new ArrayList<PortStats>();

                    for (OFPortStatisticsReply reply : data) {
                        result.add(
                            new PortStats(reply.getPortNumber(),
                                          ServiceController.this,
                                          reply));
                    }

                    value.set(result);
                }
            }, 1200, onTimeoutCancelFuture(value));

        return fromTheFuture(value, "global port stats");
    }

    @Nullable
    @Override
    public List<FlowStats> getFlowStats(OFMatch match) {

        final ValueFuture<List<FlowStats>> value = ValueFuture.create();

        controllerStub.sendFlowStatsRequest(
            match, ALL_TABLES, OFPort.OFPP_NONE.getValue(),
            new SuccessHandler<List<OFFlowStatisticsReply>>() {
                @Override
                public void onSuccess(List<OFFlowStatisticsReply> data) {
                    List<FlowStats> result = new ArrayList<FlowStats>();
                    for (OFFlowStatisticsReply flowStats : data) {
                        result.add(new FlowStats(flowStats.getMatch(),
                                                 ServiceController.this,
                                                 flowStats));
                    }

                    value.set(result);
                }
            }, 1200, onTimeoutCancelFuture(value)
        );

        return fromTheFuture(value, "flow stats");
    }

    @Nullable
    @Override
    public OFAggregateStatisticsReply getAggregateStats(OFMatch match) {

        final ValueFuture<OFAggregateStatisticsReply> value = ValueFuture.create();

        controllerStub.sendAggregateStatsRequest(
            match, ALL_TABLES, OFPort.OFPP_NONE.getValue(),
            new SuccessHandler<List<OFAggregateStatisticsReply>>() {
                @Override
                public void onSuccess(List<OFAggregateStatisticsReply> data) {
                    value.set(data.size() == 0 ? null : data.get(0));
                }
            },
            1200, onTimeoutCancelFuture(value));

        return fromTheFuture(value, "aggregate stats");
    }

    @Override
    public List<TableStats> getTableStats() {
        final ValueFuture<List<TableStats>> value = ValueFuture.create();

        controllerStub.sendTableStatsRequest(
            new SuccessHandler<List<OFTableStatistics>>() {
                @Override
                public void onSuccess(List<OFTableStatistics> data) {
                    List<TableStats> result = new ArrayList<TableStats>();
                    for (OFTableStatistics tStat : data) {
                        result.add(new TableStats(tStat.getTableId(), ServiceController.this, tStat));
                    }
                    value.set(result);
                }
            }, 1200, onTimeoutCancelFuture(value));

        return fromTheFuture(value, "table stats");
    }

    @Nullable
    private <T> T fromTheFuture(ValueFuture<T> future, String reason) {
        try {
            return future.get();
        } catch (Exception e) {
            log.error("Error waiting for: {}", reason, e);
            return null;
        }
    }
    private TimeoutHandler onTimeoutCancelFuture(final ValueFuture<?> valueFuture) {
        return new TimeoutHandler() {
            @Override
            public void onTimeout() {
                valueFuture.cancel(true);
            }
        };
    }

    @Override
    public void setControllerStub(ControllerStub controllerStub) {
        // TODO Auto-generated method stub
    }

    public boolean isConnected() {
        return connected;
    }

    public int getPortNum() {
        return portNum;
    }

}
