package com.midokura.midolman.openflow;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.openflow.protocol.OFFeaturesReply;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.action.OFAction;

import com.midokura.midolman.openflow.ConfigHandler;
import com.midokura.midolman.openflow.Controller;
import com.midokura.midolman.openflow.ControllerStub;
import com.midokura.midolman.openflow.TimeoutHandler;

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
        public short priority;
        public int bufferId;
        public boolean sendFlowRemove;
        public boolean checkOverlap;
        public boolean emergency;
        public List<OFAction> actions;
        public short outPort;

        public Flow(OFMatch match, long cookie, short idleTimeoutSecs,
                short priority, int bufferId, boolean sendFlowRemove,
                boolean checkOverlap, boolean emergency,
                List<OFAction> actions, short outPort) {
            super();
            this.match = match;
            this.cookie = cookie;
            this.idleTimeoutSecs = idleTimeoutSecs;
            this.priority = priority;
            this.bufferId = bufferId;
            this.sendFlowRemove = sendFlowRemove;
            this.checkOverlap = checkOverlap;
            this.emergency = emergency;
            this.actions = actions;
            this.outPort = outPort;
        }
    }

    public List<Flow> addedFlows = new ArrayList<Flow>();
    public List<Packet> sentPackets = new ArrayList<Packet>();
    public List<Integer> droppedPktBufIds = new ArrayList<Integer>(); 

    @Override
    public void setController(Controller controller) {
    }

    @Override
    public OFFeaturesReply getFeatures() {
        return null;
    }

    @Override
    public void getConfigAsync(ConfigHandler configHandler,
            TimeoutHandler timeoutHandler, long timeoutMillis) {
    }

    @Override
    public void sendFlowModAdd(OFMatch match, long cookie,
            short idleTimeoutSecs, short priority, int bufferId,
            boolean sendFlowRemove, boolean checkOverlap, boolean emergency,
            List<OFAction> actions, short outPort) {
        addedFlows.add(new Flow(match, cookie, idleTimeoutSecs, priority,
                bufferId, sendFlowRemove, checkOverlap, emergency, actions,
                outPort));
    }

    @Override
    public void sendPacketOut(int bufferId, short inPort,
            List<OFAction> actions, byte[] data) {
        if (null == actions || 0 == actions.size())
            droppedPktBufIds.add(bufferId);
        else
            sentPackets.add(new Packet(bufferId, inPort, actions, data));
    }

}
