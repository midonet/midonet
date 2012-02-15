/*
 * @(#)OfInPortNxmEntry.java        1.6 11/12/27
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.openflow.nxm;

import java.nio.ByteBuffer;

public class OfInPortNxmEntry extends ShortNomaskNxmEntry {


    public OfInPortNxmEntry(short portId) {
        super(portId);
    }

    // Default constructor for class.newInstance used by NxmType.
    public OfInPortNxmEntry() {
    }

    @Override
    public NxmType getNxmType() {
        return NxmType.NXM_OF_IN_PORT;
    }

    @Override
    public String toString() {
        return "OfInPortNxmMatch: portId=" + value;
    }
}
