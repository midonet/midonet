/*
 * @(#)NxmHeaderCodec.java        1.6 11/12/27
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.openflow.nxm;

public class NxmHeaderCodec {

    private NxmHeaderCodec() {
    }

    public static NxmType getType(int header) {
        return NxmType.get(((header >>> 9) & 0x7fffff));
    }

    public static short getLength(int header) {
        return (short) (header & 0xFF);
    }

    public static boolean hasMask(int header) {
        return (((header >>> 8) & 1) == 1);
    }

}
