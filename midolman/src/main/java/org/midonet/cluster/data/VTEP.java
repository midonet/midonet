/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data;

import java.util.Objects;
import java.util.UUID;

import org.midonet.packets.IPv4Addr;

public class VTEP extends Entity.Base<IPv4Addr, VTEP.Data, VTEP> {

    public VTEP() {
        super(null, new Data());
    }

    @Override
    protected VTEP self() {
        return this;
    }

    public int getMgmtPort() {
        return getData().mgmtPort;
    }

    public UUID getTunnelZoneId() {
        return getData().tunnelZoneId;
    }

    public VTEP setMgmtPort(int mgmtPort) {
        getData().mgmtPort = mgmtPort;
        return self();
    }

    public VTEP setTunnelZone(UUID tunnelZone) {
        getData().tunnelZoneId = tunnelZone;
        return self();
    }

    public static class Data {
        public int mgmtPort;
        public UUID tunnelZoneId;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Data data = (Data) o;
            return Objects.equals(mgmtPort, data.mgmtPort) &&
                   Objects.equals(tunnelZoneId, data.tunnelZoneId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mgmtPort, tunnelZoneId);
        }

        public String toString() {
            return "VTEP.Data{mgmtPort=" + mgmtPort +
                   ", tunnelZoneId=" + tunnelZoneId + "}";
        }
    }
}
