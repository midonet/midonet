/*
 * @(#)OfIcmpCodeNxmEntry.java        1.6 11/12/27
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.openflow.nxm;

import java.nio.ByteBuffer;

public class OfIcmpCodeNxmEntry extends ByteNomaskNxmEntry {

    public OfIcmpCodeNxmEntry(byte code) {
        super(code);
    }

    // Default constructor for class.newInstance used by NxmType.
    public OfIcmpCodeNxmEntry() {
    }

    @Override
    public NxmType getNxmType() {
        return NxmType.NXM_OF_ICMP_CODE;
    }

    @Override
    public String toString() {
        return "OfIcmpCodeNxMatch: code=" + value;
    }
}
