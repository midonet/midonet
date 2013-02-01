/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.client.dto;

public class DtoCapwapTunnelZone extends DtoTunnelZone {

    public DtoCapwapTunnelZone() {
        super();
    }

    @Override
    public String getType() {
        return TunnelZoneType.CAPWAP;
    }
}
