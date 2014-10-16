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
package org.midonet.cluster.data.ports;

import java.util.Objects;
import java.util.UUID;

import org.midonet.cluster.data.Port;
import org.midonet.packets.IPv4Addr;

public class VxLanPort extends Port<VxLanPort.Data, VxLanPort> {

    public VxLanPort() {
        super(new Data());
    }

    public VxLanPort(UUID bridgeId, IPv4Addr mgmtIpAddr, int mgmtPort, int vni,
                     IPv4Addr tunnelIp, UUID tunnelZoneId) {
        super(UUID.randomUUID(), new Data());
        setDeviceId(bridgeId);
        setMgmtIpAddr(mgmtIpAddr);
        setMgmtPort(mgmtPort);
        setTunnelIp(tunnelIp);
        setTunnelZoneId(tunnelZoneId);
        setVni(vni);
    }

    @Override
    protected VxLanPort self() {
        return this;
    }

    public IPv4Addr getMgmtIpAddr() {
        return getData().mgmtIpAddr;
    }

    public VxLanPort setMgmtIpAddr(IPv4Addr mgmtIpAddr) {
        getData().mgmtIpAddr = mgmtIpAddr;
        return self();
    }

    public int getMgmtPort() {
        return getData().mgmtPort;
    }

    public VxLanPort setMgmtPort(int mgmtPort) {
        getData().mgmtPort = mgmtPort;
        return self();
    }

    public int getVni() {
        return getData().vni;
    }

    public VxLanPort setVni(int vni) {
        getData().vni = vni;
        return self();
    }

    public IPv4Addr getTunnelIp() { return getData().tunnelIp; }
    public VxLanPort setTunnelIp(IPv4Addr tunnelIp) {
        getData().tunnelIp = tunnelIp;
        return self();
    }

    public UUID getTunnelZoneId() { return getData().tunnelZoneId; }
    public VxLanPort setTunnelZoneId(UUID tunnelZoneId) {
        getData().tunnelZoneId = tunnelZoneId;
        return self();
    }

    public static class Data extends Port.Data {
        public IPv4Addr mgmtIpAddr;
        public int mgmtPort;
        public IPv4Addr tunnelIp;
        public int vni;
        public UUID tunnelZoneId;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            if (!super.equals(o)) return false;

            Data data = (Data) o;
            return mgmtPort == data.mgmtPort &&
                   vni != data.vni &&
                   Objects.equals(mgmtIpAddr, data.mgmtIpAddr) &&
                   Objects.equals(tunnelIp, data.tunnelIp) &&
                   Objects.equals(tunnelZoneId, data.tunnelZoneId);
        }

        // FIXME: is vni enough as hash code??
        @Override
        public int hashCode() {
            return vni;
        }
    }
}
