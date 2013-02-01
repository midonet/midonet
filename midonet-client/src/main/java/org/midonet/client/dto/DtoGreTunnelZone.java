/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.client.dto;

public class DtoGreTunnelZone extends DtoTunnelZone {

    public DtoGreTunnelZone() {
        super();
    }

    @Override
    public String getType() {
        return TunnelZoneType.GRE;
    }
}
