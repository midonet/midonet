/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test.openflow;

import junit.framework.Assert;

import org.openflow.protocol.OFMatch;
import org.openflow.protocol.statistics.OFAggregateStatisticsReply;

public class AgFlowStats {
    OFMatch match;
    OpenFlowStats controller;
    OFAggregateStatisticsReply stat;

    public AgFlowStats(OFMatch match, OpenFlowStats controller,
            OFAggregateStatisticsReply stat) {
        this.match = match;
        this.controller = controller;
        this.stat = stat;
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
        stat = controller.getAgReply(match);
        return this;
    }
}
