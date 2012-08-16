package com.midokura.midonet.functional_test.topology;

import java.nio.ByteBuffer;
import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midonet.functional_test.utils.Tap;
import com.midokura.packets.ARP;
import com.midokura.packets.IPv4;
import com.midokura.packets.LLDP;
import com.midokura.packets.MAC;
import static com.midokura.util.process.ProcessHelper.newProcess;


/**
 * Copyright 2011 Midokura Europe SARL
 * User: rossella rossella@midokura.com
 * Date: 12/9/11
 * Time: 1:03 PM
 */
public class TapWrapper {
    private final static Logger log = LoggerFactory.getLogger(TapWrapper.class);

    String name;
    MAC hwAddr;

    byte[] unreadBytes;
    int fd = -1;

    public TapWrapper(String name) {
        this(name, true);
    }

    public TapWrapper(String name, boolean create) {
        this.name = name;

        if (create) {
            // Create Tap
            newProcess(
                String.format("sudo -n ip tuntap add dev %s mode tap", name))
                .logOutput(log, "create_tap")
                .runAndWait();

            newProcess(
                String.format(
                    "sudo -n ip link set dev %s arp off multicast off up",
                    name))
                .logOutput(log, "create_tap")
                .runAndWait();
        }

        fd = Tap.openTap(name, true).fd;
    }

    public String getName() {
        return name;
    }

    public MAC getHwAddr() {
        return MAC.fromString(Tap.getHwAddress(this.name, fd));
    }

    public void setHwAddr(MAC hwAddr) {
        Tap.setHwAddress(fd, this.name, hwAddr.toString());
    }

    /*
     * A hack to allow the programatic close of the fd since while it is opened by the JVM you it can't be open by the KVM and the VM are failing.
     * @author mtoader@midokura.com
     */
    public void closeFd() {
        if (fd > 0) {
            Tap.closeFD(fd);
        }
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
                    // buf.position());
                    return null;
                }
                try {
                    log.debug("Sleeping for 100 millis.");
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    log.error("InterruptedException in recv()", e);
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
                } else if (totalSize > -1)
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
        bb.limit(size);
        bb.position(12);
        short etherType = bb.getShort();
        while (etherType == (short) 0x8100) {
            // Move past any vlan tags.
            if (bb.remaining() < 4)
                return -1;
            bb.getShort();
            etherType = bb.getShort();
        }
        // Now parse the payload.
        if (etherType == ARP.ETHERTYPE) {
            if (bb.remaining() < 6)
                return -1;
            bb.getInt();
            int hwLen = bb.get();
            int protoLen = bb.get();
            // The ARP includes a 2-octet Operation, 2 hw and 2 proto addrs.
            return bb.position() + 2 + (2 * hwLen) + (2 * protoLen);
        }
        if (etherType == LLDP.ETHERTYPE) {
            while(true) {
                if (bb.remaining() < 2)
                    return -1;
                // Get the TL of the TLV
                short tl = bb.getShort();
                // The end of the LLDP is marked by TLV of zero.
                if (0 == tl)
                    return bb.position();
                // Get the length of the TLV
                int length = tl & 0x1ff;
                if(bb.remaining() < length)
                    return -1;
                bb.position(length + bb.position());
            }
        }
        if (etherType != IPv4.ETHERTYPE) {
            log.debug("Unknown ethertype {}", String.format("%x", etherType));
            return -2;
        }
        if (bb.remaining() < 4)
            return -1;
        // Ignore the first 2 bytes of the IP header.
        bb.getShort();
        // Now read the total IP pkt length
        int totalLength = bb.getShort();
        // Compute the Ethernet frame length.
        return totalLength + bb.position() - 4;
    }

    public void down() {
        // TODO Auto-generated method stub

    }

    public void remove() {
        closeFd();

        newProcess(
            String.format("sudo -n ip tuntap del dev %s mode tap", getName()))
            .logOutput(log, "remove_tap@" + getName())
            .runAndWait();

    }
}
