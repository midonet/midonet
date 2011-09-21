/*
 * Copyright 2011 Midokura KK 
 */

package com.midokura.midolman.openflow;

import java.util.List;

import org.openflow.protocol.OFFeaturesReply;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.action.OFAction;

// This is the analogue to the openfaucet "protocol" class in Python.
public interface ControllerStub {
    public static final int UNBUFFERED_ID = 0xffffffff;
    public static final short CONTROLLER_PORT = -1; // TODO: check this.

    void setController(Controller controller);

    OFFeaturesReply getFeatures();

    void sendFlowModAdd(OFMatch match, long cookie, short idleTimeoutSecs, 
                        short hardTimoutSecs, short priority, int bufferId,
                        boolean sendFlowRemove, boolean checkOverlap,
                        boolean emergency, List<OFAction> actions);

    void sendFlowModDelete(OFMatch match, boolean strict,
                           short priority, short outPort);

    void sendPacketOut(int bufferId, short inPort, List<OFAction> actions, 
                       byte[] data);
    
    void close();

}
