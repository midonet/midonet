/*
 * @(#)OfUdpSrcNxmEntry.java        1.6 11/12/27
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.openflow.nxm;

import java.nio.ByteBuffer;

public class OfUdpSrcNxmEntry extends ShortNomaskNxmEntry {

    public OfUdpSrcNxmEntry(short port) {
        super(port);
    }

    // Default constructor for class.newInstance used by NxmType.
    public OfUdpSrcNxmEntry() {
    }

    @Override
    public NxmType getNxmType() {
        return NxmType.NXM_OF_UDP_SRC;
    }

    @Override
    public String toString() {
        return "OfUdpSrcNxmEntry: port=" + value;
    }
}
