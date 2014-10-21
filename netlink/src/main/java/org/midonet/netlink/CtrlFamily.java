/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.netlink;

import java.nio.ByteBuffer;

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

    public static Reader<Integer> mcastGrpDeserializer(final String groupName) {
        return new Reader<Integer>() {
            public Integer deserializeFrom(ByteBuffer buf) {
                if (buf == null)
                    return null;

                int pos = NetlinkMessage.seekAttribute(buf, AttrKey.MCAST_GROUPS);
                if (pos <= 0)
                    return null;

                buf.position(pos + 4); // skip nested header

                String name =
                    NetlinkMessage.readStringAttr(buf, AttrKey.MCAST_GRP_NAME);

                if (name.equals(groupName)) {
                    pos = NetlinkMessage.seekAttribute(buf, AttrKey.MCAST_GRP_ID);
                    if (pos <= 0)
                        return null;
                    return buf.getInt(pos);
                }

                return null;
            }
        };
    }

    public static final Reader<Short> familyIdDeserializer =
        new Reader<Short>() {
            public Short deserializeFrom(ByteBuffer buf) {
                if (buf == null)
                    return 0;
                int pos = NetlinkMessage.seekAttribute(buf, AttrKey.FAMILY_ID);
                if (pos <= 0)
                    return null;
                return buf.getShort(pos);
            }
        };

    private CtrlFamily() { }
}
