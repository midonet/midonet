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

    public interface AttrKey {

        NetlinkMessage.AttrKey<Short> FAMILY_ID = NetlinkMessage.AttrKey.attr(1);
        NetlinkMessage.AttrKey<String> FAMILY_NAME = NetlinkMessage.AttrKey.attr(2);
        NetlinkMessage.AttrKey<String> FAMILY_VERSION = NetlinkMessage.AttrKey.attr(3);
        NetlinkMessage.AttrKey<String> HDRSIZE = NetlinkMessage.AttrKey.attr(4);
        NetlinkMessage.AttrKey<String> MAXATTR = NetlinkMessage.AttrKey.attr(5);
        NetlinkMessage.AttrKey<String> OPS = NetlinkMessage.AttrKey.attr(6);
        NetlinkMessage.AttrKey<NetlinkMessage> MCAST_GROUPS = NetlinkMessage.AttrKey.attr(7);

        NetlinkMessage.AttrKey<String> MCAST_GRP_NAME = NetlinkMessage.AttrKey.attr(1);
        NetlinkMessage.AttrKey<Integer> MCAST_GRP_ID = NetlinkMessage.AttrKey.attr(2);

    }

    private CtrlFamily() { }
}
