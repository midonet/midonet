/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midolman;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.management.JMException;

import org.apache.zookeeper.KeeperException;
import org.openflow.protocol.OFMatch;

import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.packets.Ethernet;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkStateSerializationException;


public interface ForwardingElement {

    void process(ForwardInfo fwdInfo)
         throws StateAccessException, KeeperException;
    void addPort(UUID portId)
         throws ZkStateSerializationException, StateAccessException,
                KeeperException, InterruptedException, JMException;
    void removePort(UUID portId) throws StateAccessException;
    UUID getId();
    void freeFlowResources(OFMatch match, UUID inPortId);
    void destroy();

    public enum Action {
        DROP,
        NOT_IPV4,
        FORWARD,
        CONSUMED,
        PAUSED,
    }

    // For use by Bridge and Router ForwardingElements.
    public interface PacketContext {
        boolean isGeneratedPacket();
        UUID getInPortId();
        Ethernet getPktIn();
        MidoMatch getMatchIn();   // The return value should not be modified.
        MidoMatch getMatchOut();  // The return value may be modified.
        void addRemovalNotification(UUID deviceId);
        void setAction(Action action);
        void setOutPortId(UUID outPortId);
    }

    // For use by VRNCoordinator.
    public interface CoordinatorPacketContext extends PacketContext {
        void setInPortId(UUID inPortId);
        void setMatchIn(MidoMatch match);
        void setMatchOut(MidoMatch match);
        void setDepth(int depth);
        void addTraversedFE(UUID deviceId);
        int getTimesTraversed(UUID deviceId);
        int getNumFEsTraversed();
        Collection<UUID> getNotifiedFEs();
        int getDepth();
        Action getAction();
        UUID getOutPortId();
    }

    /* VRNController creates and partially populate an instance of
     * ForwardInfo to call ForwardingElement.process(fInfo).  The
     * ForwardingElement populates a number of fields to indicate various
     * decisions:  the next action for the packet, the egress port,
     * the packet at egress (i.e. after possible modifications).
     */
    public static class ForwardInfo implements CoordinatorPacketContext {

        // These fields are filled by the caller of ForwardingElement.process():
        public UUID inPortId;
        public Ethernet pktIn;
        public MidoMatch flowMatch; // (original) match of any eventual flows
        public MidoMatch matchIn; // the match as it enters the ForwardingElement
        public Set<UUID> portGroups = new HashSet<UUID>();
        public boolean internallyGenerated = false;

        // These fields are filled by ForwardingElement.process():
        public Action action;
        public UUID outPortId;
        public MidoMatch matchOut; // the match as it exits the ForwardingElement
        // Used by FEs that want notification when the flow is removed.
        private Collection<UUID> notifyFEs = new HashSet<UUID>();
        // Used by the VRNCoordinator to detect loops.
        private Map<UUID, Integer> traversedFEs = new HashMap<UUID, Integer>();
        public int depth = 0;  // depth in the VRN simulation

        public ForwardInfo() {}
        public ForwardInfo(boolean internallyGenerated) {
            this.internallyGenerated = internallyGenerated;
        }

        @Override
        public String toString() {
            return "ForwardInfo [inPortId=" + inPortId +
                   ", pktIn=" + pktIn + ", matchIn=" + matchIn +
                   ", action=" + action + ", outPortId=" + outPortId +
                   ", matchOut=" + matchOut + ", depth=" + depth + "]";
        }

        @Override
        public Action getAction() {
            return action;
        }

        @Override
        public void setAction(Action action) {
            this.action = action;
        }

        @Override
        public int getDepth() {
            return depth;
        }

        @Override
        public void setDepth(int depth) {
            this.depth = depth;
        }

        @Override
        public void addTraversedFE(UUID deviceId) {
            Integer numTraversed = traversedFEs.get(deviceId);
            if (null == numTraversed)
                traversedFEs.put(deviceId, 1);
            else
                traversedFEs.put(deviceId, numTraversed+1);
        }

        @Override
        public int getTimesTraversed(UUID deviceId) {
            Integer numTraversed = traversedFEs.get(deviceId);
            return null == numTraversed ? 0 : numTraversed;
        }

        @Override
        public int getNumFEsTraversed() {
            return traversedFEs.size();
        }

        @Override
        public Collection<UUID> getNotifiedFEs() {
            return notifyFEs;
        }

        @Override
        public boolean isGeneratedPacket() {
            return internallyGenerated;
        }

        @Override
        public UUID getInPortId() {
            return inPortId;
        }

        @Override
        public void setInPortId(UUID inPortId) {
            this.inPortId = inPortId;
        }

        @Override
        public MidoMatch getMatchIn() {
            return matchIn;
        }

        @Override
        public void setMatchIn(MidoMatch matchIn) {
            this.matchIn = matchIn;
        }

        @Override
        public MidoMatch getMatchOut() {
            return matchOut;
        }

        @Override
        public void addRemovalNotification(UUID deviceId) {
            notifyFEs.add(deviceId);
        }

        @Override
        public void setMatchOut(MidoMatch matchOut) {
            this.matchOut = matchOut;
        }

        @Override
        public UUID getOutPortId() {
            return outPortId;
        }

        @Override
        public void setOutPortId(UUID outPortId) {
            this.outPortId = outPortId;
        }

        @Override
        public Ethernet getPktIn() {
            return pktIn;
        }
    }
}
