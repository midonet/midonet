/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test.topology;

import com.midokura.midonet.client.dto.DtoBridge;
import com.midokura.midonet.client.dto.DtoLogicalBridgePort;
import com.midokura.midonet.functional_test.mocks.MidolmanMgmt;

public class LogicalBridgePort {

    public static class Builder {
        private final MidolmanMgmt mgmt;
        private final DtoBridge bridge;
        private final DtoLogicalBridgePort port;

        public Builder(MidolmanMgmt mgmt, DtoBridge bridge) {
            this.mgmt = mgmt;
            this.bridge = bridge;
            port = new DtoLogicalBridgePort();
        }

        public LogicalBridgePort build() {
            DtoLogicalBridgePort p = mgmt.addLogicalBridgePort(bridge, port);
            return new LogicalBridgePort(mgmt, p);
        }
    }

    MidolmanMgmt mgmt;
    public DtoLogicalBridgePort port;

    LogicalBridgePort(MidolmanMgmt mgmt, DtoLogicalBridgePort port) {
        this.mgmt = mgmt;
        this.port = port;
    }

    public void delete() {
        mgmt.delete(port.getUri());
    }
}
