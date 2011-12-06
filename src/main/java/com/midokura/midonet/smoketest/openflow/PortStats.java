/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.smoketest.openflow;

import static org.junit.Assert.assertEquals;

import org.openflow.protocol.statistics.OFPortStatisticsReply;

public class PortStats {

    ServiceController controller;
    short portNum;
    OFPortStatisticsReply stat;

    public PortStats(short portNum, ServiceController controller) {
        this.controller = controller;
        this.portNum = portNum;
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
        return this;
    }

}
