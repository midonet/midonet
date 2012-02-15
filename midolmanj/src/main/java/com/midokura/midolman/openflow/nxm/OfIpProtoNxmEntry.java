/*
 * @(#)OfIpProtoNxmEntry.java        1.6 11/12/27
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.openflow.nxm;

import java.nio.ByteBuffer;

public class OfIpProtoNxmEntry extends ByteNomaskNxmEntry {

    // Default constructor for class.newInstance used by NxmType.
    public OfIpProtoNxmEntry() {
    }

    public OfIpProtoNxmEntry(byte proto) {
        super(proto);
    }

    @Override
    public NxmType getNxmType() {
        return NxmType.NXM_OF_IP_PROTO;
    }

    @Override
    public String toString() {
        return "OfIpProtoNxmEntry: proto=" + value;
    }
}
