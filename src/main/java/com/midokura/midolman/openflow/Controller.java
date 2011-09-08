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

    void onPacketIn(int bufferId, int totalLen, short inPort, byte[] data);

    void onFlowRemoved(OFMatch match, long cookie, short priority,
            OFFlowRemovedReason reason, int durationSeconds,
            int durationNanoseconds, short idleTimeout, long packetCount,
            long byteCount);

    void onPortStatus(OFPhysicalPort port, OFPortReason status);

    void onMessage(OFMessage m);
}
