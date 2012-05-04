/*
 * @(#)ChainMgmtConfig        1.6 11/11/15
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dto.config;

import org.codehaus.jackson.annotate.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
public class ChainMgmtConfig {
    public ChainMgmtConfig() {
        super();
    }

    public ChainMgmtConfig(String tenantId, String name) {
        super();
        this.tenantId = tenantId;
        this.name = name;
    }

    public String tenantId;
    public String name;

}
