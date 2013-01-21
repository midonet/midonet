/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midonet.cluster.data.ports;

import java.util.UUID;

import com.midokura.midonet.cluster.data.Bridge;
import com.midokura.midonet.cluster.data.Port;

/**
 * Basic abstraction for a Bridge Port.
 */
public abstract class BridgePort<
    PortData extends Port.Data,
    Self extends BridgePort<PortData, Self>
    > extends Port<PortData, Self> {

    protected BridgePort(UUID bridgeId, UUID uuid, PortData portData){
        super(uuid, portData);
        if (getData() != null && bridgeId != null)
            setDeviceId(bridgeId);
    }
}
