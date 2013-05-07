package org.midonet.cluster.client;

import java.util.UUID;

import org.midonet.util.functors.Callback3;

public interface VlanPortMap {
    public Short getVlan(UUID portId);
    public UUID getPort(Short vlanId);
}
