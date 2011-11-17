/*
 * Copyright 2011 Midokura KK 
 */

package com.midokura.midolman.packets;

import java.nio.ByteBuffer;
import java.util.Arrays;

public class TCP extends BasePacket implements Transport {
    public static final byte PROTOCOL_NUMBER = 6;
    
    protected short sourcePort;
    protected short destinationPort;
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
        bb.putShort(sourcePort);
        bb.putShort(destinationPort);
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
    public IPacket deserialize(byte[] data, int offset, int length) {
        ByteBuffer bb = ByteBuffer.wrap(data, offset, length);
        sourcePort = bb.getShort();
        destinationPort = bb.getShort();
        seqNo = bb.getInt();
        ackNo = bb.getInt();
        flags = bb.getShort(); //TODO: parse flags
        windowSize = bb.getShort();
        checksum = bb.getShort();
        urgent = bb.getShort();
        //TODO: verify checksum
        int dataOffset = (flags >> 12) & 0xf;
        int remainingBytes = bb.limit() - bb.position();
        if (dataOffset > 5) {
            int optionsLength = (dataOffset-5)*4;
            if (optionsLength > remainingBytes)
                options = new byte[remainingBytes];
            else
                options = new byte[optionsLength];
            bb.get(options);
        }
        remainingBytes = bb.limit() - bb.position();
        if (remainingBytes > 0) {
            payload = new Data();
            payload.deserialize(data, bb.position(), remainingBytes);
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

    public void setSourcePort(short sourcePort) {
        this.sourcePort = sourcePort;
    }

    public void setDestinationPort(short destinationPort) {
        this.destinationPort = destinationPort;
    }

    @Override
    public short getSourcePort() {
        return sourcePort;
    }

    @Override
    public short getDestinationPort() {
        return destinationPort;
    }

    public Data getPayload() {
        return (Data) payload;
    }

}
