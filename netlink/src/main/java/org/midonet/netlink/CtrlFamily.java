/*
 * Copyright (c) 2012 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.netlink;

/**
 * Abstraction for the NETLINK CTRL family of commands and attributes.
 */
public final class CtrlFamily {

    public static final int FAMILY_ID = 0x10;
    public static final int VERSION = 1;

    public enum Context implements NetlinkRequestContext {
        Unspec(0),
        NewFamily(1),
        DelFamily(2),
        GetFamily(3),
        NewOps(4),
        DelOps(5),
        GetOps(6),
        NewMCastGrp(7),
        DelMCastGrp(8),
        GetMCastGrp(9);

        final byte command;

        Context(int command) { this.command = (byte) command; }
        public short commandFamily() { return FAMILY_ID; }
        public byte command() { return command; }
        public byte version() { return VERSION; }
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

    private CtrlFamily() { }
}
