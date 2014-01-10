/*
 * Copyright (c) 2012 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.odp.flows;

import java.util.List;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.odp.family.FlowFamily;
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
        return intern(new FlowKeyInPort(portNumber));
    }

    public static FlowKeyEthernet ethernet(byte[] src, byte[] dst) {
        return intern(new FlowKeyEthernet(src, dst));
    }

    public static FlowKeyEtherType etherType(short value) {
        return intern(new FlowKeyEtherType(value));
    }

    public static FlowKeyEtherType etherType(FlowKeyEtherType.Type type) {
        return etherType((short)type.value);
    }

    public static FlowKeyIPv4 ipv4(IPv4Addr src, IPv4Addr dst,
                                   IpProtocol protocol) {
        return ipv4(src, dst, protocol.value, (byte)0, (byte)0, IPFragmentType.None);
    }

    public static FlowKeyIPv4 ipv4(IPv4Addr src, IPv4Addr dst, byte protocol,
                                   byte typeOfService, byte ttl,
                                   IPFragmentType fragmentType) {
        return intern(new FlowKeyIPv4(src.toInt(), dst.toInt(), protocol,
                                      typeOfService, ttl, fragmentType.value));
    }

    public static FlowKeyIPv6 ipv6(IPv6Addr src, IPv6Addr dst,
                                   IpProtocol protocol) {
        return ipv6(src, dst, protocol.value, (byte)0, IPFragmentType.None);
    }

    public static FlowKeyIPv6 ipv6(IPv6Addr src, IPv6Addr dst,
                                   byte protocol) {
        return ipv6(src, dst, protocol, (byte)0, IPFragmentType.None);
    }

    public static FlowKeyIPv6 ipv6(IPv6Addr src, IPv6Addr dst, byte protocol,
                                   byte hlimit, IPFragmentType fragmentType) {
        return intern(new FlowKeyIPv6(src, dst, protocol,
                                      hlimit, fragmentType.value));
    }

    public static FlowKeyICMP icmp(byte type, byte code) {
        return intern(new FlowKeyICMP(type, code));
    }

    public static FlowKeyICMPEcho icmpEcho(byte type, byte code, short id) {
        return intern(new FlowKeyICMPEcho(type, code, id));
    }

    public static FlowKeyICMPError icmpError(byte type, byte code, byte[] data) {
        return intern(new FlowKeyICMPError(type, code, data));
    }

    public static FlowKeyICMPv6 icmpv6(int type, int code) {
        return intern(new FlowKeyICMPv6((byte) type, (byte) code));
    }

    public static FlowKeyUDP udp(int src, int dst) {
        return intern(new FlowKeyUDP(src, dst));
    }

    public static FlowKeyARP arp(byte[] sourceAddress, byte[] targetAddress,
                                 short opcode, int sourceIp, int targetIp) {
        return
            intern(new FlowKeyARP(sourceAddress, targetAddress, opcode,
                                  sourceIp, targetIp));
    }

    public static FlowKeyTCP tcp(int src, int dst) {
        return intern(new FlowKeyTCP(src, dst));
    }

    public static FlowKeyND neighborDiscovery(int[] target) {
        return intern(new FlowKeyND(target));
    }

    public static FlowKeyTunnelID tunnelID(long tunnelId) {
        return intern(new FlowKeyTunnelID(tunnelId));
    }

    public static FlowKeyPriority priority(int priority) {
        return intern(new FlowKeyPriority(priority));
    }

    public static FlowKeyVLAN vlan(short vlan) {
        return intern(new FlowKeyVLAN(vlan));
    }

    public static FlowKeyEncap encap(List<FlowKey<?>> keys) {
        return intern(new FlowKeyEncap(keys));
    }

    public static FlowKeyTunnel tunnel(long tunnelId, int Ipv4SrcAddr,
            int ipv4DstAddr) {
        return intern(new FlowKeyTunnel(tunnelId, Ipv4SrcAddr, ipv4DstAddr));
    }

    public static List<FlowKey<?>> buildFrom(NetlinkMessage msg) {
        return msg.getAttrValue(FlowFamily.AttrKey.KEY, FlowKey.Builder);
    }
}
