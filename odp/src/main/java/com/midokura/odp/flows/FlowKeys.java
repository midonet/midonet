/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.odp.flows;

/**
 * Builder class to allow easier building of FlowKey instances.
 */
public class FlowKeys {

    public static FlowKeyInPort inPort(int portNumber) {
        return new FlowKeyInPort().setInPort(portNumber);
    }

    public static FlowKeyEthernet ethernet(byte[] src, byte[] dst) {
        return new FlowKeyEthernet().setSrc(src).setDst(dst);
    }

    public static FlowKeyEtherType etherType(int value) {
        return new FlowKeyEtherType().setEtherType((short) value);
    }

    public static FlowKeyEtherType etherType(FlowKeyEtherType.Type type) {
        return etherType(type.value);
    }

    public static FlowKeyIPv4 ipv4(int src, int dst, IpProtocol protocol) {
        return ipv4(src, dst, protocol.value);
    }

    public static FlowKeyIPv4 ipv4(int src, int dst, int proto) {
        return
            new FlowKeyIPv4()
                .setSrc(src)
                .setDst(dst)
                .setProto((byte) proto);
    }

    public static FlowKeyIPv6 ipv6(int[] src, int dst[], int proto) {
        return
            new FlowKeyIPv6()
                .setSrc(src)
                .setDst(dst)
                .setProto((byte) proto);
    }

    public static FlowKeyIPv6 ipv6(int[] src, int dst[], IpProtocol protocol) {
        return ipv6(src, dst, protocol.value);
    }

    public static FlowKeyICMP icmp(int type, int code) {
        return new FlowKeyICMP()
            .setType((byte) type)
            .setCode((byte) code);
    }

    public static FlowKeyICMPv6 icmpv6(int type, int code) {
        return new FlowKeyICMPv6()
            .setType((byte) type)
            .setCode((byte) code);
    }

    public static FlowKeyUDP udp(int src, int dst) {
        return new FlowKeyUDP().setUdpSrc(src).setUdpDst(dst);
    }

    public static FlowKeyARP arp(byte[] sourceAddress, byte[] targetAddress) {
        return
            new FlowKeyARP()
                .setSourceAddress(sourceAddress)
                .setTargetAddress(targetAddress);
    }

    public static FlowKeyTCP tcp(int src, int dst) {
        return new FlowKeyTCP().setSrc(src).setDst(dst);
    }

    public static FlowKeyND neighborDiscovery(int[] target) {
        return new FlowKeyND().setTarget(target);
    }

    public static FlowKeyTunnelID tunnelID(long tunnelId) {
        return new FlowKeyTunnelID().setTunnelID(tunnelId);
    }

    public static FlowKeyPriority priority(int priority) {
        return new FlowKeyPriority().setPriority(priority);
    }

    public static FlowKeyVLAN vlan(int vlan) {
        return new FlowKeyVLAN().setVLAN((short)vlan);
    }

    public static FlowKeyEncap encap() {
        return new FlowKeyEncap();
    }
}
