/*
 * @(#)PortMgmtConfig        1.6 11/11/15
 *
 * Copyright 2011 Midokura KK
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
