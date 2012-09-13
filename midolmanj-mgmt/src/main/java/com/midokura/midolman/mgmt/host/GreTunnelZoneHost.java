/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.host;

import com.midokura.packets.IntIPv4;
import com.midokura.util.StringUtil;

import javax.validation.constraints.Pattern;
import java.util.UUID;

/**
 * GRE tunnel zone host mapping
 */
public class GreTunnelZoneHost extends TunnelZoneHost {

    @Pattern(regexp = StringUtil.IP_ADDRESS_REGEX_PATTERN,
            message = "is an invalid IP format")
    private String ipAddress;

    public GreTunnelZoneHost() {
        super();
    }

    public GreTunnelZoneHost(UUID tunnelZoneId,
            com.midokura.midonet.cluster.data.zones.GreTunnelZoneHost data) {
        super(tunnelZoneId, data);
        this.ipAddress = data.getIp().toString();
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    @Override
    public com.midokura.midonet.cluster.data.TunnelZone.HostConfig toData() {
        com.midokura.midonet.cluster.data.zones.GreTunnelZoneHost data =
                new com.midokura.midonet.cluster.data.zones
                        .GreTunnelZoneHost();
        data.setIp(IntIPv4.fromString(ipAddress));
        setData(data);
        return data;
    }
}
