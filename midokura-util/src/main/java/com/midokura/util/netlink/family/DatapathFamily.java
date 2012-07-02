/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.netlink.family;

import com.midokura.util.netlink.Netlink;

/**
 * OVS Datapath netlink protocol.
 */
public class DatapathFamily extends Netlink.CommandFamily<DatapathFamily.Cmd, DatapathFamily.Attr>{

    public static final byte VERSION = 1;

    public enum Cmd implements Netlink.ByteConstant {
        NEW(1), DEL(2), GET(3), SET(4);

        byte value;

        private Cmd(int value) {
            this.value = (byte)value;
        }

        @Override
        public byte getValue() {
            return value;
        }
    }

    public enum Attr implements Netlink.ShortConstant {
        NAME(1), UPCALL_PID(2), STATS(3)
        ;

        private Attr(int value) {
            this.value = (short)value;
        }

        short value;

        @Override
        public short getValue() {
            return value;
        }
    }

    public DatapathFamily(int familyId) {
        super(familyId, VERSION);
    }
}
