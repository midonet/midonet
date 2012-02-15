/*
 * @(#)OfUdpDstNxmEntry.java        1.6 11/12/27
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.openflow.nxm;

import java.nio.ByteBuffer;

public class OfUdpDstNxmEntry extends ShortNomaskNxmEntry {

    public OfUdpDstNxmEntry(short port) {
        super(port);
    }

    // Default constructor for class.newInstance used by NxmType.
    public OfUdpDstNxmEntry() {
    }

    @Override
    public NxmType getNxmType() {
        return NxmType.NXM_OF_UDP_DST;
    }

    @Override
    public String toString() {
        return "OfUdpDstNxmEntry: port=" + value;
    }
}
