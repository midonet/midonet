/*
 * @(#)MockControllerStub.java
 *
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.openflow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openflow.protocol.OFFeaturesReply;
import org.openflow.protocol.OFFlowMod;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFPort;
import org.openflow.protocol.OFStatisticsReply;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.statistics.OFAggregateStatisticsReply;
import org.openflow.protocol.statistics.OFAggregateStatisticsRequest;
import org.openflow.protocol.statistics.OFDescriptionStatistics;
import org.openflow.protocol.statistics.OFFlowStatisticsReply;
import org.openflow.protocol.statistics.OFFlowStatisticsRequest;
import org.openflow.protocol.statistics.OFPortStatisticsReply;
import org.openflow.protocol.statistics.OFPortStatisticsRequest;
import org.openflow.protocol.statistics.OFQueueStatisticsReply;
import org.openflow.protocol.statistics.OFQueueStatisticsRequest;
import org.openflow.protocol.statistics.OFStatistics;
import org.openflow.protocol.statistics.OFTableStatistics;

public class MockControllerStub implements ControllerStub {
    public class Packet {
        public int bufferId;
        public short inPort;
        public List<OFAction> actions;
        public byte[] data;

        public Packet(int bufferId, short inPort, List<OFAction> actions,
                      byte[] data) {
            super();
            this.bufferId = bufferId;
            this.inPort = inPort;
            this.actions = actions;
            this.data = data;
        }

        public String toString() {
            return String.format("Packet{buffer:%d, inPort:%d, actions:%s, " +
                             "data[len=%d]}", bufferId, inPort,
                             actions.toString(), data.length);
        }
    }

    public static class Flow {
        public OFMatch match;
        public long cookie;
        public short command;
        public short idleTimeoutSecs;
        public short hardTimeoutSecs;
        public short priority;
        public int bufferId;
        public short outPort;
        public boolean sendFlowRemove;
        public boolean checkOverlap;
        public boolean emergency;
        public List<OFAction> actions;
        public long matchTunnelId;

        public Flow(OFMatch match, long cookie, short command,
                short idleTimeoutSecs, short hardTimeoutSecs, short priority,
                int bufferId, short outPort, boolean sendFlowRemove,
                boolean checkOverlap, boolean emergency,
                List<OFAction> actions, long matchTunnelId) {
            this.match = match;
            this.cookie = cookie;
            this.idleTimeoutSecs = idleTimeoutSecs;
            this.hardTimeoutSecs = hardTimeoutSecs;
            this.priority = priority;
            this.bufferId = bufferId;
            this.outPort = outPort;
            this.sendFlowRemove = sendFlowRemove;
            this.checkOverlap = checkOverlap;
            this.emergency = emergency;
            this.actions = actions;
            this.matchTunnelId = matchTunnelId;
        }

        @Override
        public String toString() {
            return "Flow{" +
                    "actions=" + actions +
                    ", match=" + match +
                    ", cookie=" + cookie +
                    ", command=" + command +
                    ", idleTimeoutSecs=" + idleTimeoutSecs +
                    ", hardTimeoutSecs=" + hardTimeoutSecs +
                    ", priority=" + priority +
                    ", bufferId=" + bufferId +
                    ", outPort=" + outPort +
                    ", sendFlowRemove=" + sendFlowRemove +
                    ", checkOverlap=" + checkOverlap +
                    ", emergency=" + emergency +
                    ", matchTunnelId=" + matchTunnelId +
                    '}';
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Flow flow = (Flow) o;

            if (bufferId != flow.bufferId) return false;
            if (checkOverlap != flow.checkOverlap) return false;
            if (command != flow.command) return false;
            if (cookie != flow.cookie) return false;
            if (emergency != flow.emergency) return false;
            if (hardTimeoutSecs != flow.hardTimeoutSecs) return false;
            if (idleTimeoutSecs != flow.idleTimeoutSecs) return false;
            if (matchTunnelId != flow.matchTunnelId) return false;
            if (outPort != flow.outPort) return false;
            if (priority != flow.priority) return false;
            if (sendFlowRemove != flow.sendFlowRemove) return false;
            if (actions != null ? !actions.equals(flow.actions) : flow.actions != null)
                return false;
            if (match != null ? !match.equals(flow.match) : flow.match != null)
                return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = match != null ? match.hashCode() : 0;
            result = 31 * result + (int) (cookie ^ (cookie >>> 32));
            result = 31 * result + (int) command;
            result = 31 * result + (int) idleTimeoutSecs;
            result = 31 * result + (int) hardTimeoutSecs;
            result = 31 * result + (int) priority;
            result = 31 * result + bufferId;
            result = 31 * result + (int) outPort;
            result = 31 * result + (sendFlowRemove ? 1 : 0);
            result = 31 * result + (checkOverlap ? 1 : 0);
            result = 31 * result + (emergency ? 1 : 0);
            result = 31 * result + (actions != null ? actions.hashCode() : 0);
            result = 31 * result + (int) (matchTunnelId ^ (matchTunnelId >>> 32));
            return result;
        }
    }

    // Accumulated calls to sendFlowModAdd()
    public List<Flow> addedFlows = new ArrayList<Flow>();
    // Accumulated calls to sendFlowModDelete()
    public List<Flow> deletedFlows = new ArrayList<Flow>();
    public List<Packet> sentPackets = new ArrayList<Packet>();
    public List<Integer> droppedPktBufIds = new ArrayList<Integer>();
    public OFFeaturesReply features = null;
    public List<OFStatistics> sentStatsRequest = new ArrayList<OFStatistics>();
    public OFStatisticsReply statisticsReply = null;

    @Override
    public void setController(Controller controller) {
    }

    public void setFeatures(OFFeaturesReply features) {
        this.features = features;
    }

    @Override
    public OFFeaturesReply getFeatures() {
        return features;
    }

    @Override
    public void sendFlowModAdd(OFMatch match, long cookie,
            short idleTimeoutSecs, short hardTimoutSecs, short priority,
            int bufferId, boolean sendFlowRemove, boolean checkOverlap,
            boolean emergency, List<OFAction> actions) {
        sendFlowModAdd(match, cookie, idleTimeoutSecs, hardTimoutSecs,
                priority, bufferId, sendFlowRemove ,checkOverlap, emergency,
                actions, 0);
    }

    @Override
    public void sendFlowModAdd(OFMatch match, long cookie,
            short idleTimeoutSecs, short hardTimoutSecs, short priority,
            int bufferId, boolean sendFlowRemove, boolean checkOverlap,
            boolean emergency, List<OFAction> actions, long matchingTunnelId) {
        addedFlows.add(new Flow(match, cookie,  OFFlowMod.OFPFC_ADD,
                idleTimeoutSecs, hardTimoutSecs, priority, bufferId,
                OFPort.OFPP_NONE.getValue(), sendFlowRemove, checkOverlap,
                emergency, actions, matchingTunnelId));
        if (bufferId != 0xffffffff && null != actions && 0 != actions.size()) {
            sentPackets.add(new Packet(bufferId, (short)-1, actions,
                    new byte[] {}));
        }
    }

    @Override
    public void sendFlowModDelete(OFMatch match, boolean strict,
                                  short priority, short outPort) {
        sendFlowModDelete(match, strict, priority, outPort, 0, 0);
    }

    @Override
    public void sendFlowModDelete(OFMatch match, boolean strict, short priority,
            short outPort, long matchingTunnelId, long cookie) {
        // For deletedFlows, use hardTimeout for outPort and
        // sendFlowRemove for strict.
        deletedFlows.add(new Flow(match, cookie,
                strict ? OFFlowMod.OFPFC_DELETE_STRICT : OFFlowMod.OFPFC_DELETE,
                (short) 0, (short) 0, priority, -1, outPort,
                false, false, false, null, matchingTunnelId));
    }

    @Override
    public void sendPacketOut(int bufferId, short inPort,
            List<OFAction> actions, byte[] data) {
        if (null == actions || 0 == actions.size())
            droppedPktBufIds.add(bufferId);
        else
            sentPackets.add(new Packet(bufferId, inPort, actions, data));
    }

    @Override
    public void enableNxm() {
        // For now, don't enforce the behavior.
    }

    @Override
    public void disableNxm() {
        // For now, don't enforce the behavior.
    }

    @Override
    public void sendDescStatsRequest(
        SuccessHandler<List<OFDescriptionStatistics>> onSuccess, long timeout,
        TimeoutHandler onTimeout) {
        OFDescriptionStatistics descStatsREquest = new OFDescriptionStatistics();
        sentStatsRequest.add(descStatsREquest);
    }

    @Override
    public void sendFlowStatsRequest(OFMatch match, byte tableId,
                                     short outPort,
                                     SuccessHandler<List<OFFlowStatisticsReply>> onSuccess,
                                     long timeout, TimeoutHandler onTimeout) {
        OFFlowStatisticsRequest flowStatsRequest =
                new OFFlowStatisticsRequest();
        flowStatsRequest.setMatch(match);
        flowStatsRequest.setTableId(tableId);
        flowStatsRequest.setOutPort(outPort);
        sentStatsRequest.add(flowStatsRequest);
    }

    @Override
    public void sendAggregateStatsRequest(OFMatch match, byte tableId, short outPort,
                                          SuccessHandler<List<OFAggregateStatisticsReply>> onSuccess,
                                          long timeout, TimeoutHandler onTimeout) {
        OFAggregateStatisticsRequest aggregateStatsRequest =
            new OFAggregateStatisticsRequest();

        aggregateStatsRequest.setMatch(match);
        aggregateStatsRequest.setTableId(tableId);
        aggregateStatsRequest.setOutPort(outPort);
        sentStatsRequest.add(aggregateStatsRequest);
    }

    @Override
    public void sendTableStatsRequest(SuccessHandler<List<OFTableStatistics>> onSuccess,
                                      long timeout,
                                      TimeoutHandler onTimeout) {
        OFTableStatistics tableStatsRequest = new OFTableStatistics();

        sentStatsRequest.add(tableStatsRequest);
    }

    @Override
    public void sendPortStatsRequest(short portNo,
                                    SuccessHandler<List<OFPortStatisticsReply>> onSuccess,
                                    long timeout, TimeoutHandler onTimeout) {
        OFPortStatisticsRequest portStatsRequest =
                new OFPortStatisticsRequest();

        portStatsRequest.setPortNumber(portNo);
        sentStatsRequest.add(portStatsRequest);
    }

    @Override
    public void sendQueueStatsRequest(short portNo, int queueId,
                                      SuccessHandler<List<OFQueueStatisticsReply>> onSuccess,
                                      long timeout, TimeoutHandler onTimeout) {
        Map <Short, Set<Integer>> queueRequests =
            new HashMap<Short, Set<Integer>>();
        Set<Integer> queueIds = new HashSet<Integer>();

        queueIds.add(queueId);
        queueRequests.put(portNo, queueIds);
        sendQueueStatsRequest(queueRequests, onSuccess, timeout, onTimeout);
    }

    @Override
    public void sendQueueStatsRequest(Map<Short, Set<Integer>> requests,
                                      SuccessHandler<List<OFQueueStatisticsReply>> onSuccess,
                                      long timeout, TimeoutHandler onTimeout) {
        for (Map.Entry<Short, Set<Integer>> request: requests.entrySet()) {
            for (Integer queueId: request.getValue()) {
                OFQueueStatisticsRequest queueStatsRequest =
                    new OFQueueStatisticsRequest();

                queueStatsRequest.setPortNumber(request.getKey());
                queueStatsRequest.setQueueId(queueId);
                sentStatsRequest.add(queueStatsRequest);
            }
        }
    }

    @Override
    public void close() {

    }

}
