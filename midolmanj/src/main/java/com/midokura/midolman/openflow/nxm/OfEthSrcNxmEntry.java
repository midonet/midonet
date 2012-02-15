/*
 * @(#)OfEthSrcNxmEntry.java        1.6 11/12/27
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.openflow.nxm;

import com.midokura.midolman.util.Net;

public class OfEthSrcNxmEntry extends OfEthNxmEntry {

    public OfEthSrcNxmEntry(byte[] address) {
        super(address);
    }

    public OfEthSrcNxmEntry(byte[] address, byte[] mask) {
        super(address, mask);
    }

    // Default constructor for class.newInstance used by NxmType.
    public OfEthSrcNxmEntry() {
    }

    @Override
    public NxmType getNxmType() {
        return NxmType.NXM_OF_ETH_SRC;
    }

    @Override
    public String toString() {
        StringBuilder ret = new StringBuilder("OFEthSrcNxmMatch: address=");
        ret.append(Net.convertByteMacToString(address));
        if (null != mask)
            ret.append(" ,mask=").append(Net.convertByteMacToString(mask));
        return ret.toString();
    }
}
