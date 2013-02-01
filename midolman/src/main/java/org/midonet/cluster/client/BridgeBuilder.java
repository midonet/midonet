/*
 * Copyright 2012 Midokura Europe SARL
 */

package org.midonet.cluster.client;

import java.util.Map;
import java.util.UUID;

import org.midonet.packets.IntIPv4;
import org.midonet.packets.MAC;

public interface BridgeBuilder extends ForwardingElementBuilder {
    void setTunnelKey(long key);
    void setMacLearningTable(MacLearningTable table);
    void setLogicalPortsMap(Map<MAC, UUID> rtrMacToLogicalPortId,
                            Map<IntIPv4, MAC> rtrIpToMac);
}
