/*
 * Copyright 2012 Midokura Europe SARL
 */

package org.midonet.packets;

public class Packets {

    public static Ethernet udp(MAC dlSrc, MAC dlDst,
                               IPv4Addr nwSrc, IPv4Addr nwDst,
                               short sport, short dport, byte[] data) {
        UDP udp = new UDP();
        udp.setDestinationPort(dport);
        udp.setSourcePort(sport);
        udp.setPayload(new Data(data));
        IPv4 ip = new IPv4();
        ip.setDestinationAddress(nwDst.addr());
        ip.setSourceAddress(nwSrc.addr());
        ip.setProtocol(UDP.PROTOCOL_NUMBER);
        ip.setPayload(udp);
        Ethernet eth = new Ethernet();
        eth.setDestinationMACAddress(dlDst);
        eth.setSourceMACAddress(dlSrc);
        eth.setEtherType(IPv4.ETHERTYPE);
        eth.setPayload(ip);
        return eth;
    }

    public static Ethernet arpRequest(MAC dlSrc, IPv4Addr nwSrc,
                                      IPv4Addr targetAddr) {
        ARP arp = new ARP();
        arp.setHardwareType(ARP.HW_TYPE_ETHERNET);
        arp.setProtocolType(ARP.PROTO_TYPE_IP);
        arp.setHardwareAddressLength((byte)0x06);
        arp.setProtocolAddressLength((byte)0x04);
        arp.setOpCode(ARP.OP_REQUEST);
        arp.setSenderHardwareAddress(dlSrc);
        arp.setSenderProtocolAddress(IPv4.toIPv4AddressBytes(nwSrc.addr()));
        arp.setTargetProtocolAddress(IPv4.toIPv4AddressBytes(targetAddr.addr()));
        arp.setTargetHardwareAddress(MAC.fromString("ff:ff:ff:ff:ff:ff"));
        Ethernet eth = new Ethernet();
        eth.setDestinationMACAddress(MAC.fromString("ff:ff:ff:ff:ff:ff"));
        eth.setSourceMACAddress(dlSrc);
        eth.setEtherType(ARP.ETHERTYPE);
        eth.setPayload(arp);
        return eth;
    }

    public static byte[] bpdu(MAC srcMac, MAC dstMac, byte type, byte flags,
                              long rootBridgeId, int rootPathCost,
                              long senderBridgeId, short portId,
                              short msgAge, short maxAge, short helloTime,
                              short fwdDelay) {
        BPDU bpdu = new BPDU();
        bpdu.setBpduMsgType(type)
            .setFlags(flags)
            .setRootBridgeId(rootBridgeId)
            .setRootPathCost(rootPathCost)
            .setSenderBridgeId(senderBridgeId)
            .setPortId(portId)
            .setMsgAge(msgAge)
            .setMaxAge(maxAge)
            .setHelloTime(helloTime)
            .setFwdDelay(fwdDelay);
        Ethernet frame = new Ethernet();
        frame.setEtherType(BPDU.ETHERTYPE);
        frame.setPayload(bpdu);
        frame.setSourceMACAddress(srcMac);
        frame.setDestinationMACAddress(dstMac);
        return frame.serialize();
    }


}
