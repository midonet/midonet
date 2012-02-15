/*
 * @(#)OfArpOpNxmEntry.java        1.6 11/12/27
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.openflow.nxm;

import java.nio.ByteBuffer;

public class OfArpOpNxmEntry extends ShortNomaskNxmEntry {

    public OfArpOpNxmEntry(short op) {
        super(op);
    }

    // Default constructor for class.newInstance used by NxmType.
    public OfArpOpNxmEntry() {
    }

    @Override
    public NxmType getNxmType() {
        return NxmType.NXM_OF_ARP_OP;
    }

    @Override
    public String toString() {
        return "OfArpOpNxMatch: code=" + value;
    }
}
