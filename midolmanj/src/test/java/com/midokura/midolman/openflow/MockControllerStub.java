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
import org.openflow.protocol.statistics.OFAggregateStatisticsRequest;
import org.openflow.protocol.statistics.OFDescriptionStatistics;
import org.openflow.protocol.statistics.OFFlowStatisticsRequest;
import org.openflow.protocol.statistics.OFPortStatisticsRequest;
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
    }

    public class Flow {
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

        public Flow(OFMatch match, long cookie, short command,
                short idleTimeoutSecs, short hardTimeoutSecs, short priority,
                int bufferId, short outPort, boolean sendFlowRemove,
                boolean checkOverlap, boolean emergency,
                List<OFAction> actions) {
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
        }

        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("match["+match+"]");
            sb.append(",cookie:"+cookie);
            sb.append(",idle:"+idleTimeoutSecs);
            sb.append(",hard:"+hardTimeoutSecs);
            sb.append(",pri:"+priority);
            sb.append(",buf_id:"+bufferId);
            sb.append(",out_port:" + (outPort &  0xfff));
            sb.append(",flow_remove:"+sendFlowRemove);
            sb.append(",overlap:"+checkOverlap);
            sb.append(",emerg:"+emergency);
            sb.append(",actions["+actions+"]");
            return sb.toString();
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

    public void setStatistics(OFStatisticsReply statisticsReply) {
        this.statisticsReply = statisticsReply;
    }

    @Override
    public OFStatisticsReply getStatisticsReply(int xid) {
        return statisticsReply;
    }

    @Override
    public void sendFlowModAdd(OFMatch match, long cookie,
            short idleTimeoutSecs, short hardTimoutSecs, short priority,
            int bufferId, boolean sendFlowRemove, boolean checkOverlap,
            boolean emergency, List<OFAction> actions) {
        addedFlows.add(new Flow(match, cookie,  OFFlowMod.OFPFC_ADD,
                idleTimeoutSecs, hardTimoutSecs, priority, bufferId,
                OFPort.OFPP_NONE.getValue(), sendFlowRemove, checkOverlap,
                emergency, actions));
        if (bufferId != 0xffffffff && null != actions && 0 != actions.size()) {
            sentPackets.add(new Packet(bufferId, (short)-1, actions,
                                       new byte[] {}));
        }
    }

    @Override
    public void sendFlowModDelete(OFMatch match, boolean strict,
                                  short priority, short outPort) {
        // For deletedFlows, use hardTimeout for outPort and
        // sendFlowRemove for strict.
        deletedFlows.add(new Flow(match, 0,
                strict ?  OFFlowMod.OFPFC_DELETE_STRICT :
                          OFFlowMod.OFPFC_DELETE,
                (short)0, (short)0, priority, -1, outPort,
                false, false, false, null));
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
    public int sendDescStatsRequest() {
        OFDescriptionStatistics descStatsREquest = new OFDescriptionStatistics();
        sentStatsRequest.add(descStatsREquest);
        return 0;
    }

    @Override
    public int sendFlowStatsRequest(OFMatch match, byte tableId,
                                    short outPort) {
        OFFlowStatisticsRequest flowStatsRequest =
                new OFFlowStatisticsRequest();
        flowStatsRequest.setMatch(match);
        flowStatsRequest.setTableId(tableId);
        flowStatsRequest.setOutPort(outPort);
        sentStatsRequest.add(flowStatsRequest);
        return 0;
    }

    @Override
    public int sendAggregateStatsRequest(OFMatch match, byte tableId,
                                         short outPort) {
        OFAggregateStatisticsRequest aggregateStatsRequest =
                new OFAggregateStatisticsRequest();

        aggregateStatsRequest.setMatch(match);
        aggregateStatsRequest.setTableId(tableId);
        aggregateStatsRequest.setOutPort(outPort);
        sentStatsRequest.add(aggregateStatsRequest);
        return 0;
    }

    @Override
    public int sendTableStatsRequest() {
        OFTableStatistics tableStatsRequest = new OFTableStatistics();

        sentStatsRequest.add(tableStatsRequest);
        return 0;
    }

    @Override
    public int sendPortStatsRequest(short portNo) {
        OFPortStatisticsRequest portStatsRequest =
                new OFPortStatisticsRequest();

        portStatsRequest.setPortNumber(portNo);
        sentStatsRequest.add(portStatsRequest);
        return 0;
    }

    @Override
    public int sendQueueStatsRequest(short portNo, int queueId) {
        Map <Short, Set<Integer>> queueRequests =
                new HashMap<Short, Set<Integer>>();
        Set<Integer> queueIds = new HashSet<Integer>();

        queueIds.add(queueId);
        queueRequests.put(portNo, queueIds);
        return sendQueueStatsRequest(queueRequests);
    }

    @Override
    public int sendQueueStatsRequest(Map<Short, Set<Integer>> requests) {
        for (Map.Entry<Short, Set<Integer>> request: requests.entrySet()) {
            for (Integer queueId: request.getValue()) {
                OFQueueStatisticsRequest queueStatsRequest =
                    new OFQueueStatisticsRequest();

                queueStatsRequest.setPortNumber(request.getKey());
                queueStatsRequest.setQueueId(queueId);
                sentStatsRequest.add(queueStatsRequest);
            }
        }
        return 0;
    }

    @Override
    public void close() {

    }

}
