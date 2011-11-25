/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.smoketest.topology;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.packets.Ethernet;
import com.midokura.midolman.packets.ARP;
import com.midokura.midolman.packets.ICMP;
import com.midokura.midolman.packets.IPv4;
import com.midokura.midolman.packets.MAC;
import com.midokura.midolman.util.Net;
import com.midokura.midonet.smoketest.mgmt.DtoMaterializedRouterPort;
import com.midokura.midonet.smoketest.mocks.MidolmanMgmt;
import com.midokura.midonet.smoketest.utils.Tap;

public class TapPort extends Port {

    private final static Logger log = LoggerFactory.getLogger(TapPort.class);

    MAC hwAddr;
    MAC outerMac;
    static Random rand = new Random();
    byte[] unreadBytes;
    int fd = -1;

    TapPort(MidolmanMgmt mgmt, DtoMaterializedRouterPort port, String name) {
        super(mgmt, port, name);
        // Create a random MAC address that will be used as hw_src for packets
        // written to the underlying tap.
        byte[] hw_bytes = new byte[6];
        rand.nextBytes(hw_bytes);
        hw_bytes[0] = (byte)02;
        hwAddr = new MAC(hw_bytes);
        outerMac = MAC.fromString(Tap.getHwAddress(this.name));
        fd = Tap.openTap(name, true);
    }

    /*
     * A hack to allow the programatic close of the fd since while it is opened by the JVM you it can't be open by the KVM and the VM are failing.
     * @author mtoader@midokura.com
     */
    public void closeFd() {
        if ( fd > 0 ) {
            Tap.closeFD(fd);
        }
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
        ethReq.setDestinationMACAddress(outerMac);
        MAC senderMac = MAC.fromString("ab:cd:ef:01:23:45");
        ethReq.setSourceMACAddress(senderMac);
        byte[] pktData = ethReq.serialize();
        Tap.writeToTap(fd, pktData, pktData.length);
    }

    public boolean send(byte[] pktBytes) {
        Tap.writeToTap(this.fd, pktBytes, pktBytes.length);
        return true;
    }

    public byte[] recv() {
        long maxSleepMillis = 2000;
        long timeSlept = 0;
        // Max pkt size = 14 (Ethernet) + 1500 (MTU) - 20 GRE = 1492
        byte[] data = new byte[1492];
        byte[] tmp = new byte[1492];
        ByteBuffer buf = ByteBuffer.wrap(data);
        int totalSize = -1;
        if (null != unreadBytes) {
            buf.put(unreadBytes);
            unreadBytes = null;
            totalSize = getTotalPacketSize(data, buf.position());
        }
        while (true) {
            int numRead = Tap.readFromTap(this.fd, tmp, 1492 - buf.position());
            if (numRead > 0)
            	log.debug("Got {} bytes reading from tap.", numRead);
            if (0 == numRead) {
                if (timeSlept >= maxSleepMillis) {
                	//log.debug("Returning null after receiving {} bytes",
                	//		buf.position());
                    return null;
                }
                try {
                	log.debug("Sleeping for 100 millis.");
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    log.error("InterrupException in recv()", e);
                }
                timeSlept += 100;
                continue;
            }
            buf.put(tmp, 0, numRead);
            if (totalSize < 0) {
                totalSize = getTotalPacketSize(data, buf.position());
                if (totalSize == -2) {
                	log.warn("Got a non-IPv4 packet. Discarding.");
                	totalSize = -1;
                	buf.position(0);
                	continue;
                }
                else if (totalSize > -1)
                	log.debug("The packet has size {}", totalSize);
            }
            // break out of the loop if you've read at least one full packet.
            if (totalSize > 0 && totalSize <= buf.position())
                break;
        }
        if (buf.position() > totalSize) {
            unreadBytes = Arrays.copyOfRange(data, totalSize, buf.position());
            log.debug("Saving {} unread bytes for next packet recv call.",
            		unreadBytes.length);
        }
        return Arrays.copyOf(data, totalSize);
    }

    private int getTotalPacketSize(byte[] pktBytes, int size) {
    	log.debug("computing total size, currently have {} bytes", size);
        if (size < 14)
            return -1;
        ByteBuffer bb = ByteBuffer.wrap(pktBytes);
        bb.position(12);
        short etherType = bb.getShort();
        while (etherType == (short) 0x8100) {
            // Move past any vlan tags.
            if (size - bb.position() < 4)
                return -1;
            bb.getShort();
            etherType = bb.getShort();
        }
        // Now parse the payload.
        if (etherType == ARP.ETHERTYPE) {
        	bb.getInt();
        	int hwLen = bb.get();
        	int protoLen = bb.get();
        	bb.getShort();
        	return bb.position() + 2*(hwLen + protoLen);
        }
        if (etherType != IPv4.ETHERTYPE)
        	return -2;
            //throw new RuntimeException("Received non-IPv4 packet");
        if (size - bb.position() < 4)
            return -1;
        // Ignore the first 2 bytes of the IP header.
        bb.getShort();
        // Now read the total IP pkt length
        int totalLength = bb.getShort(); 
        // Compute the Ethernet frame length.
        return totalLength + bb.position() - 4;
    }

    public MAC getInnerMAC() {
        return hwAddr;
    }

    public MAC getOuterMAC() {
        return outerMac;
    }
}
