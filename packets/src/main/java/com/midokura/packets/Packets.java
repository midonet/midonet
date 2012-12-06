/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.packets;

public class Packets {
    public static Ethernet udp(MAC dlSrc, MAC dlDst,
                               IntIPv4 nwSrc, IntIPv4 nwDst,
                               short sport, short dport, byte[] data) {
        UDP udp = new UDP();
        udp.setDestinationPort(dport);
        udp.setSourcePort(sport);
        udp.setPayload(new Data(data));
        IPv4 ip = new IPv4();
        ip.setDestinationAddress(nwDst.getAddress());
        ip.setSourceAddress(nwSrc.getAddress());
        ip.setProtocol(UDP.PROTOCOL_NUMBER);
        ip.setPayload(udp);
        Ethernet eth = new Ethernet();
        eth.setDestinationMACAddress(dlDst);
        eth.setSourceMACAddress(dlSrc);
        eth.setEtherType(IPv4.ETHERTYPE);
        eth.setPayload(ip);
        return eth;
    }

    public static Ethernet arpRequest(MAC dlSrc, IntIPv4 nwSrc,
                                      IntIPv4 targetAddr) {
        ARP arp = new ARP();
        arp.setHardwareType(ARP.HW_TYPE_ETHERNET);
        arp.setProtocolType(ARP.PROTO_TYPE_IP);
        arp.setHardwareAddressLength((byte)0x06);
        arp.setProtocolAddressLength((byte)0x04);
        arp.setOpCode(ARP.OP_REQUEST);
        arp.setSenderHardwareAddress(dlSrc);
        arp.setSenderProtocolAddress(IPv4.toIPv4AddressBytes(nwSrc.addressAsInt()));
        arp.setTargetProtocolAddress(IPv4.toIPv4AddressBytes(targetAddr.addressAsInt()));
        arp.setTargetHardwareAddress(MAC.fromString("ff:ff:ff:ff:ff:ff"));
        Ethernet eth = new Ethernet();
        eth.setDestinationMACAddress(MAC.fromString("ff:ff:ff:ff:ff:ff"));
        eth.setSourceMACAddress(dlSrc);
        eth.setEtherType(ARP.ETHERTYPE);
        eth.setPayload(arp);
        return eth;
    }
}
