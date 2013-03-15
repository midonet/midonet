/*
 * Copyright 2011 Midokura KK
 */

package org.midonet.midolman.layer4;

import org.midonet.packets.IPAddr;


public class PacketSignature {

    public byte protocol;
    public IPAddr nwSrc;
    public int tpSrc;
    public IPAddr nwDst;
    public int tpDst;

    public PacketSignature(byte protocol,
                           IPAddr nwSrc, int tpSrc, IPAddr nwDst, int tpDst) {
        super();
        this.protocol = protocol;
        this.nwSrc = nwSrc;
        this.tpSrc = tpSrc;
        this.nwDst = nwDst;
        this.tpDst = tpDst;
    }

    @Override
    public int hashCode() {
        return nwSrc.hashCode()*31 + tpSrc*17 + nwDst.hashCode()*23 + tpDst*29 +
               protocol*19;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;
        if (!(other instanceof PacketSignature))
            return false;
        PacketSignature p = (PacketSignature) other;
        return nwSrc.equals(p.nwSrc) && tpSrc == p.tpSrc &&
               nwDst.equals(p.nwDst) && tpDst == p.tpDst &&
               protocol == p.protocol;
    }

    @Override
    public String toString() {
        return "PacketSignature [nwSrc=" + nwSrc.toString() + ", tpSrc=" + tpSrc +
               ", nwDst=" + nwDst.toString() + ", tpDst=" + tpDst + ", protocol=" +
        protocol +"]";
    }
}
