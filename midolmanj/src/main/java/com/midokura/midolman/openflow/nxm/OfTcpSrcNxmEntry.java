/*
 * @(#)OfTcpSrcNxmEntry.java        1.6 11/12/27
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.openflow.nxm;

import java.nio.ByteBuffer;

public class OfTcpSrcNxmEntry extends ShortNomaskNxmEntry {

    public OfTcpSrcNxmEntry(short port) {
        super(port);
    }

    // Default constructor for class.newInstance used by NxmType.
    public OfTcpSrcNxmEntry() {
    }

    @Override
    public NxmType getNxmType() {
        return NxmType.NXM_OF_TCP_SRC;
    }

    @Override
    public String toString() {
        return "OfTcpSrcNxmEntry: port=" + value;
    }
}