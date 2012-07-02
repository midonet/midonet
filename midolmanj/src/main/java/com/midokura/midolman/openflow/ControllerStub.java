/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.openflow;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openflow.protocol.OFFeaturesReply;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.statistics.OFAggregateStatisticsReply;
import org.openflow.protocol.statistics.OFDescriptionStatistics;
import org.openflow.protocol.statistics.OFFlowStatisticsReply;
import org.openflow.protocol.statistics.OFPortStatisticsReply;
import org.openflow.protocol.statistics.OFQueueStatisticsReply;
import org.openflow.protocol.statistics.OFTableStatistics;

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
     * @param match
     * @param cookie
     * @param idleTimeoutSecs
     * @param hardTimoutSecs
     * @param priority
     * @param bufferId
     * @param sendFlowRemove
     * @param checkOverlap
     * @param emergency
     * @param actions          The list of OFAction to be applied to packets that match the flow.
     *                         Actions of type NxAction will be cause the method to throw an
     *                         IllegalArgumentException unless you previously called enableNxm.
     * @param matchingTunnelId Specifies additional meta-data that the flow entry should match: the
     *                         tunnel ID of the incoming packet. This argument should be 0 unless
     *                         you previously called enableNxm. Otherwise, this method throws an
     *                         IllegalArgumentException.
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
     * @param matchingTunnelId Specifies additional meta-data that the flow entry should match: the
     *                         tunnel ID of the incoming packet. This argument should be 0 unless
     *                         you previously called enableNxm. Otherwise, this method throws an
     *                         IllegalArgumentException.
     * @param cookie           Specifies additional meta-data the the flow entry should match: the
     *                         cookie that was assigned to the added flow at creation-time. The
     *                         argument should be 0 unless you previously called enableNxm.
     *                         Otherwise, this method throws an IllegalArgumentException.
     */
    void sendFlowModDelete(OFMatch match, boolean strict,
                           short priority, short outPort, long matchingTunnelId, long cookie);

    /**
     * Send a ofp_packet_out message to the switch. See OpenFlow1.0 spec,
     * Section 5.3.6 "Send Packet Message":
     * <p/>
     * "The buffer_id is the same given in the ofp_packet_in message. If the
     * buffer_id is -1, then the packet data is included in the data array.
     * If OFPP_TABLE is specified as the output port of an action, the in_port
     * in the packet_out message is used in the flow table lookup."
     * <p/>
     * In testing we found that the switch drops sent packets if inPort is
     * not OFPP_NONE and there is no action that outputs to OFPP_TABLE.
     *
     * @param bufferId ID assigned by datapath (-1 if none).
     * @param inPort   Packet’s input port (OFPP_NONE if none).
     * @param actions  Actions to apply to the packet.
     * @param data     The packet data (only if bufferId is -1).
     */
    void sendPacketOut(int bufferId, short inPort, List<OFAction> actions,
                       byte[] data);

    void enableNxm();

    void disableNxm();

    public void sendDescStatsRequest(SuccessHandler<List<OFDescriptionStatistics>> onSuccess,
                                     long timeout, TimeoutHandler onTimeout);

    public void sendFlowStatsRequest(OFMatch match, byte tableId, short outPort,
                                     SuccessHandler<List<OFFlowStatisticsReply>> onSuccess,
                                     long timeout, TimeoutHandler onTimeout);

    public void sendAggregateStatsRequest(OFMatch match, byte tableId, short outPort,
                                          SuccessHandler<List<OFAggregateStatisticsReply>> onSuccess,
                                          long timeout, TimeoutHandler onTimeout);

    public void sendTableStatsRequest(SuccessHandler<List<OFTableStatistics>> onSuccess,
                                      long timeout, TimeoutHandler onTimeout);

    public void sendPortStatsRequest(short portNo,
                                     SuccessHandler<List<OFPortStatisticsReply>> onSuccess,
                                     long timeout, TimeoutHandler onTimeout);

    public void sendQueueStatsRequest(short portNo, int queueId,
                                      SuccessHandler<List<OFQueueStatisticsReply>> onSuccess,
                                      long timeout, TimeoutHandler onTimeout);

    public void sendQueueStatsRequest(Map<Short, Set<Integer>> requests,
                                      SuccessHandler<List<OFQueueStatisticsReply>> onSuccess,
                                      long timeout, TimeoutHandler onTimeout);

    void close();
}
