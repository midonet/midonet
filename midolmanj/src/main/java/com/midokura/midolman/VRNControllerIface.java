// Copyright 2012 Midokura Inc.

package com.midokura.midolman;

import java.util.UUID;

import com.midokura.midolman.ForwardingElement.ForwardInfo;
import com.midokura.midolman.packets.Ethernet;
import com.midokura.midolman.state.StateAccessException;


public interface VRNControllerIface {

    void continueProcessing(final ForwardInfo fwdInfo);

    void addGeneratedPacket(Ethernet pkt, UUID originPort);

    void subscribePortSet(UUID portSetID) throws StateAccessException;
    void unsubscribePortSet(UUID portSetID) throws StateAccessException;
    void addLocalPortToSet(UUID portSetID, UUID portID);
    void removeLocalPortFromSet(UUID portSetID, UUID portID);

    void invalidateFlowsByElement(UUID id);
}
