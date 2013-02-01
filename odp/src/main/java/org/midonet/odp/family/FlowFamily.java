/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.odp.family;

import java.util.List;

import org.midonet.netlink.Netlink;
import org.midonet.netlink.NetlinkMessage;
import org.midonet.odp.flows.FlowAction;
import org.midonet.odp.flows.FlowKey;
import org.midonet.odp.flows.FlowStats;

/**
 * // TODO: mtoader ! Please explain yourself.
 */
public class FlowFamily extends Netlink.CommandFamily<FlowFamily.Cmd, FlowFamily.AttrKey>{

    public static final byte VERSION = 1;
    public static final String NAME = "ovs_flow";

    public enum Cmd implements Netlink.ByteConstant {
        NEW(1), DEL(2), GET(3), SET(4);

        byte value;

        private Cmd(int value) {
            this.value = (byte)value;
        }

        @Override
        public byte getValue() {
            return value;
        }
    }

    public static class AttrKey<T> extends NetlinkMessage.AttrKey<T> {

        /* Sequence of OVS_KEY_ATTR_* attributes. */
        public static final AttrKey<List<FlowKey<?>>> KEY = attrNested(1);
        /* Nested OVS_ACTION_ATTR_* attributes. */
        public static final AttrKey<List<FlowAction<?>>> ACTIONS = attrNested(2);
        /* struct ovs_flow_stats. */
        public static final AttrKey<FlowStats> STATS = attr(3);
        /* 8-bit OR'd TCP flags. */
        public static final AttrKey<Byte> TCP_FLAGS = attr(4);
        /* u64 msecs last used in monotonic time. */
        public static final AttrKey<Long> USED = attr(5);
        /* Flag to clear stats, tcp_flags, used. */
        public static final AttrKey<Netlink.Flag> CLEAR = attr(6);

        private AttrKey(int id, boolean nested) {
            super(id, nested);
        }

        static <T> AttrKey<T> attr(int id) {
            return new AttrKey<T>(id, false);
        }

        static <T> AttrKey<T> attrNested(int id) {
            return new AttrKey<T>(id, true);
        }
    }

    public FlowFamily(int familyId) {
        super(familyId, VERSION);
    }
}
