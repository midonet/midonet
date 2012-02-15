/*
 * @(#)OfEthDstNxmEntry.java        1.6 11/12/27
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.openflow.nxm;

import com.midokura.midolman.util.Net;

public class OfEthDstNxmEntry extends OfEthNxmEntry {

    public OfEthDstNxmEntry(byte[] address) {
        super(address);
    }

    public OfEthDstNxmEntry(byte[] address, byte[] mask) {
        super(address, mask);
    }

    // Default constructor for class.newInstance used by NxmType.
    public OfEthDstNxmEntry() {
    }

    @Override
    public NxmType getNxmType() {
        return NxmType.NXM_OF_ETH_DST;
    }

    @Override
    public String toString() {
        StringBuilder ret = new StringBuilder("OFEthDstNxmMatch: address=");
        ret.append(Net.convertByteMacToString(address));
        if (null != mask)
            ret.append(" ,mask=").append(Net.convertByteMacToString(mask));
        return ret.toString();
    }
}
