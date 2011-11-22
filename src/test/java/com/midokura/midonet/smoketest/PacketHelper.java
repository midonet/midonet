/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.smoketest;

import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midolman.packets.MAC;

public class PacketHelper {

    MAC hwAddr;
    IntIPv4 ipAddr;

    public PacketHelper(MAC hwAddr, String ipv4) {
        this.hwAddr = hwAddr;
        this.ipAddr = IntIPv4.fromString(ipv4);
    }

    public byte[] makeDhcpDiscover() {
        // TODO Auto-generated method stub
        return null;
    }

    public void checkDhcpOffer(byte[] request, byte[] reply, String ipAddr) {
        // TODO Auto-generated method stub
        
    }

    public byte[] makeDhcpRequest(byte[] reply) {
        // TODO Auto-generated method stub
        return null;
    }

    public void checkDhcpAck(byte[] request, byte[] reply) {
        // TODO Auto-generated method stub
        
    }

    public byte[] makeArpRequest(IntIPv4 rtr_ip) {
        // TODO Auto-generated method stub
        return null;
    }

    public void checkArpReply(byte[] request, byte[] reply, MAC mac) {
        // TODO Auto-generated method stub
        
    }

    public byte[] makeIcmpEchoRequest(MAC rtr_mac, IntIPv4 rtr_ip) {
        // TODO Auto-generated method stub
        return null;
    }

    public void checkIcmpEchoReply(byte[] request, byte[] reply) {
        // TODO Auto-generated method stub
        
    }

    public void checkArpRequest(byte[] arp, MAC rtr_mac, IntIPv4 rtr_ip) {
        // TODO Auto-generated method stub
        
    }

    public byte[] makeArpReply(MAC rtr_mac, IntIPv4 rtr_ip) {
        // TODO Auto-generated method stub
        return null;
    }

}
