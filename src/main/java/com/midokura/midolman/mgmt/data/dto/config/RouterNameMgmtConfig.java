package com.midokura.midolman.mgmt.data.dto.config;

import java.util.UUID;

import org.codehaus.jackson.annotate.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
public class RouterNameMgmtConfig {
    public RouterNameMgmtConfig() {
        super();
    }

    public RouterNameMgmtConfig(UUID id) {
        super();
        this.id = id;
    }

    public UUID id;
}
