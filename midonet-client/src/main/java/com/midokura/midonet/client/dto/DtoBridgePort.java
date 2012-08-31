/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.client.dto;

public class DtoBridgePort extends DtoPort {

    @Override
    public String getType() {
        return PortType.MATERIALIZED_BRIDGE;
    }
}
