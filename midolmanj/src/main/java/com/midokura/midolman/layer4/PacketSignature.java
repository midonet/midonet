/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.layer4;

public class PacketSignature {

    public int nwSrc;
    public int tpSrc;
    public int nwDst;
    public int tpDst;

    public PacketSignature(int nwSrc, int tpSrc, int nwDst, int tpDst) {
        super();
        this.nwSrc = nwSrc;
        this.tpSrc = tpSrc;
        this.nwDst = nwDst;
        this.tpDst = tpDst;
    }

    @Override
    public int hashCode() {
        return nwSrc * 31 + tpSrc * 17 + nwDst * 23 + tpDst * 29;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;
        if (!(other instanceof PacketSignature))
            return false;
        PacketSignature p = (PacketSignature) other;
        return nwSrc == p.nwSrc && tpSrc == p.tpSrc && nwDst == p.nwDst
                && tpDst == p.tpDst;
    }

    @Override
    public String toString() {
        return "PacketSignature [nwSrc=" + nwSrc + ", tpSrc=" + tpSrc + ", nwDst=" + nwDst + ", tpDst=" + tpDst + "]";
    }
}
