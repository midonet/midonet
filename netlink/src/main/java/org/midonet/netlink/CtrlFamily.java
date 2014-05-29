/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
 */
package org.midonet.netlink;

import java.nio.ByteBuffer;
import java.util.List;

import com.google.common.base.Function;

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

    public static ByteBuffer familyNameRequest(ByteBuffer buf, String name) {
        NetlinkMessage.writeStringAttr(buf, AttrKey.FAMILY_NAME.getId(), name);
        buf.flip();
        return buf;
    }

    public static Function<List<ByteBuffer>, Integer> mcastGrpDeserializer(
            final String groupName) {
        return new Function<List<ByteBuffer>, Integer>() {
            @Override
            public Integer apply(List<ByteBuffer> input) {
                if (input == null)
                    return null;

                NetlinkMessage res = new NetlinkMessage(input.get(0));
                NetlinkMessage sub =
                    res.getAttrValueNested(CtrlFamily.AttrKey.MCAST_GROUPS);

                if (sub == null)
                    return null;

                sub.getShort();
                sub.getShort();

                String name =
                    sub.getAttrValueString(CtrlFamily.AttrKey.MCAST_GRP_NAME);

                if (name.equals(groupName))
                    return sub.getAttrValueInt(CtrlFamily.AttrKey.MCAST_GRP_ID);

                return null;
            }
        };
    }

    public static final Function<List<ByteBuffer>, Short> familyIdDeserializer =
        new Function<List<ByteBuffer>, Short>() {
            @Override
            public Short apply(List<ByteBuffer> input) {
                if (input == null || input.isEmpty() || input.get(0) == null)
                    return 0;

                return new NetlinkMessage(input.get(0))
                              .getAttrValueShort(AttrKey.FAMILY_ID);
            }
        };

    private CtrlFamily() { }
}
