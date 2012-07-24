/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.netlink.family;

import com.midokura.netlink.Netlink;
import com.midokura.netlink.NetlinkMessage;

/**
 * Abstraction for the NETLINK CTRL family of commands and attributes.
 */
public class CtrlFamily
    extends Netlink.CommandFamily<CtrlFamily.Cmd, CtrlFamily.AttrKey> {

    public static final int FAMILY_ID = 0x10;
    public static final int VERSION = 1;

    public enum Cmd implements Netlink.ByteConstant {
        UNSPEC(0),
        NEWFAMILY(1), DELFAMILY(2), GETFAMILY(3),
        NEWOPS(4), DELOPS(5), GETOPS(6),
        NEWMCAST_GRP(7), DELMCAST_GRP(8), GETMCAST_GRP(9),;

        byte value;

        Cmd(int value) {
            this.value = (byte) value;
        }

        @Override
        public byte getValue() {
            return value;
        }
    }

    public static class AttrKey<T> extends NetlinkMessage.AttrKey<T> {

        public static final AttrKey<Short> FAMILY_ID = attr(1);
        public static final AttrKey<String> FAMILY_NAME = attr(2);
        public static final AttrKey<String> FAMILY_VERSION = attr(3);
        public static final AttrKey<String> HDRSIZE = attr(4);
        public static final AttrKey<String> MAXATTR = attr(5);
        public static final AttrKey<String> OPS = attr(6);
        public static final AttrKey<NetlinkMessage> MCAST_GROUPS = attr(7);

        public static final AttrKey<String> MCAST_GRP_NAME = attr(1);
        public static final AttrKey<Integer> MCAST_GRP_ID = attr(2);

        private AttrKey(int id) {
            super(id);
        }

        static <T> AttrKey<T> attr(int id) {
            return new AttrKey<T>(id);
        }
    }

    public CtrlFamily() {
        super(FAMILY_ID, VERSION);
    }
}
