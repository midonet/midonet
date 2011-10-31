package com.midokura.midolman.mgmt.data.dto.config;

import java.util.UUID;

import org.codehaus.jackson.annotate.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
public class VifConfig {
    public VifConfig() {
        super();
    }

    public VifConfig(UUID portId) {
        super();
        this.portId = portId;
    }

    public UUID portId;
}
