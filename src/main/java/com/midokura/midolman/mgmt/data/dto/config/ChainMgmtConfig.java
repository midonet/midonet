package com.midokura.midolman.mgmt.data.dto.config;

import org.codehaus.jackson.annotate.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
public class ChainMgmtConfig {
    public ChainMgmtConfig() {
        super();
    }

    public ChainMgmtConfig(String table) {
        super();
        this.table = table;
    }

    public String table;
}
