/*
 * @(#)ChainMgmtConfig        1.6 11/11/15
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dto.config;

import org.codehaus.jackson.annotate.JsonTypeInfo;

import com.midokura.midolman.mgmt.rest_api.core.ChainTable;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
public class ChainMgmtConfig {
    public ChainMgmtConfig() {
        super();
    }

    public ChainMgmtConfig(ChainTable table) {
        super();
        this.table = table;
    }

    public ChainTable table;
}
