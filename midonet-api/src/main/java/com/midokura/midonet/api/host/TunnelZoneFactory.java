/*
 * Copyright 2012 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midonet.api.host;

public class TunnelZoneFactory {

    public static TunnelZone createTunnelZone(
            com.midokura.midonet.cluster.data.TunnelZone data) {

        if (data instanceof
                com.midokura.midonet.cluster.data.zones.CapwapTunnelZone) {
            return new CapwapTunnelZone(
                    (com.midokura.midonet.cluster.data.zones.CapwapTunnelZone)
                            data);
        } else if (data instanceof
                com.midokura.midonet.cluster.data.zones.GreTunnelZone) {
            return new GreTunnelZone(
                    (com.midokura.midonet.cluster.data.zones.GreTunnelZone)
                            data);
        } else if (data instanceof
                com.midokura.midonet.cluster.data.zones.IpsecTunnelZone) {
            return new IpsecTunnelZone(
                    (com.midokura.midonet.cluster.data.zones.IpsecTunnelZone)
                            data);
        } else {
            throw new UnsupportedOperationException(
                    "Cannot instantiate this tunnel type.");
        }
    }
}
