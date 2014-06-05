/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
 */
package org.midonet.odp.flows;

import java.nio.ByteBuffer;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.Translator;
import org.midonet.netlink.messages.BuilderAware;
import org.midonet.odp.OpenVSwitch;

public interface FlowAction extends BuilderAware {

    /** write the action into a bytebuffer, without its header. */
    int serializeInto(ByteBuffer buf);

    /** give the netlink attr id of this action instance. */
    short attrId();

    public interface FlowActionAttr {

        /** u32 port number. */
        NetlinkMessage.AttrKey<FlowActionOutput> OUTPUT =
            NetlinkMessage.AttrKey.attr(OpenVSwitch.FlowAction.Attr.Output);

        /** Nested OVS_USERSPACE_ATTR_*. */
        NetlinkMessage.AttrKey<FlowActionUserspace> USERSPACE =
            NetlinkMessage.AttrKey.attrNested(OpenVSwitch.FlowAction.Attr.Userspace);

        /** One nested OVS_KEY_ATTR_*. */
        NetlinkMessage.AttrKey<FlowActionSetKey> SET =
            NetlinkMessage.AttrKey.attrNested(OpenVSwitch.FlowAction.Attr.Set);

        /** struct ovs_action_push_vlan. */
        NetlinkMessage.AttrKey<FlowActionPushVLAN> PUSH_VLAN =
            NetlinkMessage.AttrKey.attr(OpenVSwitch.FlowAction.Attr.PushVLan);

        /** No argument. */
        NetlinkMessage.AttrKey<FlowActionPopVLAN> POP_VLAN =
            NetlinkMessage.AttrKey.attr(OpenVSwitch.FlowAction.Attr.PopVLan);

        /** Nested OVS_SAMPLE_ATTR_*. */
        NetlinkMessage.AttrKey<FlowActionSample> SAMPLE =
            NetlinkMessage.AttrKey.attrNested(OpenVSwitch.FlowAction.Attr.Sample);
    }

    static NetlinkMessage.CustomBuilder<FlowAction> Builder =
        new NetlinkMessage.CustomBuilder<FlowAction>() {
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

    /** stateless serialiser and deserialiser of ovs FlowAction classes. Used
     *  as a typeclass with NetlinkMessage.writeAttr() and writeAttrSet()
     *  for assembling ovs requests. */
    Translator<FlowAction> translator = new Translator<FlowAction>() {
        public short attrIdOf(FlowAction value) {
            return value.attrId();
        }
        public int serializeInto(ByteBuffer receiver, FlowAction value) {
            return value.serializeInto(receiver);
        }
        public FlowAction deserializeFrom(ByteBuffer source) {
            throw new UnsupportedOperationException();
        }
    };
}
