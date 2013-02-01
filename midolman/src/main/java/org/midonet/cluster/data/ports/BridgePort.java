/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.cluster.data.ports;

import java.util.UUID;

import org.midonet.cluster.data.Bridge;
import org.midonet.cluster.data.Port;

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
