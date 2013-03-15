/*
 * Copyright 2011 Midokura KK
 */

package org.midonet.midolman.layer4;

import org.midonet.packets.IPAddr;


public class NwTpPair {

    public final IPAddr nwAddr;
    public final int tpPort;
    public final String unrefKey;

    public NwTpPair(IPAddr nwAddr, int tpPort) {
        this.nwAddr = nwAddr;
        this.tpPort = tpPort;
        this.unrefKey = null;
    }

    public NwTpPair(IPAddr nwAddr, int tpPort, String unrefKey) {
        this.nwAddr = nwAddr;
        this.tpPort = tpPort;
        this.unrefKey = unrefKey;
    }

    @Override
    public int hashCode() {
        return nwAddr.hashCode() * 31 + tpPort * 17;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;
        if (!(other instanceof NwTpPair))
            return false;
        NwTpPair p = (NwTpPair) other;
        return nwAddr.equals(p.nwAddr) && tpPort == p.tpPort;
    }

    @Override
    public String toString() {
        return "NwTpPair [nwAddr=" + nwAddr.toString() + ", tpPort=" + tpPort + "]";
    }
}
