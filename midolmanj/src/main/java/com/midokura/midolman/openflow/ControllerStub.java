/*
 * Copyright 2011 Midokura KK 
 */

package com.midokura.midolman.openflow;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openflow.protocol.OFFeaturesReply;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFStatisticsReply;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.statistics.OFStatistics;

// This is the analogue to the openfaucet "protocol" class in Python.
public interface ControllerStub {
    public static final int UNBUFFERED_ID = 0xffffffff;

    void setController(Controller controller);

    OFFeaturesReply getFeatures();

    void sendFlowModAdd(OFMatch match, long cookie, short idleTimeoutSecs, 
                        short hardTimoutSecs, short priority, int bufferId,
                        boolean sendFlowRemove, boolean checkOverlap,
                        boolean emergency, List<OFAction> actions);

    void sendFlowModDelete(OFMatch match, boolean strict,
                           short priority, short outPort);

    void sendPacketOut(int bufferId, short inPort, List<OFAction> actions,
                       byte[] data);

    public int sendDescStatsRequest();
    public int sendFlowStatsRequest(OFMatch match, byte tableId,
                                    short outPort);
    public int sendAggregateStatsRequest(OFMatch match, byte tableId,
                                         short outPort);
    public int sendTableStatsRequest();
    public int sendPortStatsRequest(short portNo);
    public int sendQueueStatsRequest(short portNo, int queueId);
    public int sendQueueStatsRequest(Map<Short, Set<Integer>> requests);
    public OFStatisticsReply getStatisticsReply(int xid);
    void close();

}
