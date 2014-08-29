/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.brain.southbound.vtep;

import java.util.Objects;

import org.midonet.packets.IPv4Addr;

public class VtepEndPoint {

    public final IPv4Addr mgmtIp;
    public final int mgmtPort;

    public VtepEndPoint(IPv4Addr mgmtIp, int mgmtPort) {
        this.mgmtIp = mgmtIp;
        this.mgmtPort = mgmtPort;
    }

    public static VtepEndPoint apply(String mgmtIp, int mgmtPort) {
        return new VtepEndPoint(IPv4Addr.apply(mgmtIp), mgmtPort);
    }

    @Override
    public String toString() {
        return String.format("%s:%s", mgmtIp, mgmtPort);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (null == obj || getClass() != obj.getClass()) return false;

        VtepEndPoint ep = (VtepEndPoint) obj;
        return Objects.equals(mgmtIp, ep.mgmtIp) &&
               (mgmtPort == ep.mgmtPort);
    }

    @Override
    public int hashCode() {
        int result = mgmtIp != null ? mgmtIp.hashCode() : 0;
        result = 31 * result + mgmtPort;
        return result;
    }
}
