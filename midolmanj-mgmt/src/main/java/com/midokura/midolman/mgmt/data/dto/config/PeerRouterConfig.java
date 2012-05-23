/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.dto.config;

import java.util.UUID;

import org.codehaus.jackson.annotate.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
public class PeerRouterConfig {
    public PeerRouterConfig() {
        super();
    }

    public PeerRouterConfig(UUID portId, UUID peerPortId) {
        super();
        this.portId = portId;
        this.peerPortId = peerPortId;
    }

    public UUID portId;
    public UUID peerPortId;
}
