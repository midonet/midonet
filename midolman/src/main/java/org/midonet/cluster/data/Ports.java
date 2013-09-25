/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.cluster.data;

import org.midonet.cluster.data.ports.BridgePort;
import org.midonet.cluster.data.ports.RouterPort;

/**
 * Factory class for creating / converting {@link Port} instances
 */
public class Ports {

    public static BridgePort bridgePort(Bridge bridge) {
        return new BridgePort().setDeviceId(bridge.getId());
    }

    public static BridgePort bridgePort(Bridge bridge, Short vlanId) {
        return new BridgePort()
                   .setDeviceId(bridge.getId())
                   .setVlanId(vlanId);
    }

    public static RouterPort routerPort(Router router) {
        return new RouterPort().setDeviceId(router.getId());
    }

}
