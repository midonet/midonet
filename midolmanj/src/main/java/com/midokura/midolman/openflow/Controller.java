/*
 * Copyright 2011 Midokura KK 
 */

package com.midokura.midolman.openflow;

import org.openflow.protocol.OFFlowRemoved.OFFlowRemovedReason;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFMessage;
import org.openflow.protocol.OFPhysicalPort;
import org.openflow.protocol.OFPortStatus.OFPortReason;

public interface Controller {

    void setControllerStub(ControllerStub controllerStub);

    void onConnectionMade();

    void onConnectionLost();

    /**
     *
     * @param bufferId
     * @param totalLen
     * @param inPort
     * @param data
     * @param matchingTunnelId
     *      The ID of the tunnel on which the packet arrived. This is 0
     *      if the packet did not arrive on a tunnel or if you have not called
     *      enableNxm() on the ControllerStub instance.
     */
    void onPacketIn(int bufferId, int totalLen, short inPort, byte[] data,
            long matchingTunnelId);

    void onPacketIn(int bufferId, int totalLen, short inPort, byte[] data);

    /**
     *
     * @param match
     * @param cookie
     * @param priority
     * @param reason
     * @param durationSeconds
     * @param durationNanoseconds
     * @param idleTimeout
     * @param packetCount
     * @param byteCount
     * @param matchingTunnelId
     *      Additional meta-data that was matched by the removed flow: the ID
     *      of the tunnel on which the packet arrived. This will be 0 if the
     *      flow did not match on tunnel Id or if you have not called
     *      enableNxm() on the ControllerStub instance.
     */
    void onFlowRemoved(OFMatch match, long cookie, short priority,
            OFFlowRemovedReason reason, int durationSeconds,
            int durationNanoseconds, short idleTimeout, long packetCount,
            long byteCount, long matchingTunnelId);

    void onFlowRemoved(OFMatch match, long cookie, short priority,
            OFFlowRemovedReason reason, int durationSeconds,
            int durationNanoseconds, short idleTimeout, long packetCount,
            long byteCount);

    void onPortStatus(OFPhysicalPort port, OFPortReason status);

    void onMessage(OFMessage m);
}
