/*
 * @(#)OfArpSrcPacketNxmEntry.java        1.6 11/12/27
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.openflow.nxm;

import java.net.InetAddress;

public class OfArpSrcPacketNxmEntry extends OfIpNxmEntry {

    public OfArpSrcPacketNxmEntry(InetAddress addr, int maskLen) {
        super(addr, maskLen);
    }

    // Default constructor for class.newInstance used by NxmType.
    public OfArpSrcPacketNxmEntry() {
    }

    @Override
    public NxmType getNxmType() {
        return NxmType.NXM_OF_ARP_SPA;
    }

    @Override
    public String toString() {
        return "OfArpSrcPacketNxMatch: addr=" + address + ", maskLen="
                + maskLen;
    }
}