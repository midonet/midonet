/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.odp.flows;

import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IPv6Addr;

import org.midonet.util.collection.WeakObjectPool;

/**
 * Builder class to allow easier building of FlowKey instances.
 */
public class FlowKeys {
    private static WeakObjectPool<FlowKey<?>> FLOW_KEYS_POOL =
        new WeakObjectPool<FlowKey<?>>();

    public static <T extends FlowKey<?>> T intern(T flowKey) {
        if (flowKey instanceof FlowKeyInPort ||
            flowKey instanceof FlowKeyEthernet ||
            flowKey instanceof FlowKeyEtherType ||
            flowKey instanceof FlowKeyND ||
            flowKey instanceof FlowKeyVLAN ||
            flowKey instanceof FlowKeyTunnelID ||
            flowKey instanceof FlowKeyARP ||
            flowKey instanceof FlowKeyTunnel)
            return (T) FLOW_KEYS_POOL.sharedRef(flowKey);
        else
            return flowKey;
    }

    public static FlowKeyInPort inPort(int portNumber) {
        return intern(new FlowKeyInPort().setInPort(portNumber));
    }

    public static FlowKeyEthernet ethernet(byte[] src, byte[] dst) {
        return intern(new FlowKeyEthernet().setSrc(src).setDst(dst));
    }

    public static FlowKeyEtherType etherType(int value) {
        return intern(new FlowKeyEtherType().setEtherType((short) value));
    }

    public static FlowKeyEtherType etherType(FlowKeyEtherType.Type type) {
        return etherType(type.value);
    }

    public static FlowKeyIPv4 ipv4(IPv4Addr src, IPv4Addr dst,
                                   IpProtocol protocol) {
        return ipv4(src, dst, protocol.value);
    }

    public static FlowKeyIPv4 ipv4(IPv4Addr src, IPv4Addr dst, int proto) {
        return
            intern(new FlowKeyIPv4()
                    .setSrc(src)
                    .setDst(dst)
                    .setProto((byte) proto));
    }

    public static FlowKeyIPv6 ipv6(IPv6Addr src, IPv6Addr dst, int proto) {
        return intern(new FlowKeyIPv6().setSrc(src)
                                .setDst(dst)
                                .setProto((byte) proto));
    }

    public static FlowKeyIPv6 ipv6(IPv6Addr src, IPv6Addr dst,
                                   IpProtocol protocol) {
        return ipv6(src, dst, protocol.value);
    }

    public static FlowKeyICMP icmp(int type, int code) {
        return intern(new FlowKeyICMP()
                .setType((byte) type)
                .setCode((byte) code));
    }

    public static FlowKeyICMPEcho icmpEcho(int type, int code,
                                           short id, short seq) {
        FlowKeyICMPEcho key = new FlowKeyICMPEcho();
        key.setType((byte) type).setCode((byte) code);
        return intern(key.setIdentifier(id).setSeq(seq));
    }

    public static FlowKeyICMPError icmpError(int type, int code, byte[] data) {
        FlowKeyICMPError key = new FlowKeyICMPError();
        key.setType((byte) type).setCode((byte) code);
        return intern(key.setIcmpData(data));
    }

    public static FlowKeyICMPv6 icmpv6(int type, int code) {
        return intern(new FlowKeyICMPv6()
                .setType((byte) type)
                .setCode((byte) code));
    }

    public static FlowKeyUDP udp(int src, int dst) {
        return intern(new FlowKeyUDP().setUdpSrc(src).setUdpDst(dst));
    }

    public static FlowKeyARP arp(byte[] sourceAddress, byte[] targetAddress) {
        return
            intern(new FlowKeyARP()
                .setSourceAddress(sourceAddress)
                .setTargetAddress(targetAddress));
    }

    public static FlowKeyTCP tcp(int src, int dst) {
        return intern(new FlowKeyTCP().setSrc(src).setDst(dst));
    }

    public static FlowKeyND neighborDiscovery(int[] target) {
        return intern(new FlowKeyND().setTarget(target));
    }

    public static FlowKeyTunnelID tunnelID(long tunnelId) {
        return intern(new FlowKeyTunnelID().setTunnelID(tunnelId));
    }

    public static FlowKeyPriority priority(int priority) {
        return intern(new FlowKeyPriority().setPriority(priority));
    }

    public static FlowKeyVLAN vlan(int vlan) {
        return intern(new FlowKeyVLAN().setVLAN((short)vlan));
    }

    public static FlowKeyEncap encap() {
        return intern(new FlowKeyEncap());
    }

    public static FlowKeyTunnel tunnel(long tunnelId, int Ipv4SrcAddr,
            int ipv4DstAddr) {
        // OVS kernel module requires the TTL field to be set. Here we set it to
        // the maximum possible value. We leave TOS and flags not set. We may
        // set the tunnel key flag, but it's not a required field. DONT FRAGMENT
        // and CHECKSUM are for the outer header. We leave those alone for now
        // as well. CHECKSUM defaults to zero in OVS kernel module. GRE header
        // checksum is not checked in gre_rcv, and it is also not required for
        // GRE packets.
        return intern(new FlowKeyTunnel().setTunnelID(tunnelId)
                .setIpv4SrcAddr(Ipv4SrcAddr)
                .setIpv4DstAddr(ipv4DstAddr))
                .setTtl((byte) 127);  // A maximum number.
    }
}
