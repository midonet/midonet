package com.midokura.midolman.mgmt.data.dto.config;

import org.codehaus.jackson.annotate.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
public class BridgeMgmtConfig {
    public BridgeMgmtConfig() {
        super();
    }

    public BridgeMgmtConfig(String tenantId, String name) {
        super();
        this.tenantId = tenantId;
        this.name = name;
    }

    public String tenantId;
    public String name;
}
