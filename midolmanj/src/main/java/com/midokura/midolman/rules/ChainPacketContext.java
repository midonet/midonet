// Copyright 2012 Midokura Inc.

package com.midokura.midolman.rules;

import java.util.Set;
import java.util.UUID;

public interface ChainPacketContext {
    UUID getInPortId();
    UUID getOutPortId();
    Set<UUID> getPortGroups();
    void addTraversedElementID(UUID id);
    boolean isConnTracked();
    boolean isForwardFlow();
    Object getFlowCookie();
}
