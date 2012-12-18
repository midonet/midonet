/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test.topology;

import java.util.UUID;

import com.midokura.midonet.client.dto.DtoBridge;
import com.midokura.midonet.client.dto.DtoBridgePort;
import com.midokura.midonet.client.dto.DtoTenant;
import com.midokura.midonet.functional_test.mocks.MidolmanMgmt;

public class BridgePort {
    public static class Builder {
        MidolmanMgmt mgmt;
        DtoBridge bridge;
        DtoBridgePort port;

        public Builder(MidolmanMgmt mgmt, DtoBridge bridge) {
            this.mgmt = mgmt;
            this.bridge = bridge;
            this.port = new DtoBridgePort();
        }

        public Builder setInboundFilter(UUID chainId) {
            port.setInboundFilterId(chainId);
            return this;
        }

        public Builder setOutboundFilter(UUID chainId) {
            port.setOutboundFilterId(chainId);
            return this;
        }

        public BridgePort build() {
            return new BridgePort(mgmt,
                    mgmt.addExteriorBridgePort(bridge, port));
        }
    }

    private MidolmanMgmt mgmt;
    private DtoBridgePort port;

    public BridgePort(MidolmanMgmt mgmt, DtoBridgePort port) {
        this.mgmt = mgmt;
        this.port = port;
    }

    public UUID getId()
    {
        return port.getId();
    }

    public void removeFilters() {
        port.setInboundFilterId(null);
        port.setOutboundFilterId(null);
        mgmt.updatePort(port);
    }

    public RuleChain addOutboundFilter(String name, DtoTenant tenant) {
        RuleChain filter = (new RuleChain.Builder(mgmt, tenant))
                .setName(name).build();
        // Set this chain as the port's outbound filter.
        port.setOutboundFilterId(filter.chain.getId());
        mgmt.updatePort(port);
        return filter;
    }

    public void delete() {
        mgmt.delete(port.getUri());
    }
}
