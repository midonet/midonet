/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
 */
package org.midonet.odp.flows;

import java.nio.ByteBuffer;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.Writer;
import org.midonet.netlink.messages.BuilderAware;
import org.midonet.odp.OpenVSwitch;

public interface FlowAction extends BuilderAware {

    /** write the action into a bytebuffer, without its header. */
    int serializeInto(ByteBuffer buf);

    /** give the netlink attr id of this action instance. */
    short attrId();

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
    Writer<FlowAction> actionWriter = new Writer<FlowAction>() {
        public short attrIdOf(FlowAction value) {
            return value.attrId();
        }
        public int serializeInto(ByteBuffer receiver, FlowAction value) {
            return value.serializeInto(receiver);
        }
    };
}
