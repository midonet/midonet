/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test.openflow;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import org.hamcrest.Matchers;
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

    public PortStats expectRx(long i) {
        assertThat("We have a matching number of received packets",
                   stat.getReceievePackets(), equalTo(i));
        return this;
    }

    public PortStats expectTx(long i) {
        assertThat("We have a matching number of transmitted packets",
                   stat.getTransmitPackets(), equalTo(i));
        return this;
    }

    public PortStats expectRxDrop(long i) {
        assertThat("We have a matching number of dropped received packets",
                   stat.getReceiveDropped(), equalTo(i));
        return this;
    }

    public PortStats expectTxDrop(long i) {
        assertThat("We have a matching number of dropped transmitted packets",
                   stat.getTransmitDropped(), equalTo(i));
        return this;
    }

    public PortStats refresh() {
        stat = controller.getPortReply(portNum);
        return this;
    }
}
