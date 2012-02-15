/*
 * @(#)OfArpTargetPacketNxmEntry.java        1.6 11/12/27
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.openflow.nxm;

import java.net.InetAddress;

public class OfArpTargetPacketNxmEntry extends OfIpNxmEntry{

    public OfArpTargetPacketNxmEntry(InetAddress addr, int maskLen) {
        super(addr, maskLen);
    }

    // Default constructor for class.newInstance used by NxmType.
    public OfArpTargetPacketNxmEntry() {
    }

    @Override
    public NxmType getNxmType() {
        return NxmType.NXM_OF_ARP_TPA;
    }

    @Override
    public String toString() {
        return "OfArpTargetPacketNxMatch: addr=" + address + ", maskLen=" +
                maskLen;
    }
}