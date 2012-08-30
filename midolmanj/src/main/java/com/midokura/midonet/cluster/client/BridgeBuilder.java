/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midonet.cluster.client;

import java.util.Map;
import java.util.UUID;

import com.midokura.packets.IntIPv4;
import com.midokura.packets.MAC;

public interface BridgeBuilder extends ForwardingElementBuilder {
    void setTunnelKey(long key);
    void setMacLearningTable(MacLearningTable table);
    void setLogicalPortsMap(Map<MAC, UUID> rtrMacToLogicalPortId,
                            Map<IntIPv4, MAC> rtrIpToMac);
}
