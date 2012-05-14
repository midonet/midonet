/*
 * @(#)ChainNameMgmtConfig        1.6 11/11/15
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dto.config;

import java.util.UUID;

import org.codehaus.jackson.annotate.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
public class PortGroupNameMgmtConfig {
    public PortGroupNameMgmtConfig() {
        super();
    }

    public PortGroupNameMgmtConfig(UUID id) {
        super();
        this.id = id;
    }

    public UUID id;
}
