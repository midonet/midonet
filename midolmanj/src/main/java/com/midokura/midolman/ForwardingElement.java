/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midolman;

import java.util.Collection;
import java.util.UUID;

import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.packets.Ethernet;
import com.midokura.midolman.state.StateAccessException;


public interface ForwardingElement {

    void process(ForwardInfo fwdInfo) throws StateAccessException;
    void addPort(UUID portId);
    void removePort(UUID portId);

    public enum Action {
        BLACKHOLE, NOT_IPV4, NO_ROUTE, FORWARD, REJECT, CONSUMED, PAUSED;
    }

    /* ForwardingElements create and partially populate an instance of
     * ForwardInfo to call ForwardingElement.process(fInfo).  The
     * ForwardingElement populates a number of fields to indicate various
     * decisions:  the next action for the packet, the next hop gateway
     * address, the egress port, the packet at egress (i.e. after possible
     * modifications).
     */
    public static class ForwardInfo {
        // These fields are filled by the caller of ForwardingElement.process():
        public UUID inPortId;
        public Ethernet pktIn;
        public MidoMatch flowMatch; // (original) match of any eventual flows
        public MidoMatch matchIn; // the match as it enters the ForwardingElement

        // These fields are filled by ForwardingElement.process():
        public Action action;
        public UUID outPortId;
        public int nextHopNwAddr;
        public MidoMatch matchOut; // the match as it exits the ForwardingElement
        public boolean trackConnection;
        // Used by forwarding elements that want notification when the flow
        // is removed.
        public Collection<UUID> notifyFEs;

        public ForwardInfo() {
        }

        @Override
        public String toString() {
            return "ForwardInfo [inPortId=" + inPortId +
                   ", pktIn=" + pktIn + ", matchIn=" + matchIn +
                   ", action=" + action + ", outPortId=" + outPortId +
                   ", nextHopNwAddr=" + nextHopNwAddr + ", matchOut="
                    + matchOut + ", trackConnection=" + trackConnection + "]";
        }
    }

}
