/*
 * Copyright 2011 Midokura KK
 */

package org.midonet.midolman.layer4;

public class PacketSignature {

    public byte protocol;
    public int nwSrc;
    public int tpSrc;
    public int nwDst;
    public int tpDst;

    public PacketSignature(byte protocol,
                           int nwSrc, int tpSrc, int nwDst, int tpDst) {
        super();
        this.protocol = protocol;
        this.nwSrc = nwSrc;
        this.tpSrc = tpSrc;
        this.nwDst = nwDst;
        this.tpDst = tpDst;
    }

    @Override
    public int hashCode() {
        return nwSrc*31 + tpSrc*17 + nwDst*23 + tpDst*29 + protocol*19;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;
        if (!(other instanceof PacketSignature))
            return false;
        PacketSignature p = (PacketSignature) other;
        return nwSrc == p.nwSrc && tpSrc == p.tpSrc && nwDst == p.nwDst &&
               tpDst == p.tpDst && protocol == p.protocol;
    }

    @Override
    public String toString() {
        return "PacketSignature [nwSrc=" + nwSrc + ", tpSrc=" + tpSrc +
               ", nwDst=" + nwDst + ", tpDst=" + tpDst + ", protocol=" +
        protocol +"]";
    }
}
