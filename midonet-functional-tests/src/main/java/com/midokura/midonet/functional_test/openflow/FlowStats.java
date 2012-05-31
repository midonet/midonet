/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test.openflow;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.openflow.protocol.OFMatch;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionOutput;
import org.openflow.protocol.action.OFActionType;
import org.openflow.protocol.statistics.OFFlowStatisticsReply;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

public class FlowStats {
    OFMatch match;
    OpenFlowStats controller;
    OFFlowStatisticsReply stat;

    public FlowStats(OFMatch match, OpenFlowStats controller,
            OFFlowStatisticsReply stat) {
        this.match = match;
        this.controller = controller;
        this.stat = stat;
    }

    public final OFMatch getMatch() {
        return match;
    }

    /**
     * Return the FlowStats from the list whose match field is equal to the one
     * in 'this'. Assert. This is a convenience method that can be used like
     * this:
     *
     * <pre>
     * {
     *     OFMatch match; // initialize appropriately
     *     List&lt;FlowStats&gt; stats = controller.getFlowStats(match);
     *     FlowStat fStat = stats.get(0);
     *     fStat.expectCount(4).expectOutput(1);
     *     stats = controller.getFlowStats(match); // refresh stats from switch
     *     fStat.findSameInList(stats).expectCount(5).expectOutput(1);
     * }
     * </pre>
     *
     * @return The equivalent FlowStat from the list or 'this' if none is found
     *         in the list. Assert.fail with a message if no equivalent is found
     *         in the list.
     */
    public FlowStats findSameInList(List<FlowStats> stats) {
        for (FlowStats fStat : stats) {
            if (match.equals(fStat.match))
                return fStat;
        }
        assertThat("Did not find a FlowStats with the same match.", false);
        return this;
    }

    public FlowStats expectCount(long i) {
        assertThat(stat.getPacketCount(), is(i));
        return this;
    }

    public FlowStats expectOutputActions(Set<Short> portNums) {
        List<OFAction> actions = stat.getActions();
        assertThat(actions.size(), greaterThanOrEqualTo(portNums.size()));
        Set<Short> actual = new HashSet<Short>();
        for (int i = 1; i <= portNums.size(); i++) {
            OFAction act = actions.get(actions.size() - i);
            assertThat(act.getType(), is(OFActionType.OUTPUT));
            OFActionOutput outAct = OFActionOutput.class.cast(act);
            actual.add(outAct.getPort());
        }
        assertThat(actual, is(portNums));
        return this;
    }

    public FlowStats expectOutputAction(short portNum) {
        List<OFAction> actions = stat.getActions();
        assertThat(actions.size(), greaterThan(0));
        OFAction act = actions.get(actions.size() - 1);
        assertThat(act.getType(), is(OFActionType.OUTPUT));
        OFActionOutput outAct = OFActionOutput.class.cast(act);
        assertThat(outAct.getPort(), is(portNum));
        return this;
    }
}
