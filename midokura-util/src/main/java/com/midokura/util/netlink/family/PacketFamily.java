/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.netlink.family;

import java.util.List;

import com.midokura.util.netlink.Netlink;
import com.midokura.util.netlink.NetlinkMessage;
import com.midokura.util.netlink.dp.flows.FlowAction;
import com.midokura.util.netlink.dp.flows.FlowKey;
import com.midokura.util.netlink.dp.flows.FlowStats;

/**
 * // TODO: mtoader ! Please explain yourself.
 */
public class PacketFamily
    extends Netlink.CommandFamily<PacketFamily.Cmd, PacketFamily.AttrKey> {

    public static final String NAME = "ovs_packet";

    public static final byte VERSION = 1;

    public enum Cmd implements Netlink.ByteConstant {
        NEW(1), DEL(2), GET(3), SET(4);

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
         * Flow table miss.
         */
        public static final AttrKey<List<FlowKey>> MISS = attr(1);
        /**
         * OVS_ACTION_ATTR_USERSPACE action.
         */
        public static final AttrKey<List<FlowAction>> ACTION = attr(2);
        /**
         * Apply actions to a packet.
         */
        public static final AttrKey<FlowStats> EXECUTE = attr(3);

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
