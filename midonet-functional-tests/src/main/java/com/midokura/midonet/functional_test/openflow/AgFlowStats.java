/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test.openflow;

import org.openflow.protocol.OFMatch;
import org.openflow.protocol.statistics.OFAggregateStatisticsReply;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

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
        assertThat(stat.getFlowCount(), is(i));
        return this;
    }

    public AgFlowStats expectPktCount(long i) {
        assertThat(stat.getPacketCount(), is(i));
        return this;
    }

    public AgFlowStats refresh() {
        stat = controller.getAggregateStats(match);
        return this;
    }
}
