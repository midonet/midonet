/*
 * Copyright 2011 Midokura KK 
 */

package com.midokura.midolman.packets;

import java.nio.ByteBuffer;

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
        if (dataOffset > 5) {
            options = new byte[(dataOffset-5)*4];
            bb.get(options);
        }
        payload = new Data();
        payload.deserialize(data, bb.position(), bb.limit() - bb.position());
        payload.setParent(this);
        return this;
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
