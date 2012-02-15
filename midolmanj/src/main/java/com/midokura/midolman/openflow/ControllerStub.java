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

// This is the analogue to the openfaucet "protocol" class in Python.
public interface ControllerStub {
    public static final int UNBUFFERED_ID = 0xffffffff;

    void setController(Controller controller);

    OFFeaturesReply getFeatures();

    void sendFlowModAdd(OFMatch match, long cookie, short idleTimeoutSecs,
                        short hardTimoutSecs, short priority, int bufferId,
                        boolean sendFlowRemove, boolean checkOverlap,
                        boolean emergency, List<OFAction> actions);

    /**
     *
     * @param match
     * @param cookie
     * @param idleTimeoutSecs
     * @param hardTimoutSecs
     * @param priority
     * @param bufferId
     * @param sendFlowRemove
     * @param checkOverlap
     * @param emergency
     * @param actions
     *      The list of OFAction to be applied to packets that match the flow.
     *      Actions of type NxAction will be cause the method to throw an
     *      IllegalArgumentException unless you previously called enableNxm.
     * @param matchingTunnelId
     *      Specifies additional meta-data that the flow entry should match: the
     *      tunnel ID of the incoming packet. This argument should be 0 unless
     *      you previously called enableNxm. Otherwise, this method throws an
     *      IllegalArgumentException.
     */
    void sendFlowModAdd(OFMatch match, long cookie, short idleTimeoutSecs,
                        short hardTimoutSecs, short priority, int bufferId,
                        boolean sendFlowRemove, boolean checkOverlap,
                        boolean emergency, List<OFAction> actions,
                        long matchingTunnelId);

    void sendFlowModDelete(OFMatch match, boolean strict,
                           short priority, short outPort);

    /**
     * Delete a flow entry or set of entries.
     *
     * @param match
     * @param strict
     * @param priority
     * @param outPort
     * @param matchingTunnelId
     *      Specifies additional meta-data that the flow entry should match: the
     *      tunnel ID of the incoming packet. This argument should be 0 unless
     *      you previously called enableNxm. Otherwise, this method throws an
     *      IllegalArgumentException.
     */
    void sendFlowModDelete(OFMatch match, boolean strict,
            short priority, short outPort, long matchingTunnelId);

    void sendPacketOut(int bufferId, short inPort, List<OFAction> actions,
                       byte[] data);

    void enableNxm();
    void disableNxm();

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
