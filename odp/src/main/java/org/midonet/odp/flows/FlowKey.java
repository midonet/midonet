/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
 */
package org.midonet.odp.flows;

import java.nio.ByteBuffer;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.Writer;
import org.midonet.netlink.messages.BuilderAware;
import org.midonet.odp.OpenVSwitch;

public interface FlowKey extends BuilderAware {

    /** write the key into a bytebuffer, without its header. */
    int serializeInto(ByteBuffer buf);

    /** give the netlink attr id of this key instance. */
    short attrId();

    /**
     * Should be used by those keys that are only supported in user space.
     *
     * Note that Matches containing any UserSpaceOnly key will NOT be sent
     * to the datapath, and will also need to have all UserSpace-related actions
     * applied before being sent to the DP.
     */
    interface UserSpaceOnly extends FlowKey { }

    static NetlinkMessage.CustomBuilder<FlowKey> Builder =
        new NetlinkMessage.CustomBuilder<FlowKey>() {
            @Override
            public FlowKey newInstance(short type) {
                switch (type) {

                    case OpenVSwitch.FlowKey.Attr.Encap:
                        return new FlowKeyEncap();

                    case OpenVSwitch.FlowKey.Attr.Priority:
                        return new FlowKeyPriority();

                    case OpenVSwitch.FlowKey.Attr.InPort:
                        return new FlowKeyInPort();

                    case OpenVSwitch.FlowKey.Attr.Ethernet:
                        return new FlowKeyEthernet();

                    case OpenVSwitch.FlowKey.Attr.VLan:
                        return new FlowKeyVLAN();

                    case OpenVSwitch.FlowKey.Attr.Ethertype:
                        return new FlowKeyEtherType();

                    case OpenVSwitch.FlowKey.Attr.IPv4:
                        return new FlowKeyIPv4();

                    case OpenVSwitch.FlowKey.Attr.IPv6:
                        return new FlowKeyIPv6();

                    case OpenVSwitch.FlowKey.Attr.TCP:
                        return new FlowKeyTCP();

                    case OpenVSwitch.FlowKey.Attr.UDP:
                        return new FlowKeyUDP();

                    case OpenVSwitch.FlowKey.Attr.ICMP:
                        return new FlowKeyICMP();

                    case OpenVSwitch.FlowKey.Attr.ICMPv6:
                        return new FlowKeyICMPv6();

                    case OpenVSwitch.FlowKey.Attr.ARP:
                        return new FlowKeyARP();

                    case OpenVSwitch.FlowKey.Attr.ND:
                        return new FlowKeyND();

                    case OpenVSwitch.FlowKey.Attr.Tunnel:
                        return new FlowKeyTunnel();

                    default: return null;
                }
            }
        };

    /** stateless serialiser and deserialiser of ovs FlowKey classes. Used
     *  as a typeclass with NetlinkMessage.writeAttr() and writeAttrSet()
     *  for assembling ovs requests. */
    Writer<FlowKey> keyWriter = new Writer<FlowKey>() {
        public short attrIdOf(FlowKey value) {
            return value.attrId();
        }
        public int serializeInto(ByteBuffer receiver, FlowKey value) {
            return value.serializeInto(receiver);
        }
    };
}
