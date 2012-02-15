/*
 * @(#)OfIpSrcNxmEntry.java        1.6 11/12/27
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.openflow.nxm;

import java.net.InetAddress;

public class OfIpSrcNxmEntry extends OfIpNxmEntry {

    public OfIpSrcNxmEntry(InetAddress addr, int maskLen) {
        super(addr, maskLen);
    }

    // Default constructor for class.newInstance used by NxmType.
    public OfIpSrcNxmEntry() {
    }

    @Override
    public NxmType getNxmType() {
        return NxmType.NXM_OF_IP_SRC;
    }

    @Override
    public String toString() {
        return "OfIpSrcNxmMatch: addr=" + address + ", maskLen=" + maskLen;
    }
}