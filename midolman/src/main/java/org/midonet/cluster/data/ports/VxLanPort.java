/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.ports;

import org.midonet.cluster.data.Port;
import org.midonet.packets.IPv4Addr;

import java.util.Objects;
import java.util.UUID;

public class VxLanPort extends Port<VxLanPort.Data, VxLanPort> {

    public VxLanPort() {
        super(new Data());
    }

    public VxLanPort(UUID bridgeId, IPv4Addr mgmtIpAddr,
                     int mgmtPort, int vni) {
        super(UUID.randomUUID(), new Data());
        setDeviceId(bridgeId);
        setMgmtIpAddr(mgmtIpAddr);
        setMgmtPort(mgmtPort);
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

    public static class Data extends Port.Data {
        public IPv4Addr mgmtIpAddr;
        public int mgmtPort;
        public int vni;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            if (!super.equals(o)) return false;

            Data data = (Data) o;
            return mgmtPort == data.mgmtPort &&
                   vni != data.vni &&
                   Objects.equals(mgmtIpAddr, data.mgmtIpAddr);
        }

        @Override
        public int hashCode() {
            return vni;
        }
    }
}
