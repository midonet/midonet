/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.odp.flows;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.messages.BuilderAware;
import org.midonet.odp.OpenVSwitch;

public interface FlowAction<Action extends FlowAction<Action>>
        extends BuilderAware, NetlinkMessage.Attr<Action> {

    public static class FlowActionAttr<Action extends FlowAction<Action>>
            extends NetlinkMessage.AttrKey<Action> {

        /** u32 port number. */
        public static final FlowActionAttr<FlowActionOutput> OUTPUT =
            attr(OpenVSwitch.FlowAction.Attr.Output);

        /** Nested OVS_USERSPACE_ATTR_*. */
        public static final FlowActionAttr<FlowActionUserspace> USERSPACE =
            attrNest(OpenVSwitch.FlowAction.Attr.Userspace);

        /** One nested OVS_KEY_ATTR_*. */
        public static final FlowActionAttr<FlowActionSetKey> SET =
            attrNest(OpenVSwitch.FlowAction.Attr.Set);

        /** struct ovs_action_push_vlan. */
        public static final FlowActionAttr<FlowActionPushVLAN> PUSH_VLAN =
            attr(OpenVSwitch.FlowAction.Attr.PushVLan);

        /** No argument. */
        public static final FlowActionAttr<FlowActionPopVLAN> POP_VLAN =
            attr(OpenVSwitch.FlowAction.Attr.PopVLan);

        /** Nested OVS_SAMPLE_ATTR_*. */
        public static final FlowActionAttr<FlowActionSample> SAMPLE =
            attrNest(OpenVSwitch.FlowAction.Attr.Sample);

        public FlowActionAttr(int id, boolean nested) {
            super(id, nested);
        }

        static <T extends FlowAction<T>> FlowActionAttr<T> attr(int id) {
            return new FlowActionAttr<T>(id, false);
        }

        static <T extends FlowAction<T>> FlowActionAttr<T> attrNest(int id) {
            return new FlowActionAttr<T>(id, true);
        }
    }

    static NetlinkMessage.CustomBuilder<FlowAction<?>> Builder =
        new NetlinkMessage.CustomBuilder<FlowAction<?>>() {
            @Override
            public FlowAction newInstance(short type) {
                switch (type) {

                    case OpenVSwitch.FlowAction.Attr.Output:
                        return new FlowActionOutput();

                    case OpenVSwitch.FlowAction.Attr.Userspace:
                        return new FlowActionUserspace();

                    case OpenVSwitch.FlowAction.Attr.Set:
                        return new FlowActionSetKey();

                    case OpenVSwitch.FlowAction.Attr.PushVLan:
                        return new FlowActionPushVLAN();

                    case OpenVSwitch.FlowAction.Attr.PopVLan:
                        return new FlowActionPopVLAN();

                    case OpenVSwitch.FlowAction.Attr.Sample:
                        return new FlowActionSample();

                    default: return null;
                }
            }
        };

}
