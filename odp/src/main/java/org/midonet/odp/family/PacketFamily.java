/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.odp.family;

import java.util.List;

import org.midonet.netlink.Netlink;
import org.midonet.netlink.NetlinkMessage;
import org.midonet.odp.flows.FlowAction;
import org.midonet.odp.flows.FlowKey;
import org.midonet.packets.Ethernet;

/**
 *
 */
public class PacketFamily
    extends Netlink.CommandFamily<PacketFamily.Cmd, PacketFamily.AttrKey> {

    public static final String NAME = "ovs_packet";
    public static final byte VERSION = 1;

    public enum Cmd implements Netlink.ByteConstant {
        MISS(1), ACTION(2), EXECUTE(3);

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
        public static final AttrKey<Ethernet> PACKET = attr(1);

        /**
         * Nested OVS_KEY_ATTR_* attributes.
         */
        public static final AttrKey<List<FlowKey<?>>> KEY = attr(2);

        /**
         * Nested OVS_ACTION_ATTR_* attributes.
         */
        public static final AttrKey<List<FlowAction<?>>> ACTIONS = attr(3);

        /**
         * u64 OVS_ACTION_ATTR_USERSPACE arg.
         */
        public static final AttrKey<Long> USERDATA = attr(4);

        public AttrKey(int id) {
            super(id);
        }

        static <T> AttrKey<T> attr(int id) {
            return new AttrKey<T>(id);
        }
    }

    public PacketFamily(int familyId) {
        super(familyId, VERSION);
    }
}
