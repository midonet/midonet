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
}
