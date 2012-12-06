/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.packets;

public class Unsigned {
    public static int unsign(byte b) {
        return b & 0xff;
    }

    public static int unsign(short s) {
        return s & 0xffff;
    }

    public static long unsign(int i) {
        return (long)i & 0xffffffffL;
    }
}
