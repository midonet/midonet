/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
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
 * Abstraction for the NETLINK OvsPacket family of commands and attributes.
 */
public class PacketFamily {

    public static final String NAME = OpenVSwitch.Packet.Family;
    public static final byte VERSION = OpenVSwitch.Packet.version;

    public interface AttrKey {

        /**
         * Packet data.
         */
        NetlinkMessage.AttrKey<Ethernet> PACKET =
            NetlinkMessage.AttrKey.attr(OpenVSwitch.Packet.Attr.Packet);

        /**
         * Nested OVS_KEY_ATTR_* attributes.
         */
        NetlinkMessage.AttrKey<List<FlowKey>> KEY =
            NetlinkMessage.AttrKey.attr(OpenVSwitch.Packet.Attr.Key);

        /**
         * Nested OVS_ACTION_ATTR_* attributes.
         */
        NetlinkMessage.AttrKey<List<FlowAction>> ACTIONS =
            NetlinkMessage.AttrKey.attr(OpenVSwitch.Packet.Attr.Actions);

        /**
         * u64 OVS_ACTION_ATTR_USERSPACE arg.
         */
        NetlinkMessage.AttrKey<Long> USERDATA =
            NetlinkMessage.AttrKey.attr(OpenVSwitch.Packet.Attr.Userdata);

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
