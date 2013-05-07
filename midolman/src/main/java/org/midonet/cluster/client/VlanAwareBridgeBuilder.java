/*
* Copyright 2012 Midokura Europe SARL
*/

package org.midonet.cluster.client;

import java.util.Set;
import java.util.UUID;

public interface VlanAwareBridgeBuilder extends ForwardingElementBuilder {
    void setTunnelKey(long key);
    void setVlanPortMap(VlanPortMap vlanPortMap);
    void setTrunks(Set<UUID> portId);
}