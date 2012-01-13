/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.smoketest.openflow;

import static org.junit.Assert.assertEquals;

import org.openflow.protocol.statistics.OFPortStatisticsReply;

public class PortStats {

    short portNum;
    OpenFlowStats controller;
    OFPortStatisticsReply stat;

    public PortStats(short portNum, OpenFlowStats controller,
            OFPortStatisticsReply stat) {
        this.portNum = portNum;
        this.controller = controller;
        this.stat = stat;
    }

    public PortStats expectRx(int i) {
        assertEquals(i, stat.getReceievePackets());
        return this;
    }

    public PortStats expectTx(int i) {
        assertEquals(i, stat.getTransmitPackets());
        return this;
    }

    public PortStats expectRxDrop(int i) {
        assertEquals(i, stat.getReceiveDropped());
        return this;
    }

    public PortStats expectTxDrop(int i) {
        assertEquals(i, stat.getTransmitDropped());
        return this;
    }

    public PortStats refresh() {
        stat = controller.getPortReply(portNum);
        return this;
    }
}
