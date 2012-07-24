/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.netlink.dp.flows;

import com.midokura.util.netlink.NetlinkMessage;
import com.midokura.util.netlink.messages.BuilderAware;

/**
* // TODO: mtoader ! Please explain yourself.
*/
public interface FlowAction<Action extends FlowAction<Action>> extends BuilderAware, NetlinkMessage.Attr<Action> {

    public static class FlowActionKey<Action extends FlowAction> extends
                                                        NetlinkMessage.AttrKey<Action> {

        /** u32 port number. */
        public static final FlowActionKey<FlowActionOutput> OUTPUT = attr(1);

        /** Nested OVS_USERSPACE_ATTR_*. */
        public static final FlowActionKey<FlowActionUserspace> USERSPACE = attrNest(2);

        /** One nested OVS_KEY_ATTR_*. */
        public static final FlowActionKey<FlowActionSetKey> SET = attrNest(3);

        /** struct ovs_action_push_vlan. */
        public static final FlowActionKey<FlowActionPushVLAN> PUSH_VLAN = attr(4);

        /* No argument. */
        public static final FlowActionKey<FlowActionPopVLAN> POP_VLAN = attr(5);

        /* Nested OVS_SAMPLE_ATTR_*. */
        public static final FlowActionKey<FlowActionSample> SAMPLE = attrNest(6);

        private FlowActionKey(int id, boolean nested) {
            super(id, nested);
        }

        static <T extends FlowAction> FlowActionKey<T> attr(int id) {
            return new FlowActionKey<T>(id, false);
        }

        static <T extends FlowAction> FlowActionKey<T> attrNest(int id) {
            return new FlowActionKey<T>(id, true);
        }
    }

    static NetlinkMessage.CustomBuilder<FlowAction> Builder = new NetlinkMessage.CustomBuilder<FlowAction>() {
        @Override
        public FlowAction newInstance(short type) {
            switch (type) {
                case 1: return new FlowActionOutput();
                case 2: return new FlowActionUserspace();
                case 3: return new FlowActionSetKey();
                case 4: return new FlowActionPushVLAN();
                case 5: return new FlowActionPopVLAN();
                case 6: return new FlowActionSample();
                default: return null;
            }
        }
    };

}
