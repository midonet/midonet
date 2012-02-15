/*
 * @(#)OfTcpDstNxmEntry.java        1.6 11/12/27
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.openflow.nxm;

import java.nio.ByteBuffer;

public class OfTcpDstNxmEntry extends ShortNomaskNxmEntry {

    public OfTcpDstNxmEntry(short port) {
        super(port);
    }

    // Default constructor for class.newInstance used by NxmType.
    public OfTcpDstNxmEntry() {
    }

    @Override
    public NxmType getNxmType() {
        return NxmType.NXM_OF_TCP_DST;
    }

    @Override
    public String toString() {
        return "OfTcpDstNxmEntry: port=" + value;
    }
}
