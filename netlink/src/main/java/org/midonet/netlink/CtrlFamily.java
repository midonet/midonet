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

        short FAMILY_ID       = (short) 1;
        short FAMILY_NAME     = (short) 2;
        short FAMILY_VERSION  = (short) 3;
        short HDRSIZE         = (short) 4;
        short MAXATTR         = (short) 5;
        short OPS             = (short) 6;
        short MCAST_GROUPS    = (short) 7;

        short MCAST_GRP_NAME  = (short) 1;
        short MCAST_GRP_ID    = (short) 2;
    }

    public static ByteBuffer familyNameRequest(ByteBuffer buf, String name) {
        NetlinkMessage.writeStringAttr(buf, AttrKey.FAMILY_NAME, name);
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

                ByteBuffer sub =
                    NetlinkMessage.getAttrValueNested(input.get(0),
                                                      AttrKey.MCAST_GROUPS);

                if (sub == null)
                    return null;

                sub.getShort();
                sub.getShort();

                String name =
                    NetlinkMessage.getAttrValueString(sub,
                                                      AttrKey.MCAST_GRP_NAME);

                if (name.equals(groupName))
                    return NetlinkMessage.getAttrValueInt(sub,
                                                          AttrKey.MCAST_GRP_ID);

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

                return NetlinkMessage.getAttrValueShort(input.get(0),
                                                        AttrKey.FAMILY_ID);
            }
        };

    private CtrlFamily() { }
}
