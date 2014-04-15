/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data;

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

    public VTEP setMgmtPort(int mgmtPort) {
        getData().mgmtPort = mgmtPort;
        return self();
    }

    public static class Data {
        public int mgmtPort;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Data data = (Data) o;

            return mgmtPort == data.mgmtPort;
        }

        @Override
        public int hashCode() {
            return mgmtPort;
        }

        public String toString() {
            return "VTEP.Data{mgmtPort=" + mgmtPort +"}";
        }
    }
}
