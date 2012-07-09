/*
 * Copyright 2012 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.dto;

import java.util.UUID;

import com.midokura.midolman.state.PortConfig;
import com.midokura.midolman.state.PortDirectory.LogicalBridgePortConfig;
import com.midokura.midolman.state.PortDirectory.LogicalRouterPortConfig;
import com.midokura.midolman.state.PortDirectory.MaterializedBridgePortConfig;
import com.midokura.midolman.state.PortDirectory.MaterializedRouterPortConfig;

public class PortFactory {

    public static Port createPort(UUID id, PortConfig config) {

        if (config instanceof LogicalRouterPortConfig) {
            return new LogicalRouterPort(id, (LogicalRouterPortConfig) config);
        } else if (config instanceof LogicalBridgePortConfig) {
            return new LogicalBridgePort(id, (LogicalBridgePortConfig) config);
        } else if (config instanceof MaterializedRouterPortConfig) {
            return new MaterializedRouterPort(id,
                    (MaterializedRouterPortConfig) config);
        } else if (config instanceof MaterializedBridgePortConfig) {
            return new MaterializedBridgePort(id,
                    (MaterializedBridgePortConfig) config);
        } else {
            throw new UnsupportedOperationException(
                    "Cannot instantiate this port type.");
        }
    }
}
