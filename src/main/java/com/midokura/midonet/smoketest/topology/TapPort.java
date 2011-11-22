/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.smoketest.topology;

import java.util.Random;

import com.midokura.midolman.packets.Ethernet;
import com.midokura.midolman.packets.ICMP;
import com.midokura.midolman.packets.IPv4;
import com.midokura.midolman.packets.MAC;
import com.midokura.midolman.util.Net;
import com.midokura.midonet.smoketest.mgmt.DtoMaterializedRouterPort;
import com.midokura.midonet.smoketest.mocks.MidolmanMgmt;
import com.midokura.midonet.smoketest.utils.Tap;

public class TapPort extends Port {

    MAC hwAddr;
    static Random rand = new Random();

    TapPort(MidolmanMgmt mgmt, DtoMaterializedRouterPort port, String name) {
        super(mgmt, port, name);
        // Create a random MAC address that will be used as hw_src for packets
        // written to the underlying tap.
        byte[] hw_bytes = new byte[6];
        rand.nextBytes(hw_bytes);
        hwAddr = new MAC(hw_bytes);
    }

    public void sendICMP(String dstIp4) {
        ICMP icmpReq = new ICMP();
        short id = -12345;
        short seq = -20202;
        byte[] data = new byte[] { (byte) 0xaa, (byte) 0xbb, (byte) 0xcc,
                (byte) 0xdd, (byte) 0xee, (byte) 0xff };
        icmpReq.setEchoRequest(id, seq, data);
        IPv4 ipReq = new IPv4();
        ipReq.setPayload(icmpReq);
        ipReq.setProtocol(ICMP.PROTOCOL_NUMBER);
        // The ping can come from anywhere if one of the next hops is a
        int senderIP = Net.convertStringAddressToInt(port
                .getLocalNetworkAddress());
        ipReq.setSourceAddress(senderIP);
        ipReq.setDestinationAddress(Net.convertStringAddressToInt(dstIp4));
        Ethernet ethReq = new Ethernet();
        ethReq.setPayload(ipReq);
        ethReq.setEtherType(IPv4.ETHERTYPE);
        ethReq.setDestinationMACAddress(MAC.fromString(Tap.getHwAddress(name)));
        MAC senderMac = MAC.fromString("ab:cd:ef:01:23:45");
        ethReq.setSourceMACAddress(senderMac);
        byte[] pktData = ethReq.serialize();
        Tap.writeToTap(name, pktData, pktData.length);
    }

    public boolean send(byte[] request) {
        return false;
    }

    public byte[] recv() {
        return null;
    }

    public MAC getInnerMAC() {
        return hwAddr;
    }

    public MAC getOuterMAC() {
        return MAC.fromString(Tap.getHwAddress(this.name));
    }
}
