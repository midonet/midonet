/*
 * @(#)OfIpTosNxmEntry.java        1.6 11/12/27
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.openflow.nxm;

import java.nio.ByteBuffer;

public class OfIpTosNxmEntry extends ByteNomaskNxmEntry {

    private final static short MAX_VALUE = 0xFC;
    // Format: 8-bit integer with 2 least-significant bits forced to 0.
    private byte tos = 0;

    // Default constructor for class.newInstance used by NxmType.
    public OfIpTosNxmEntry() {
    }

    public OfIpTosNxmEntry(byte tos) {
        super((byte)(tos & MAX_VALUE));
    }

    @Override
    public NxmType getNxmType() {
        return NxmType.NXM_OF_IP_TOS;
    }

    @Override
    public String toString() {
        return "OfIpTosNxmEntry: tos=" + value;
    }
}
