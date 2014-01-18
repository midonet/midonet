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
import org.midonet.odp.OpenVSwitch;

/**
 * // TODO: mtoader ! Please explain yourself.
 */
public class FlowFamily extends
        Netlink.CommandFamily<FlowFamily.Cmd, FlowFamily.AttrKey<FlowFamily>> {

    public static final byte VERSION = OpenVSwitch.Flow.version;
    public static final String NAME = OpenVSwitch.Flow.Family;

    public enum Cmd implements Netlink.ByteConstant {

        NEW(OpenVSwitch.Flow.Cmd.New),
        DEL(OpenVSwitch.Flow.Cmd.Del),
        GET(OpenVSwitch.Flow.Cmd.Get),
        SET(OpenVSwitch.Flow.Cmd.Set);

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
        public static final AttrKey<List<FlowKey<?>>> KEY =
            attrNested(OpenVSwitch.Flow.Attr.Key);

        /* Nested OVS_ACTION_ATTR_* attributes. */
        public static final AttrKey<List<FlowAction>> ACTIONS =
            attrNested(OpenVSwitch.Flow.Attr.Actions);

        /* struct ovs_flow_stats. */
        public static final AttrKey<FlowStats> STATS =
            attr(OpenVSwitch.Flow.Attr.Stats);

        /* 8-bit OR'd TCP flags. */
        public static final AttrKey<Byte> TCP_FLAGS =
            attr(OpenVSwitch.Flow.Attr.TCPFlags);

        /* u64 msecs last used in monotonic time. */
        public static final AttrKey<Long> USED =
            attr(OpenVSwitch.Flow.Attr.Used);

        /* Flag to clear stats, tcp_flags, used. */
        public static final AttrKey<Netlink.Flag> CLEAR =
            attr(OpenVSwitch.Flow.Attr.Clear);

        // unused attribute -> MetaFlow ??
        //public static final AttrKey<?> MASK =
        //    attr(OpenVSwitch.Flow.Attr.Mask);


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
