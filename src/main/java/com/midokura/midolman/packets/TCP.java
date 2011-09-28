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
    protected short windowSize;
    protected short checksum;
    protected short urgent;

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
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public IPacket deserialize(byte[] data, int offset, int length) {
        ByteBuffer bb = ByteBuffer.wrap(data, offset, length);
        sourcePort = bb.getShort();
        destinationPort = bb.getShort();
        seqNo = bb.getInt();
        ackNo = bb.getInt();
        bb.getShort(); //TODO: parse flags
        windowSize = bb.getShort();
        checksum = bb.getShort();
        urgent = bb.getShort();
        //TODO: verify checksum
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
