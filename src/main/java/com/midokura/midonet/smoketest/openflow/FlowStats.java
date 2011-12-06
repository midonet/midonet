/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.smoketest.openflow;

import java.util.List;
import java.util.UUID;

import junit.framework.Assert;

import org.openflow.protocol.OFMatch;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionOutput;
import org.openflow.protocol.action.OFActionType;
import org.openflow.protocol.statistics.OFFlowStatisticsReply;

public class FlowStats {
    OFMatch match;
    ServiceController controller;
    OFFlowStatisticsReply stat;

    public FlowStats(OFMatch match, ServiceController controller) {
        this.match = match;
        this.controller = controller;
    }

    public void expectNone() {
        Assert.assertNull(stat);
    }

    public FlowStats refresh() {
        return this;
    }

    public FlowStats expectCount(int i) {
        Assert.assertEquals(i, stat.getPacketCount());
        return this;
    }

    public FlowStats expectOutputAction(UUID id) {
        // TODO: get the port number corresponding to this id.
        int portNum = 0;
        List<OFAction> actions = stat.getActions();
        Assert.assertTrue(actions.size() > 0);
        OFAction act = actions.get(actions.size() - 1);
        Assert.assertEquals(OFActionType.OUTPUT, act.getType());
        OFActionOutput outAct = OFActionOutput.class.cast(act);
        Assert.assertEquals(portNum, outAct.getPort());
        return this;
    }
}
