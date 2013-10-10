/*
 * Copyright 2011 Midokura KK
 */

package org.midonet.packets;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TCP extends BasePacket implements Transport {

    public enum Flag {

        Fin(0, "FIN"),
        Syn(1, "SYN"),
        Rst(2, "RST"),
        Psh(3, "PSH"),
        Ack(4, "ACK"),
        Urg(5, "URG"),
        Ece(6, "ECE"),
        Cwr(7, "CWR");

        public final int position;
        public final int bit;
        public final String name;

        Flag(int position, String name) {
            this.position = position;
            this.bit = 1 << position;
            this.name = name;
        }

        @Override public String toString() { return this.name; }

        public static List<Flag> allOf(int b) {
            List<Flag> flags = new ArrayList<>();
            for (Flag f : Flag.values()) {
                if ((f.bit & b) != 0) { flags.add(f);}
            }
            return flags;
        }

    }

    public static final byte PROTOCOL_NUMBER = 6;

    public static final int MIN_HEADER_LEN = 20;
    public static final int MIN_DATA_OFFSET = 5;

    protected int sourcePort;
    protected int destinationPort;
    protected int seqNo;
    protected int ackNo;
    protected short flags;
    protected short windowSize;
    protected short checksum;
    protected short urgent;
    byte[] options;

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("TCP [sport=").append(sourcePort);
        sb.append(", dport=").append(destinationPort);
        sb.append(", seqNo=").append(seqNo);
        sb.append(", ackNo=").append(ackNo);
        sb.append(", cksum=").append(checksum);
        sb.append("]");
        return sb.toString();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (!super.equals(obj))
            return false;
        if (!(obj instanceof TCP))
            return false;
        TCP other = (TCP) obj;
        if (sourcePort != other.sourcePort)
            return false;
        if (destinationPort != other.destinationPort)
            return false;
        if (seqNo != other.seqNo)
            return false;
        if (ackNo != other.ackNo)
            return false;
        if (flags != other.flags)
            return false;
        if (windowSize != other.windowSize)
            return false;
        if (checksum != other.checksum)
            return false;
        if (urgent != other.urgent)
            return false;
        return Arrays.equals(options, other.options);
    }

    @Override
    public byte[] serialize() {
        byte[] payloadData = null;
        if (payload != null) {
            payload.setParent(this);
            payloadData = payload.serialize();
        }
        int dataOffsetBytes = 20 + (null == options ? 0 : options.length);
        int length = dataOffsetBytes
                + (null == payloadData ? 0 : payloadData.length);
        byte[] data = new byte[length];
        ByteBuffer bb = ByteBuffer.wrap(data);
        bb.putShort((short)sourcePort);
        bb.putShort((short)destinationPort);
        bb.putInt(seqNo);
        bb.putInt(ackNo);
        // Set dataOffset in flags if it hasn't already been set.
        if (0 == ((flags >> 12) & 0xf)) {
            int dataOffsetWords = dataOffsetBytes / 4;
            flags |= (dataOffsetWords & 0xf) << 12;
        }
        bb.putShort(flags);
        bb.putShort(windowSize);
        bb.putShort(checksum);
        bb.putShort(urgent);
        if (this.options != null)
            bb.put(this.options);
        if (payloadData != null)
            bb.put(payloadData);
        return data;
    }

    @Override
    public IPacket deserialize(ByteBuffer bb) throws MalformedPacketException {

        if (bb.remaining() < MIN_HEADER_LEN) {
            throw new MalformedPacketException("TCP packet size is invalid: "
                + bb.remaining());
        }

        sourcePort = Unsigned.unsign(bb.getShort());
        destinationPort = Unsigned.unsign(bb.getShort());
        seqNo = bb.getInt();
        ackNo = bb.getInt();
        flags = bb.getShort(); //TODO: parse flags
        windowSize = bb.getShort();
        checksum = bb.getShort();
        urgent = bb.getShort();
        //TODO: verify checksum
        int dataOffset = (flags >> 12) & 0xf;
        if (dataOffset < MIN_DATA_OFFSET) {
            throw new MalformedPacketException("TCP data offset is invalid: "
                    + dataOffset);
        }

        if (dataOffset > MIN_DATA_OFFSET) {
            int optionsLength = (dataOffset - MIN_DATA_OFFSET) * 4;
            if (optionsLength > bb.remaining()) {
                throw new MalformedPacketException("Packet size left "
                        + bb.remaining()
                        + " does not match the specified data offset: "
                        + dataOffset);
            }
            options = new byte[optionsLength];
            bb.get(options);
        }

        if (bb.hasRemaining()) {
            payload = new Data();
            payload.deserialize(bb.slice());
            payload.setParent(this);
        }

        return this;
    }

    public int getSeqNo() {
        return seqNo;
    }

    public void setSeqNo(int seqNo) {
        this.seqNo = seqNo;
    }

    public int getAckNo() {
        return ackNo;
    }

    public void setAckNo(int ackNo) {
        this.ackNo = ackNo;
    }

    public short getFlags() {
        return flags;
    }

    public void setFlags(short flags) {
        this.flags = flags;
    }

    public short getWindowSize() {
        return windowSize;
    }

    public void setWindowSize(short windowSize) {
        this.windowSize = windowSize;
    }

    public short getChecksum() {
        return checksum;
    }

    public void setChecksum(short checksum) {
        this.checksum = checksum;
    }

    public short getUrgent() {
        return urgent;
    }

    public void setUrgent(short urgent) {
        this.urgent = urgent;
    }

    public byte[] getOptions() {
        return options;
    }

    public void setOptions(byte[] options) {
        this.options = options;
    }

    @Override
    public void setSourcePort(int sourcePort) {
        if (sourcePort < 0 || sourcePort > 65535)
            throw new IllegalArgumentException("TCP port out of range");

        this.sourcePort = sourcePort;
    }

    @Override
    public void setDestinationPort(int destinationPort) {
        if (destinationPort < 0 || destinationPort > 65535)
            throw new IllegalArgumentException("TCP port out of range");

        this.destinationPort = destinationPort;
    }

    @Override
    public int getSourcePort() {
        return sourcePort;
    }

    @Override
    public int getDestinationPort() {
        return destinationPort;
    }

    public Data getPayload() {
        return (Data) payload;
    }

    public static int getSourcePort(ByteBuffer bb) throws MalformedPacketException {
        Short srcPort = 0;
        try {
            srcPort = bb.getShort(0);
        } catch (Exception e) {
            throw new MalformedPacketException("Cannot read tpSrc, corrupted data");
        }
        return srcPort;
    }

    public static int getDestinationPort(ByteBuffer bb) throws MalformedPacketException {
        Short srcPort = 0;
        try {
            srcPort = bb.getShort(2);
        } catch (Exception e) {
            throw new MalformedPacketException("Cannot read tpSrc, corrupted data");
        }
        return srcPort;
    }

}
