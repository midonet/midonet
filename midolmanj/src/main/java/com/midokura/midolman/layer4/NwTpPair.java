/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.layer4;

public class NwTpPair {

    public final int nwAddr;
    public final short tpPort;
    public final String unrefKey;

    public NwTpPair(int nwAddr, short tpPort) {
        this.nwAddr = nwAddr;
        this.tpPort = tpPort;
        this.unrefKey = null;
    }

    public NwTpPair(int nwAddr, short tpPort, String unrefKey) {
        this.nwAddr = nwAddr;
        this.tpPort = tpPort;
        this.unrefKey = unrefKey;
    }

    @Override
    public int hashCode() {
        return nwAddr * 31 + tpPort * 17;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;
        if (!(other instanceof NwTpPair))
            return false;
        NwTpPair p = (NwTpPair) other;
        return nwAddr == p.nwAddr && tpPort == p.tpPort;
    }

    @Override
    public String toString() {
        return "NwTpPair [nwAddr=" + nwAddr + ", tpPort=" + tpPort + "]";
    }
}
