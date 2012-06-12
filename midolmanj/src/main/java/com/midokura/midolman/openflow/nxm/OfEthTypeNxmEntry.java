/*
 * @(#)OfEthTypeNxmEntry.java        1.6 11/12/27
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.openflow.nxm;

import org.openflow.util.U16;

public class OfEthTypeNxmEntry extends ShortNomaskNxmEntry {

    public OfEthTypeNxmEntry(short ethType) {
        super(ethType);
    }

    // Default constructor for class.newInstance used by NxmType.
    public OfEthTypeNxmEntry() {
    }

    @Override
    public NxmType getNxmType() {
        return NxmType.NXM_OF_ETH_TYPE;
    }

    @Override
    public String toString() {
        return "OfEthTypeNxmEntry: type=0x" + Integer.toHexString(U16.f(value));
    }
}
