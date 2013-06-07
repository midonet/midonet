/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.cluster.data;

import org.midonet.cluster.data.ports.LogicalBridgePort;
import org.midonet.cluster.data.ports.LogicalRouterPort;
import org.midonet.cluster.data.ports.LogicalVlanBridgePort;
import org.midonet.cluster.data.ports.MaterializedBridgePort;
import org.midonet.cluster.data.ports.MaterializedRouterPort;
import org.midonet.cluster.data.ports.TrunkPort;

/**
 * Factory class for creating / converting {@link Port} instances
 */
public class Ports {

    public static MaterializedBridgePort materializedBridgePort(Bridge bridge) {
        return new MaterializedBridgePort().setDeviceId(bridge.getId());
    }

    public static LogicalBridgePort logicalBridgePort(Bridge bridge, Short vlanId) {
        return new LogicalBridgePort()
                   .setDeviceId(bridge.getId())
                   .setVlanId(vlanId);
    }

    public static MaterializedRouterPort materializedRouterPort(Router router) {
        return new MaterializedRouterPort().setDeviceId(router.getId());
    }

    public static LogicalRouterPort logicalRouterPort(Router router) {
        return new LogicalRouterPort().setDeviceId(router.getId());
    }

    public static LogicalVlanBridgePort logicalVlanBridgePort(VlanAwareBridge b, Short vlanId) {
        return new LogicalVlanBridgePort().setDeviceId(b.getId())
                                          .setVlanId(vlanId);

    }

    public static TrunkPort materializedVlanBridgePort(
        VlanAwareBridge bridge) {
        return new TrunkPort().setDeviceId(bridge.getId());
    }

}
