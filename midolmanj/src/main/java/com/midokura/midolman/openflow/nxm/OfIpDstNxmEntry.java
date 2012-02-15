/*
 * @(#)OfIpDstNxmEntry.java        1.6 11/12/27
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.openflow.nxm;

import java.net.InetAddress;

public class OfIpDstNxmEntry extends OfIpNxmEntry {

    public OfIpDstNxmEntry(InetAddress addr, int maskLen) {
        super(addr, maskLen);
    }

    // Default constructor for class.newInstance used by NxmType.
    public OfIpDstNxmEntry() {
    }

    @Override
    public String toString() {
        return "OfIpDstNxmMatch: addr=" + address + ", maskLen=" + maskLen;
    }

    @Override
    public NxmType getNxmType() {
        return NxmType.NXM_OF_IP_DST;
    }

}
