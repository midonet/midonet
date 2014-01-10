/*
 * Copyright (c) 2012 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.odp.flows;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.messages.BuilderAware;

public interface FlowKey<Key extends FlowKey<Key>> extends BuilderAware, NetlinkMessage.Attr<Key> {

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

    public static class FlowKeyAttr<T extends FlowKey> extends
                                                NetlinkMessage.AttrKey<T> {

        /** Nested set of encapsulated attributes. */
        public static final FlowKeyAttr<FlowKeyEncap> ENCAP = attr(1);

        /** u32 skb->priority */
        public static final FlowKeyAttr<FlowKeyPriority> PRIORITY = attr(2);

        /** u32 OVS dp port number */
        public static final FlowKeyAttr<FlowKeyInPort> IN_PORT = attr(3);

        /** struct ovs_key_ethernet */
        public static final FlowKeyAttr<FlowKeyEthernet> ETHERNET = attr(4);

        /** be16 VLAN TCI */
        public static final FlowKeyAttr<FlowKeyVLAN> VLAN = attr(5);

        /** be16 Ethernet type */
        public static final FlowKeyAttr<FlowKeyEtherType> ETHERTYPE = attr(6);

        /** struct ovs_key_ipv4 */
        public static final FlowKeyAttr<FlowKeyIPv4> IPv4 = attr(7);

        /** struct ovs_key_ipv6 */
        public static final FlowKeyAttr<FlowKeyIPv6> IPv6 = attr(8);

        /** struct ovs_key_tcp */
        public static final FlowKeyAttr<FlowKeyTCP> TCP = attr(9);

        /** struct ovs_key_udp */
        public static final FlowKeyAttr<FlowKeyUDP> UDP = attr(10);

        /** struct ovs_key_icmp */
        public static final FlowKeyAttr<FlowKeyICMP> ICMP = attr(11);

        /** struct ovs_key_icmpv6 */
        public static final FlowKeyAttr<FlowKeyICMPv6> ICMPv6 = attr(12);

        /** struct ovs_key_arp */
        public static final FlowKeyAttr<FlowKeyARP> ARP = attr(13);

        /** struct ovs_key_nd */
        public static final FlowKeyAttr<FlowKeyND> ND = attr(14);

        /** struct ovs_key_ipv4_tunnel */
        public static final FlowKeyAttr<FlowKeyTunnel> TUNNEL = attrNest(16);

        /** be64 tunnel ID */
        public static final FlowKeyAttr<FlowKeyTunnelID> TUN_ID = attr(63);

        public FlowKeyAttr(int id) {
            super(id);
        }

        public FlowKeyAttr(int id, boolean nested) {
            super(id, nested);
        }

        static <T extends FlowKey> FlowKeyAttr<T> attrNest(int id) {
            return new FlowKeyAttr<T>(id, true);
        }

        static <T extends FlowKey> FlowKeyAttr<T> attr(int id) {
            return new FlowKeyAttr<T>(id, false);
        }
    }

    static NetlinkMessage.CustomBuilder<FlowKey<?>> Builder = new NetlinkMessage.CustomBuilder<FlowKey<?>>() {
        @Override
        public FlowKey newInstance(short type) {
            switch (type) {
                case 1: return new FlowKeyEncap();
                case 2: return new FlowKeyPriority();
                case 3: return new FlowKeyInPort();
                case 4: return new FlowKeyEthernet();
                case 5: return new FlowKeyVLAN();
                case 6: return new FlowKeyEtherType();
                case 7: return new FlowKeyIPv4();
                case 8: return new FlowKeyIPv6();
                case 9: return new FlowKeyTCP();
                case 10: return new FlowKeyUDP();
                case 11: return new FlowKeyICMP();
                case 12: return new FlowKeyICMPv6();
                case 13: return new FlowKeyARP();
                case 14: return new FlowKeyND();
                case 16: return new FlowKeyTunnel();
                case 63: return new FlowKeyTunnelID();
                default: return null;
            }
        }
    };
}
