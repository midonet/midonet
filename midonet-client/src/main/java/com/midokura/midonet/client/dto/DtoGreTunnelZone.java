/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midonet.client.dto;

public class DtoGreTunnelZone extends DtoTunnelZone {

    public DtoGreTunnelZone() {
        super();
    }

    @Override
    public String getType() {
        return TunnelZoneType.GRE;
    }
}
