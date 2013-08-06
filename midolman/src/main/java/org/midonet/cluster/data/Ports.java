/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.cluster.data;

import org.midonet.cluster.data.ports.BridgePort;
import org.midonet.cluster.data.ports.RouterPort;
import org.midonet.cluster.data.ports.LogicalVlanBridgePort;
import org.midonet.cluster.data.ports.TrunkPort;

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

    public static LogicalVlanBridgePort logicalVlanBridgePort(VlanAwareBridge b, Short vlanId) {
        return new LogicalVlanBridgePort().setDeviceId(b.getId())
                                          .setVlanId(vlanId);

    }

    public static TrunkPort materializedVlanBridgePort(
        VlanAwareBridge bridge) {
        return new TrunkPort().setDeviceId(bridge.getId());
    }

}
