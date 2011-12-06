/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.smoketest.openflow;

import junit.framework.Assert;

import org.openflow.protocol.OFMatch;
import org.openflow.protocol.statistics.OFAggregateStatisticsReply;

public class AgFlowStats {
    OFMatch match;
    ServiceController controller;
    OFAggregateStatisticsReply stat;

    public AgFlowStats(OFMatch match, ServiceController controller) {
        this.match = match;
        this.controller = controller;
    }

    public AgFlowStats expectFlowCount(int i) {
        Assert.assertEquals(i, stat.getFlowCount());
        return this;
    }

    public AgFlowStats expectPktCount(int i) {
        Assert.assertEquals(i, stat.getPacketCount());
        return this;
    }

    public AgFlowStats refresh() {
        return this;
    }

}
