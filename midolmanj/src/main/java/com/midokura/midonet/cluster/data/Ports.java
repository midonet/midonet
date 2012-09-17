/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midonet.cluster.data;

import com.midokura.midonet.cluster.data.ports.MaterializedBridgePort;
import com.midokura.midonet.cluster.data.ports.MaterializedRouterPort;

/**
 * Factory class for creating / converting {@link Port} instances
 */
public class Ports {

    public static MaterializedBridgePort materializedBridgePort(Bridge bridge) {
        return
            new MaterializedBridgePort()
                .setDeviceId(bridge.getId());
    }

    public static MaterializedRouterPort materializedRouterPort(Router router) {
        return
            new MaterializedRouterPort()
                .setDeviceId(router.getId());
    }
}
