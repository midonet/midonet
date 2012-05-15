/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test.topology;

import java.util.Collection;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.dto.client.DtoBridge;
import com.midokura.midolman.mgmt.data.dto.client.DtoPort;
import com.midokura.midolman.mgmt.data.dto.client.DtoTenant;
import com.midokura.midonet.functional_test.mocks.MidolmanMgmt;

public class BridgePort {
    public static class Builder {
        MidolmanMgmt mgmt;
        DtoBridge bridge;
        DtoPort port;

        public Builder(MidolmanMgmt mgmt, DtoBridge bridge) {
            this.mgmt = mgmt;
            this.bridge = bridge;
            this.port = new DtoPort();
        }

        public Builder setInboundFilter(UUID chainId) {
            port.setInboundFilter(chainId);
            return this;
        }

        public Builder setOutboundFilter(UUID chainId) {
            port.setOutboundFilter(chainId);
            return this;
        }

        public Builder setPortGroups(UUID[] groupIDs) {
            port.setPortGroupIDs(groupIDs);
            return this;
        }

        public BridgePort build() {
            return new BridgePort(mgmt, mgmt.addBridgePort(bridge, port));
        }
    }

    private MidolmanMgmt mgmt;
    private DtoPort port;

    public BridgePort(MidolmanMgmt mgmt, DtoPort port) {
        this.mgmt = mgmt;
        this.port = port;
    }

    public UUID getId()
    {
        return port.getId();
    }

    public void delete() {
        mgmt.delete(port.getUri());
    }
}
