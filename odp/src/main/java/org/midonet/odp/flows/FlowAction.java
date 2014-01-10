/*
 * Copyright (c) 2012 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.odp.flows;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.messages.BuilderAware;

public interface FlowAction<Action extends FlowAction<Action>> extends BuilderAware, NetlinkMessage.Attr<Action> {

    public static class FlowActionAttr<Action extends FlowAction> extends
                                                        NetlinkMessage.AttrKey<Action> {

        /** u32 port number. */
        public static final FlowActionAttr<FlowActionOutput> OUTPUT = attr(1);

        /** Nested OVS_USERSPACE_ATTR_*. */
        public static final FlowActionAttr<FlowActionUserspace> USERSPACE = attrNest(2);

        /** One nested OVS_KEY_ATTR_*. */
        public static final FlowActionAttr<FlowActionSetKey> SET = attrNest(3);

        /** struct ovs_action_push_vlan. */
        public static final FlowActionAttr<FlowActionPushVLAN> PUSH_VLAN = attr(4);

        /** No argument. */
        public static final FlowActionAttr<FlowActionPopVLAN> POP_VLAN = attr(5);

        /** Nested OVS_SAMPLE_ATTR_*. */
        public static final FlowActionAttr<FlowActionSample> SAMPLE = attrNest(6);

        public FlowActionAttr(int id, boolean nested) {
            super(id, nested);
        }

        static <T extends FlowAction> FlowActionAttr<T> attr(int id) {
            return new FlowActionAttr<T>(id, false);
        }

        static <T extends FlowAction> FlowActionAttr<T> attrNest(int id) {
            return new FlowActionAttr<T>(id, true);
        }
    }

    static NetlinkMessage.CustomBuilder<FlowAction<?>> Builder = new NetlinkMessage.CustomBuilder<FlowAction<?>>() {
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
