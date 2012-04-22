/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midolman.packets;

import java.nio.ByteBuffer;

public class GRE extends BasePacket {
    public static final byte PROTOCOL_NUMBER = 47;

    static final int CKSUM_PRESENT = 0x80;
    static final int KEY_PRESENT = 0x20;
    static final int SEQNUM_PRESENT = 0x10;

    boolean hasCksum;
    boolean hasKey;
    boolean hasSeqnum;
    short cksum;
    int key;
    int seqNum;
    int version;
    short protocol;

    @Override
    public byte[] serialize() {
        byte[] payloadData = null;
        if (payload != null) {
            payload.setParent(this);
            payloadData = payload.serialize();
        }
        int length = 4 + ((payloadData == null) ? 0 : payloadData.length);
        byte tmp = 0;
        if (hasCksum) {
            tmp |= CKSUM_PRESENT;
            length += 4;
        }
        if (hasKey) {
            tmp |= KEY_PRESENT;
            length += 4;
        }
        if (hasSeqnum) {
            tmp |= SEQNUM_PRESENT;
            length += 4;
        }
        byte[] bytes = new byte[length];
        ByteBuffer bb = ByteBuffer.wrap(bytes);
        bb.put(tmp);
        bb.put((byte)(version & 0x7));
        bb.putShort(protocol);
        if (hasCksum) {
            bb.putShort(cksum);
            bb.putShort((short)0);
        }
        if (hasKey)
            bb.putInt(key);
        if (hasSeqnum)
            bb.putInt(seqNum);
        if (payloadData != null)
            bb.put(payloadData);
        return bytes;
    }

    @Override
    public IPacket deserialize(ByteBuffer bb) throws MalformedPacketException {
        byte tmp = bb.get();
        hasCksum = (tmp & CKSUM_PRESENT) != 0;
        hasKey = (tmp & KEY_PRESENT) != 0;
        hasSeqnum = (tmp & SEQNUM_PRESENT) != 0;
        tmp = bb.get();
        version = tmp & 0x07;
        protocol = bb.getShort();
        if (hasCksum) {
            cksum = bb.getShort();
            bb.getShort();
        }
        if (hasKey)
            key = bb.getInt();
        if (hasSeqnum)
            seqNum = bb.getInt();

        IPacket payload = new Ethernet();
        payload.deserialize(bb);
        this.payload = payload.deserialize(bb.slice());
        this.payload.setParent(this);
        return this;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("GRE [version=").append(version & 0x7);
        sb.append(", protocol=").append(protocol);
        sb.append("hasCksum=").append(hasCksum);
        if (hasCksum)
            sb.append(", cksum=").append(cksum);
        sb.append(", hasKey=").append(hasKey);
        if (hasKey)
            sb.append(", key=").append(key);
        sb.append(", hasSeqnum=").append(hasSeqnum);
        if (hasSeqnum)
            sb.append(", seqnum=").append(seqNum);
        sb.append(", payload=");
        sb.append(null == payload ? "null" : payload.toString());
        sb.append("]");
        return sb.toString();
    }
}
