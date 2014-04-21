/*
 * Copyright (c) 2012 Midokura Europe SARL, All Rights Reserved.
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
public class FlowFamily {

    public static final byte VERSION = OpenVSwitch.Flow.version;
    public static final String NAME = OpenVSwitch.Flow.Family;

    public interface AttrKey {

        /* Sequence of OVS_KEY_ATTR_* attributes. */
        NetlinkMessage.AttrKey<List<FlowKey>> KEY =
            NetlinkMessage.AttrKey.attrNested(OpenVSwitch.Flow.Attr.Key);

        /* Nested OVS_ACTION_ATTR_* attributes. */
        NetlinkMessage.AttrKey<List<FlowAction>> ACTIONS =
            NetlinkMessage.AttrKey.attrNested(OpenVSwitch.Flow.Attr.Actions);

        /* struct ovs_flow_stats. */
        NetlinkMessage.AttrKey<FlowStats> STATS =
            NetlinkMessage.AttrKey.attr(OpenVSwitch.Flow.Attr.Stats);

        /* 8-bit OR'd TCP flags. */
        NetlinkMessage.AttrKey<Byte> TCP_FLAGS =
            NetlinkMessage.AttrKey.attr(OpenVSwitch.Flow.Attr.TCPFlags);

        /* u64 msecs last used in monotonic time. */
        NetlinkMessage.AttrKey<Long> USED =
            NetlinkMessage.AttrKey.attr(OpenVSwitch.Flow.Attr.Used);

        /* Flag to clear stats, tcp_flags, used. */
        NetlinkMessage.AttrKey<Short> CLEAR =
            NetlinkMessage.AttrKey.attr(OpenVSwitch.Flow.Attr.Clear);

        // unused attribute -> MetaFlow ??
        //NetlinkMessage.AttrKey<?> MASK =
        //    NetlinkMessage.AttrKey.attr(OpenVSwitch.Flow.Attr.Mask);

    }

    public final OvsBaseContext contextNew;
    public final OvsBaseContext contextDel;
    public final OvsBaseContext contextGet;
    public final OvsBaseContext contextSet;

    public FlowFamily(int familyId) {
        contextNew = new FlowContext(familyId, OpenVSwitch.Flow.Cmd.New);
        contextDel = new FlowContext(familyId, OpenVSwitch.Flow.Cmd.Del);
        contextGet = new FlowContext(familyId, OpenVSwitch.Flow.Cmd.Get);
        contextSet = new FlowContext(familyId, OpenVSwitch.Flow.Cmd.Set);
    }

    private static class FlowContext extends OvsBaseContext {
        public FlowContext(int familyId, int command) {
            super(familyId, command);
        }
        @Override
        public byte version() { return (byte) OpenVSwitch.Flow.version; }
    }
}
