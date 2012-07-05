/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.netlink.family;

import com.midokura.util.netlink.Netlink;
import com.midokura.util.netlink.NetlinkMessage;

/**
 * Abstraction for the NETLINK OvsDatapath family of commands and attributes.
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

    public static class Attr<T> extends NetlinkMessage.Attr<T> {

        public static final Attr<String> NAME = attr(1);
        public static final Attr<Integer> UPCALL_PID = attr(2);
        public static final Attr<Object> STATS = attr(3);

        public Attr(int id) {
            super(id);
        }

        static <T> Attr<T> attr(int id) {
            return new Attr<T>(id);
        }
    }

    public DatapathFamily(int familyId) {
        super(familyId, VERSION);
    }
}
