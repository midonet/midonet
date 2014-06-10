/*
 * Copyright 2012 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.host;

public class TunnelZoneFactory {

    public static TunnelZone createTunnelZone(
            org.midonet.cluster.data.TunnelZone<?, ?> data) {

        if (data instanceof
                org.midonet.cluster.data.zones.GreTunnelZone) {
            return new GreTunnelZone(
                    (org.midonet.cluster.data.zones.GreTunnelZone)
                            data);
        } else {
            throw new UnsupportedOperationException(
                    "Cannot instantiate this tunnel type.");
        }
    }
}
