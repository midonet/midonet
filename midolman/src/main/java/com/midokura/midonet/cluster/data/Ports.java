/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midonet.cluster.data;

import com.midokura.midonet.cluster.data.ports.LogicalBridgePort;
import com.midokura.midonet.cluster.data.ports.LogicalRouterPort;
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

    public static LogicalBridgePort logicalBridgePort(Bridge bridge) {
        return
                new LogicalBridgePort()
                        .setDeviceId(bridge.getId());
    }

    public static MaterializedRouterPort materializedRouterPort(Router router) {
        return
            new MaterializedRouterPort()
                .setDeviceId(router.getId());
    }

    public static LogicalRouterPort logicalRouterPort(Router router) {
        return
            new LogicalRouterPort()
                .setDeviceId(router.getId());
    }
}
