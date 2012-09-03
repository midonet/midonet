/*
 * Copyright 2012 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.network;

public class PortFactory {

    public static Port createPort(com.midokura.midonet.cluster.data.Port data) {

        if (data instanceof
                com.midokura.midonet.cluster.data.ports.LogicalRouterPort) {
            return new LogicalRouterPort(
                    (com.midokura.midonet.cluster.data.ports.LogicalRouterPort)
                            data);
        } else if (data instanceof
                com.midokura.midonet.cluster.data.ports.LogicalBridgePort) {
            return new LogicalBridgePort(
                    (com.midokura.midonet.cluster.data.ports.LogicalBridgePort)
                            data);
        } else if (data instanceof
                com.midokura.midonet.cluster.data.ports
                        .MaterializedRouterPort) {
            return new MaterializedRouterPort(
                    (com.midokura.midonet.cluster.data.ports
                            .MaterializedRouterPort) data);
        } else if (data instanceof
                com.midokura.midonet.cluster.data.ports
                        .MaterializedBridgePort) {
            return new MaterializedBridgePort(
                    (com.midokura.midonet.cluster.data.ports
                            .MaterializedBridgePort) data);
        } else {
            throw new UnsupportedOperationException(
                    "Cannot instantiate this port type.");
        }
    }
}
