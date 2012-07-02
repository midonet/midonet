/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test.openflow;

import java.util.List;

import org.openflow.protocol.OFMatch;
import org.openflow.protocol.statistics.OFAggregateStatisticsReply;
import org.openflow.protocol.statistics.OFPortStatisticsReply;

public interface OpenFlowStats {
    OFPortStatisticsReply getPortReply(short portNum);

    PortStats getPortStats(short portNum);

    List<PortStats> getPortStats();

    List<FlowStats> getFlowStats(OFMatch match);

    OFAggregateStatisticsReply getAggregateStats(OFMatch match);

    AgFlowStats getAgFlowStats(OFMatch match);

    List<TableStats> getTableStats();

}
