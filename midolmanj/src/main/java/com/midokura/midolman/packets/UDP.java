package com.midokura.midolman.packets;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 *
 * @author David Erickson (daviderickson@cs.stanford.edu)
 */
public class UDP extends BasePacket implements Transport {
    public static final byte PROTOCOL_NUMBER = 0x11;

    /**
     * UDP header length in bytes.
     */
    public static final int HEADER_LEN = 8;

    /**
     * Max len of a UDP packet.
     */
    public static final int MAX_PACKET_LEN = 0xFFFF;

    public static Map<Short, Class<? extends IPacket>> decodeMap;

    static {
        decodeMap = new HashMap<Short, Class<? extends IPacket>>();
        UDP.decodeMap.put((short)67, DHCP.class);
        UDP.decodeMap.put((short)68, DHCP.class);
    }

    protected short sourcePort;
    protected short destinationPort;
    protected int length;
    protected short checksum;

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("UDP [sport=").append(sourcePort);
        sb.append(", dport=").append(destinationPort);
        sb.append(", length=").append(length);
        sb.append(", cksum=").append(checksum);
        sb.append(", payload=").append(
                null == payload ? "null" : payload.toString());
        sb.append("]");
        return sb.toString();
    }

    /**
     * @return the sourcePort
     */
    public short getSourcePort() {
        return sourcePort;
    }

    /**
     * @param sourcePort the sourcePort to set
     */
    public UDP setSourcePort(short sourcePort) {
        this.sourcePort = sourcePort;
        return this;
    }

    /**
     * @return the destinationPort
     */
    public short getDestinationPort() {
        return destinationPort;
    }

    /**
     * @param destinationPort the destinationPort to set
     */
    public UDP setDestinationPort(short destinationPort) {
        this.destinationPort = destinationPort;
        return this;
    }

    /**
     * @return the length
     */
    public int getLength() {
        return length;
    }

    /**
     * @param length the length to set
     */
    public UDP setLength(int length) {
        if (length > 0xFFFF) {
            throw new IllegalArgumentException("Invalid length: " + length);
        }
        this.length = length;
        return this;
    }

    /**
     * @return the checksum
     */
    public short getChecksum() {
        return checksum;
    }

    /**
     * @param checksum the checksum to set
     */
    public UDP setChecksum(short checksum) {
        this.checksum = checksum;
        return this;
    }

    /**
     * Serializes the packet. Will compute and set the following fields if they
     * are set to specific values at the time serialize is called:
     *      -checksum : 0
     *      -length : 0
     */
    public byte[] serialize() {
        byte[] payloadData = null;
        if (payload != null) {
            payload.setParent(this);
            payloadData = payload.serialize();
        }

        if (this.length == 0) {
            this.length = (HEADER_LEN + ((payloadData == null) ? 0
                    : payloadData.length));
        }

        byte[] data = new byte[this.length & 0xffff];
        ByteBuffer bb = ByteBuffer.wrap(data);

        bb.putShort(this.sourcePort);
        bb.putShort(this.destinationPort);
        bb.putShort((short) this.length);
        bb.putShort(this.checksum);
        if (payloadData != null)
            bb.put(payloadData);

        if (this.parent != null && this.parent instanceof IPv4)
            ((IPv4)this.parent).setProtocol(UDP.PROTOCOL_NUMBER);

        // compute checksum if needed
        if (this.checksum == 0) {
            bb.rewind();
            int accumulation = 0;

            // compute pseudo header mac
            if (this.parent != null && this.parent instanceof IPv4) {
                IPv4 ipv4 = (IPv4) this.parent;
                accumulation += ((ipv4.getSourceAddress() >> 16) & 0xffff)
                        + (ipv4.getSourceAddress() & 0xffff);
                accumulation += ((ipv4.getDestinationAddress() >> 16) & 0xffff)
                        + (ipv4.getDestinationAddress() & 0xffff);
                accumulation += ipv4.getProtocol() & 0xff;
                accumulation += this.length & 0xffff;
            }

            for (int i = 0; i < this.length / 2; ++i) {
                accumulation += 0xffff & bb.getShort();
            }
            // pad to an even number of shorts
            if (this.length % 2 > 0) {
                accumulation += (bb.get() & 0xff) << 8;
            }

            accumulation = ((accumulation >> 16) & 0xffff)
                    + (accumulation & 0xffff);
            this.checksum = (short) (~accumulation & 0xffff);
            bb.putShort(6, this.checksum);
        }
        return data;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        final int prime = 5807;
        int result = super.hashCode();
        result = prime * result + checksum;
        result = prime * result + destinationPort;
        result = prime * result + length;
        result = prime * result + sourcePort;
        return result;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (!super.equals(obj))
            return false;
        if (!(obj instanceof UDP))
            return false;
        UDP other = (UDP) obj;
        if (checksum != other.checksum)
            return false;
        if (destinationPort != other.destinationPort)
            return false;
        if (length != other.length)
            return false;
        if (sourcePort != other.sourcePort)
            return false;
        return true;
    }

    @Override
    public IPacket deserialize(ByteBuffer bb) throws MalformedPacketException {

        if (bb.remaining() < HEADER_LEN || bb.remaining() > MAX_PACKET_LEN) {
            throw new MalformedPacketException("Invalid UDP length: "
                    + bb.remaining());
        }

        this.sourcePort = bb.getShort();
        this.destinationPort = bb.getShort();
        this.length = bb.getShort();
        this.checksum = bb.getShort();

        if (UDP.decodeMap.containsKey(this.destinationPort)) {
            try {
                payload = UDP.decodeMap.get(this.destinationPort).getConstructor().newInstance();
            } catch (Exception e) {
                throw new RuntimeException("Failure instantiating class", e);
            }
        } else if (UDP.decodeMap.containsKey(this.sourcePort)) {
            try {
                payload = UDP.decodeMap.get(this.sourcePort).getConstructor().newInstance();
            } catch (Exception e) {
                throw new RuntimeException("Failure instantiating class", e);
            }
        } else {
            payload = new Data();
        }

        int len = this.length & 0xffff;
        int payloadLen = len - HEADER_LEN;
        if (bb.remaining() > payloadLen) {
            bb.limit(len);
        }

        payload.deserialize(bb.slice());
        payload.setParent(this);
        return this;
    }

}
