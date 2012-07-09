/*
 * Copyright 2012 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.dto;

import java.util.UUID;

import com.midokura.midolman.mgmt.data.dto.config.PortMgmtConfig;
import com.midokura.midolman.state.PortConfig;
import com.midokura.midolman.state.PortDirectory.LogicalBridgePortConfig;
import com.midokura.midolman.state.PortDirectory.LogicalRouterPortConfig;
import com.midokura.midolman.state.PortDirectory.MaterializedBridgePortConfig;
import com.midokura.midolman.state.PortDirectory.MaterializedRouterPortConfig;

public class PortFactory {

    public static Port createPort(UUID id, PortConfig config,
            PortMgmtConfig mgmtConfig) {

        if (config instanceof LogicalRouterPortConfig) {
            return new LogicalRouterPort(id, (LogicalRouterPortConfig) config,
                    (PortMgmtConfig) mgmtConfig);
        } else if (config instanceof LogicalBridgePortConfig) {
            return new LogicalBridgePort(id, (LogicalBridgePortConfig) config,
                    (PortMgmtConfig) mgmtConfig);
        } else if (config instanceof MaterializedRouterPortConfig) {
            return new MaterializedRouterPort(id,
                    (MaterializedRouterPortConfig) config, mgmtConfig);
        } else if (config instanceof MaterializedBridgePortConfig) {
            return new MaterializedBridgePort(id,
                    (MaterializedBridgePortConfig) config, mgmtConfig);
        } else {
            throw new UnsupportedOperationException(
                    "Cannot instantiate this port type.");
        }
    }
}
