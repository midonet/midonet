/*
 * Copyright (c) 2012 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.odp.family;

import java.util.List;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.NetlinkRequestContext;
import org.midonet.odp.OpenVSwitch;
import org.midonet.odp.flows.FlowAction;
import org.midonet.odp.flows.FlowKey;
import org.midonet.packets.Ethernet;

/**
 *
 */
public class PacketFamily {

    public static final String NAME = OpenVSwitch.Packet.Family;
    public static final byte VERSION = OpenVSwitch.Packet.version;

    public static class AttrKey<T> extends NetlinkMessage.AttrKey<T> {

        /**
         * Packet data.
         */
        public static final AttrKey<Ethernet> PACKET =
            attr(OpenVSwitch.Packet.Attr.Packet);

        /**
         * Nested OVS_KEY_ATTR_* attributes.
         */
        public static final AttrKey<List<FlowKey>> KEY =
            attr(OpenVSwitch.Packet.Attr.Key);

        /**
         * Nested OVS_ACTION_ATTR_* attributes.
         */
        public static final AttrKey<List<FlowAction>> ACTIONS =
            attr(OpenVSwitch.Packet.Attr.Actions);

        /**
         * u64 OVS_ACTION_ATTR_USERSPACE arg.
         */
        public static final AttrKey<Long> USERDATA =
            attr(OpenVSwitch.Packet.Attr.Userdata);

        public AttrKey(int id) {
            super(id);
        }

        static <T> AttrKey<T> attr(int id) {
            return new AttrKey<T>(id);
        }
    }

    public final short familyId;

    public final NetlinkRequestContext contextMiss;
    public final NetlinkRequestContext contextExec;
    public final NetlinkRequestContext contextAction;

    public PacketFamily(int familyId) {
        this.familyId = (short) familyId;
        contextMiss = new PacketContext(familyId, OpenVSwitch.Packet.Cmd.Miss);
        contextExec = new PacketContext(familyId, OpenVSwitch.Packet.Cmd.Exec);
        contextAction =
            new PacketContext(familyId, OpenVSwitch.Packet.Cmd.Action);
    }

    private static class PacketContext extends OvsBaseContext {
        public PacketContext(int familyId, int command) {
            super(familyId, command);
        }
        @Override
        public byte version() { return (byte) OpenVSwitch.Packet.version; }
    }
}
