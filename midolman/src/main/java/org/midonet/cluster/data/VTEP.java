/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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

    public IPv4Addr getTunnelIp() {
        return getData().tunnelIp;
    }

    public VTEP setTunnelIp(IPv4Addr tunnelIp) {
        getData().tunnelIp = tunnelIp;
        return self();
    }

    public static class Data {
        public int mgmtPort;
        public UUID tunnelZoneId;
        public IPv4Addr tunnelIp;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Data data = (Data) o;
            return Objects.equals(mgmtPort, data.mgmtPort) &&
                   Objects.equals(tunnelZoneId, data.tunnelZoneId) &&
                   Objects.equals(tunnelIp, data.tunnelIp);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mgmtPort, tunnelZoneId, tunnelIp);
        }

        public String toString() {
            return "VTEP.Data{mgmtPort=" + mgmtPort +
                   ", tunnelZoneId=" + tunnelZoneId +
                   ", tunnelIp=" + tunnelIp + "}";
        }
    }
}
