/*
 * Copyright (c) 2012 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.odp.family;

import java.util.List;

import org.midonet.netlink.Netlink;
import org.midonet.netlink.NetlinkMessage;
import org.midonet.odp.OpenVSwitch;
import org.midonet.odp.flows.FlowAction;
import org.midonet.odp.flows.FlowKey;
import org.midonet.packets.Ethernet;

/**
 *
 */
public class PacketFamily extends
        Netlink.CommandFamily<PacketFamily.Cmd,
            PacketFamily.AttrKey<PacketFamily>> {

    public static final String NAME = OpenVSwitch.Packet.Family;
    public static final byte VERSION = OpenVSwitch.Packet.version;

    public enum Cmd implements Netlink.ByteConstant {

        MISS(OpenVSwitch.Packet.Cmd.Miss),
        ACTION(OpenVSwitch.Packet.Cmd.Action),
        EXECUTE(OpenVSwitch.Packet.Cmd.Exec);

        byte value;

        private Cmd(int value) {
            this.value = (byte) value;
        }

        @Override
        public byte getValue() {
            return value;
        }
    }

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

    public final OvsBaseContext contextMiss;
    public final OvsBaseContext contextExec;
    public final OvsBaseContext contextAction;

    public PacketFamily(int familyId) {
        super(familyId, VERSION);
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
