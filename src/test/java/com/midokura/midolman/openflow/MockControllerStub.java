package com.midokura.midolman.openflow;

import java.util.ArrayList;
import java.util.List;

import org.openflow.protocol.OFFeaturesReply;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.action.OFAction;

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
        public short idleTimeoutSecs;
        public short hardTimeoutSecs;
        public short priority;
        public int bufferId;
        public boolean sendFlowRemove;
        public boolean checkOverlap;
        public boolean emergency;
        public List<OFAction> actions;

        public Flow(OFMatch match, long cookie, short idleTimeoutSecs,
                short hardTimeoutSecs, short priority, int bufferId, 
                boolean sendFlowRemove, boolean checkOverlap, 
                boolean emergency, List<OFAction> actions) {
            this.match = match;
            this.cookie = cookie;
            this.idleTimeoutSecs = idleTimeoutSecs;
            this.hardTimeoutSecs = hardTimeoutSecs;
            this.priority = priority;
            this.bufferId = bufferId;
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
        addedFlows.add(new Flow(match, cookie, idleTimeoutSecs, hardTimoutSecs,
                priority, bufferId, sendFlowRemove, checkOverlap, emergency, 
                actions));
        if (bufferId != 0xffffffff && null != actions && 0 != actions.size()) {
            sentPackets.add(new Packet(bufferId, (short)-1, actions,
                                       new byte[] {}));
        }
    }

    @Override
    public void sendFlowModDelete(OFMatch match, boolean strict,
                                  short priority, short port) {
        // For deletedFlows, use hardTimeout for outPort and 
        // sendFlowRemove for strict.
        deletedFlows.add(new Flow(match, 0, (short)0, port, priority, 0, 
                                  strict, false, false, null));
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
    public void close() {
        
    }

}
