/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.dto.config;

import java.util.UUID;

import org.codehaus.jackson.annotate.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
public class PortMgmtConfig {
    public PortMgmtConfig() {
        super();
    }

    public PortMgmtConfig(UUID vifId) {
        super();
        this.vifId = vifId;
    }

    public UUID vifId = null;
}
