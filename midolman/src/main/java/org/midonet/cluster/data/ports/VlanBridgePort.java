/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.cluster.data.ports;

import java.util.UUID;

import org.midonet.cluster.data.Port;

/**
 * Basic abstraction for a Bridge Port.
 */
public abstract class VlanBridgePort<
    PortData extends Port.Data,
    Self extends VlanBridgePort<PortData, Self>
    > extends Port<PortData, Self> {

    protected VlanBridgePort(UUID bridgeId, UUID uuid, PortData portData){
        super(uuid, portData);
        if (getData() != null && bridgeId != null)
            setDeviceId(bridgeId);
    }
}
