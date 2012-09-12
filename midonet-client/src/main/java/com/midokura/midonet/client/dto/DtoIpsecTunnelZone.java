/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midonet.client.dto;

public class DtoIpsecTunnelZone extends DtoTunnelZone {

    public DtoIpsecTunnelZone() {
        super();
    }

    @Override
    public String getType() {
        return TunnelZoneType.IPSEC;
    }
}
