/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
 */
package org.midonet.odp.flows;

import java.nio.ByteBuffer;

import org.midonet.netlink.NetlinkMessage.AttrKey;
import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.Translator;
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
    interface UserSpaceOnly extends FlowKey {

        /**
         * Tells if the UserSpaceOnly key extends <pre>key</pre> with further
         * fields. For example, this is the case of <pre>FlowKeyICMPError</pre>
         * with respect to <pre>FlowKeyICMP</pre>: it provides additional
         * matching fields.
         *
         * The goal of the method is to tell if this is a child of a given
         * FlowKey, which will indicate to FlowMatches.replaceKey that key needs
         * to be replaced by this.
         *
         * In other words: if FlowMatches says "hey FlowKeyICMPError, is this
         * FlowKey your parent (e.g: less specific than yourself?)" we'll say
         * yes so the parent gets replaced by this (which provides a more
         * specific match).
         *
         * @param key
         * @return
         */
        public boolean isChildOf(FlowKey key);

    }

    public interface FlowKeyAttr {

        /** Nested set of encapsulated attributes. */
        AttrKey<FlowKeyEncap> ENCAP =
            AttrKey.attr(OpenVSwitch.FlowKey.Attr.Encap);

        /** u32 skb->priority */
        AttrKey<FlowKeyPriority> PRIORITY =
            AttrKey.attr(OpenVSwitch.FlowKey.Attr.Priority);

        /** u32 OVS dp port number */
        AttrKey<FlowKeyInPort> IN_PORT =
            AttrKey.attr(OpenVSwitch.FlowKey.Attr.InPort);

        /** struct ovs_key_ethernet */
        AttrKey<FlowKeyEthernet> ETHERNET =
            AttrKey.attr(OpenVSwitch.FlowKey.Attr.Ethernet);

        /** be16 VLAN TCI */
        AttrKey<FlowKeyVLAN> VLAN =
            AttrKey.attr(OpenVSwitch.FlowKey.Attr.VLan);

        /** be16 Ethernet type */
        AttrKey<FlowKeyEtherType> ETHERTYPE =
            AttrKey.attr(OpenVSwitch.FlowKey.Attr.Ethertype);

        /** struct ovs_key_ipv4 */
        AttrKey<FlowKeyIPv4> IPv4 =
            AttrKey.attr(OpenVSwitch.FlowKey.Attr.IPv4);

        /** struct ovs_key_ipv6 */
        AttrKey<FlowKeyIPv6> IPv6 =
            AttrKey.attr(OpenVSwitch.FlowKey.Attr.IPv6);

        /** struct ovs_key_tcp */
        AttrKey<FlowKeyTCP> TCP =
            AttrKey.attr(OpenVSwitch.FlowKey.Attr.TCP);

        /** struct ovs_key_udp */
        AttrKey<FlowKeyUDP> UDP =
            AttrKey.attr(OpenVSwitch.FlowKey.Attr.UDP);

        /** struct ovs_key_icmp */
        AttrKey<FlowKeyICMP> ICMP =
            AttrKey.attr(OpenVSwitch.FlowKey.Attr.ICMP);

        /** struct ovs_key_icmpv6 */
        AttrKey<FlowKeyICMPv6> ICMPv6 =
            AttrKey.attr(OpenVSwitch.FlowKey.Attr.ICMPv6);

        /** struct ovs_key_arp */
        AttrKey<FlowKeyARP> ARP =
            AttrKey.attr(OpenVSwitch.FlowKey.Attr.ARP);

        /** struct ovs_key_nd */
        AttrKey<FlowKeyND> ND =
            AttrKey.attr(OpenVSwitch.FlowKey.Attr.ND);

        /** struct ovs_key_ipv4_tunnel */
        AttrKey<FlowKeyTunnel> TUNNEL =
            AttrKey.attrNested(OpenVSwitch.FlowKey.Attr.Tunnel);

    }

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
    Translator<FlowKey> translator = new Translator<FlowKey>() {
        public short attrIdOf(FlowKey value) {
            return value.attrId();
        }
        public int serializeInto(ByteBuffer receiver, FlowKey value) {
            return value.serializeInto(receiver);
        }
        public FlowKey deserializeFrom(ByteBuffer source) {
            throw new UnsupportedOperationException();
        }
    };
}
