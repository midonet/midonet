/*
 * Copyright 2012 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.network;

import org.midonet.cluster.data.ports.*;

public class PortFactory {

    public static Port createPort(org.midonet.cluster.data.Port data) {

        if (data instanceof org.midonet.cluster.data.ports.LogicalRouterPort) {
            return new InteriorRouterPort(
                    (org.midonet.cluster.data.ports.LogicalRouterPort)data);
        } else if (data instanceof
                org.midonet.cluster.data.ports.LogicalBridgePort) {
            return new InteriorBridgePort(
                    (org.midonet.cluster.data.ports.LogicalBridgePort)data);
        } else if (data instanceof org.midonet.cluster.data.ports
                                      .MaterializedRouterPort) {
            return new ExteriorRouterPort((org.midonet.cluster.data.ports
                            .MaterializedRouterPort) data);
        } else if (data instanceof
                org.midonet.cluster.data.ports.MaterializedBridgePort) {
            return new ExteriorBridgePort((org.midonet.cluster.data.ports
                                           .MaterializedBridgePort) data);
        } else {
            throw new UnsupportedOperationException(
                    "Cannot instantiate this port type.");
        }
    }
}
