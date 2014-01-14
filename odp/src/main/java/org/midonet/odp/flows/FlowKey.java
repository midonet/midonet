/*
 * Copyright (c) 2012 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.odp.flows;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.messages.BuilderAware;
import org.midonet.odp.OpenVSwitch;

public interface FlowKey<Key extends FlowKey<Key>>
        extends BuilderAware, NetlinkMessage.Attr<Key> {

    /**
     * Should be used by those keys that are only supported in user space.
     *
     * Note that Matches containing any UserSpaceOnly key will NOT be sent
     * to the datapath, and will also need to have all UserSpace-related actions
     * applied before being sent to the DP.
     */
    interface UserSpaceOnly {

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
        public boolean isChildOf(FlowKey<?> key);

    }

    public static class FlowKeyAttr<T extends FlowKey<T>>
            extends NetlinkMessage.AttrKey<T> {

        /** Nested set of encapsulated attributes. */
        public static final FlowKeyAttr<FlowKeyEncap> ENCAP =
            attr(OpenVSwitch.FlowKey.Attr.Encap);

        /** u32 skb->priority */
        public static final FlowKeyAttr<FlowKeyPriority> PRIORITY =
            attr(OpenVSwitch.FlowKey.Attr.Priority);

        /** u32 OVS dp port number */
        public static final FlowKeyAttr<FlowKeyInPort> IN_PORT =
            attr(OpenVSwitch.FlowKey.Attr.InPort);

        /** struct ovs_key_ethernet */
        public static final FlowKeyAttr<FlowKeyEthernet> ETHERNET =
            attr(OpenVSwitch.FlowKey.Attr.Ethernet);

        /** be16 VLAN TCI */
        public static final FlowKeyAttr<FlowKeyVLAN> VLAN =
            attr(OpenVSwitch.FlowKey.Attr.VLan);

        /** be16 Ethernet type */
        public static final FlowKeyAttr<FlowKeyEtherType> ETHERTYPE =
            attr(OpenVSwitch.FlowKey.Attr.Ethertype);

        /** struct ovs_key_ipv4 */
        public static final FlowKeyAttr<FlowKeyIPv4> IPv4 =
            attr(OpenVSwitch.FlowKey.Attr.IPv4);

        /** struct ovs_key_ipv6 */
        public static final FlowKeyAttr<FlowKeyIPv6> IPv6 =
            attr(OpenVSwitch.FlowKey.Attr.IPv6);

        /** struct ovs_key_tcp */
        public static final FlowKeyAttr<FlowKeyTCP> TCP =
            attr(OpenVSwitch.FlowKey.Attr.TCP);

        /** struct ovs_key_udp */
        public static final FlowKeyAttr<FlowKeyUDP> UDP =
            attr(OpenVSwitch.FlowKey.Attr.UDP);

        /** struct ovs_key_icmp */
        public static final FlowKeyAttr<FlowKeyICMP> ICMP =
            attr(OpenVSwitch.FlowKey.Attr.ICMP);

        /** struct ovs_key_icmpv6 */
        public static final FlowKeyAttr<FlowKeyICMPv6> ICMPv6 =
            attr(OpenVSwitch.FlowKey.Attr.ICMPv6);

        /** struct ovs_key_arp */
        public static final FlowKeyAttr<FlowKeyARP> ARP =
            attr(OpenVSwitch.FlowKey.Attr.ARP);

        /** struct ovs_key_nd */
        public static final FlowKeyAttr<FlowKeyND> ND =
            attr(OpenVSwitch.FlowKey.Attr.ND);

        /** struct ovs_key_ipv4_tunnel */
        public static final FlowKeyAttr<FlowKeyTunnel> TUNNEL =
            attrNest(OpenVSwitch.FlowKey.Attr.Tunnel);

        public FlowKeyAttr(int id) {
            super(id);
        }

        public FlowKeyAttr(int id, boolean nested) {
            super(id, nested);
        }

        static <T extends FlowKey<T>> FlowKeyAttr<T> attrNest(int id) {
            return new FlowKeyAttr<T>(id, true);
        }

        static <T extends FlowKey<T>> FlowKeyAttr<T> attr(int id) {
            return new FlowKeyAttr<T>(id, false);
        }
    }

    static NetlinkMessage.CustomBuilder<FlowKey<?>> Builder =
        new NetlinkMessage.CustomBuilder<FlowKey<?>>() {
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
}
