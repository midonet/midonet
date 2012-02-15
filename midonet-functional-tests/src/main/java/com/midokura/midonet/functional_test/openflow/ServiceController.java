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

import org.openflow.protocol.OFFlowRemoved.OFFlowRemovedReason;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFMessage;
import org.openflow.protocol.OFPhysicalPort;
import org.openflow.protocol.OFPort;
import org.openflow.protocol.OFPortStatus.OFPortReason;
import org.openflow.protocol.OFStatisticsReply;
import org.openflow.protocol.statistics.OFAggregateStatisticsReply;
import org.openflow.protocol.statistics.OFFlowStatisticsReply;
import org.openflow.protocol.statistics.OFPortStatisticsReply;
import org.openflow.protocol.statistics.OFStatistics;
import org.openflow.protocol.statistics.OFTableStatistics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.eventloop.SelectListener;
import com.midokura.midolman.eventloop.SelectLoop;
import com.midokura.midolman.openflow.Controller;
import com.midokura.midolman.openflow.ControllerStub;
import com.midokura.midolman.openflow.ControllerStubImpl;

public class ServiceController implements Controller, OpenFlowStats,
        SelectListener {

    static final Logger log = LoggerFactory.getLogger(ServiceController.class);
    static final byte ALL_TABLES = (byte)0xff;

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
    public OFPortStatisticsReply getPortReply(short portNum) {
        int xid = controllerStub.sendPortStatsRequest(portNum);
        OFStatisticsReply reply = controllerStub.getStatisticsReply(xid);
        assert (reply != null);
        List<OFStatistics> stats = reply.getStatistics();
        return stats.size() == 0 ? null :
            OFPortStatisticsReply.class.cast(stats.get(0));
    }

    @Override
    public PortStats getPortStats(short portNum) {
        return new PortStats(portNum, this, getPortReply(portNum));
    }

    @Override
    public List<PortStats> getPortStats() {
        int xid = controllerStub.sendPortStatsRequest(OFPort.OFPP_NONE
                .getValue());
        OFStatisticsReply reply = controllerStub.getStatisticsReply(xid);
        assert (reply != null);
        List<PortStats> result = new ArrayList<PortStats>();
        for (OFStatistics stat : reply.getStatistics()) {
            OFPortStatisticsReply pStat = OFPortStatisticsReply.class
                    .cast(stat);
            result.add(new PortStats(pStat.getPortNumber(), this, pStat));
        }
        return result;
    }

    @Override
    public List<FlowStats> getFlowStats(OFMatch match) {
        int xid = controllerStub.sendFlowStatsRequest(match, ALL_TABLES,
                OFPort.OFPP_NONE.getValue());
        OFStatisticsReply reply = controllerStub.getStatisticsReply(xid);
        assert (reply != null);
        List<FlowStats> result = new ArrayList<FlowStats>();
        for (OFStatistics stat : reply.getStatistics()) {
            OFFlowStatisticsReply fStat = OFFlowStatisticsReply.class
                    .cast(stat);
            result.add(new FlowStats(fStat.getMatch(), this, fStat));
        }
        return result;
    }

    @Override
    public OFAggregateStatisticsReply getAgReply(OFMatch match) {
        int xid = controllerStub.sendAggregateStatsRequest(match, ALL_TABLES,
                OFPort.OFPP_NONE.getValue());
        OFStatisticsReply reply = controllerStub.getStatisticsReply(xid);
        assert (reply != null);
        List<OFStatistics> stats = reply.getStatistics();
        return stats.size() == 0 ? null :
            OFAggregateStatisticsReply.class.cast(stats.get(0));
    }

    @Override
    public AgFlowStats getAgFlowStats(OFMatch match) {
        return new AgFlowStats(match, this, getAgReply(match));
    }

    @Override
    public List<TableStats> getTableStats() {
        int xid = controllerStub.sendTableStatsRequest();
        OFStatisticsReply reply = controllerStub.getStatisticsReply(xid);
        assert (reply != null);
        List<TableStats> result = new ArrayList<TableStats>();
        for (OFStatistics stat : reply.getStatistics()) {
            OFTableStatistics tStat = OFTableStatistics.class.cast(stat);
            result.add(new TableStats(tStat.getTableId(), this, tStat));
        }
        return result;
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
