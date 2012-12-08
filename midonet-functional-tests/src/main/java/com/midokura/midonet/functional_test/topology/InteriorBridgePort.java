/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test.topology;

import com.midokura.midonet.client.dto.DtoBridge;
import com.midokura.midonet.client.dto.DtoInteriorBridgePort;
import com.midokura.midonet.functional_test.mocks.MidolmanMgmt;

public class InteriorBridgePort {

    public static class Builder {
        private final MidolmanMgmt mgmt;
        private final DtoBridge bridge;
        private final DtoInteriorBridgePort port;

        public Builder(MidolmanMgmt mgmt, DtoBridge bridge) {
            this.mgmt = mgmt;
            this.bridge = bridge;
            port = new DtoInteriorBridgePort();
        }

        public InteriorBridgePort build() {
            DtoInteriorBridgePort p = mgmt.addInteriorBridgePort(bridge, port);
            return new InteriorBridgePort(mgmt, p);
        }
    }

    MidolmanMgmt mgmt;
    public DtoInteriorBridgePort port;

    InteriorBridgePort(MidolmanMgmt mgmt, DtoInteriorBridgePort port) {
        this.mgmt = mgmt;
        this.port = port;
    }

    public void delete() {
        mgmt.delete(port.getUri());
    }
}
