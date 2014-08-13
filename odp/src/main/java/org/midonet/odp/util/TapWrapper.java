package org.midonet.odp.util;

import org.midonet.packets.ARP;
import org.midonet.packets.BPDU;
import org.midonet.packets.IPv4;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IPv4Subnet;
import org.midonet.packets.LLDP;
import org.midonet.packets.MAC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.midonet.util.process.ProcessHelper.newProcess;


/**
 * Copyright 2011 Midokura Europe SARL
 * User: rossella rossella@midokura.com
 * Date: 12/9/11
 * Time: 1:03 PM
 */
public class TapWrapper {
    private final static Logger log = LoggerFactory.getLogger(TapWrapper.class);
    private boolean up;

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
        up = true;
    }

    public String getName() {
        return name;
    }

    public MAC getHwAddr() {
        return MAC.fromString(Tap.getHwAddress(this.name, fd));
    }

    public void addNeighbour(IPv4Addr ip, MAC mac) {
        newProcess(
            String.format(
                "sudo -n ip neigh add %s lladdr %s dev %s",
                ip.toString(), mac.toString(), name))
            .logOutput(log, "create_tap")
            .runAndWait();
    }

    public void setIpAddress(IPv4Subnet subnet) {
        newProcess(
            String.format("sudo -n ip addr add %s/%d dev %s",
                          subnet.getAddress().toString(),
                          subnet.getPrefixLen(), name))
            .logOutput(log, "remote_host_mock")
            .runAndWait();
    }

    public void setHwAddr(MAC hwAddr) {
        Tap.setHwAddress(fd, this.name, hwAddr.toString());
    }

    /*
     * A hack to allow the programmatic close of the fd since while it is opened
     * by the JVM you it can't be open by the KVM and the VM are failing.
     * @author mtoader@midokura.com
     */
    public void closeFd() {
        if (fd > 0) {
            Tap.closeFD(fd);
        }
        up = false;
    }

    public boolean send(byte[] pktBytes) {
        if (up) {
            Tap.writeToTap(this.fd, pktBytes, pktBytes.length);
            return true;
        } else {
            return false;
        }
    }

    public byte[] recv() {
        return recv(2000);
    }

    public byte[] recv(long maxSleepMillis) {
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
                log.debug("Got {} bytes reading from tap {}.", numRead, name);
            if (0 == numRead) {
                if (timeSlept >= maxSleepMillis) {
                    //log.debug("Returning null after receiving {} bytes",
                    // buf.position());
                    return null;
                }
                try {
                    log.debug("Sleeping for 100 millis {}", name);
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
                    log.warn("Got a non-IPv4 packet at {}. Discarding.", name);
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
        if (etherType == BPDU.ETHERTYPE) {
            return bb.position() + BPDU.FRAME_SIZE;
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
        newProcess(
            String.format("sudo -n ip link set dev %s down", getName()))
            .logOutput(log, "down_tap@"+getName())
            .runAndWait();
        up = false;
    }

    public void up() {
        newProcess(
            String.format("sudo -n ip link set dev %s up", getName()))
            .logOutput(log, "up_tap@"+getName())
            .runAndWait();
        up = true;
    }


    public void remove() {
        closeFd();

        newProcess(
            String.format("sudo -n ip tuntap del dev %s mode tap", getName()))
            .logOutput(log, "remove_tap@" + getName())
            .runAndWait();
        up = false;

    }
}
