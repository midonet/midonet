/*
 * @(#)OfIcmpTypeNxmEntry.java        1.6 11/12/27
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.openflow.nxm;

import java.nio.ByteBuffer;

public class OfIcmpTypeNxmEntry extends ByteNomaskNxmEntry {

    public OfIcmpTypeNxmEntry(byte type) {
        super(type);
    }

    // Default constructor for class.newInstance used by NxmType.
    public OfIcmpTypeNxmEntry() {
    }

    @Override
    public NxmType getNxmType() {
        return NxmType.NXM_OF_ICMP_TYPE;
    }

    @Override
    public String toString() {
        return "OfIcmpTypeNxMatch: type=" + value;
    }
}
