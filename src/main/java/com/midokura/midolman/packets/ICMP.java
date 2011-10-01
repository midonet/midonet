/*
 * Copyright 2011 Midokura KK 
 */
package com.midokura.midolman.packets;

import java.nio.ByteBuffer;
import java.util.Arrays;

public class ICMP extends BasePacket {
    public static final byte PROTOCOL_NUMBER = 1;

    public static final char CODE_NONE = 0;

    public static final char TYPE_UNREACH = 3;
    public static enum UNREACH_CODE {
        UNREACH_NET((char)0), UNREACH_HOST((char)1), 
        UNREACH_FILTER_PROHIB((char)13);

        private final char value;
        private UNREACH_CODE(char value) { this.value = value; }
        public char toChar() { return value; }
    }

    public static final char TYPE_ECHO_REPLY = 0;
    public static final char TYPE_ECHO_REQUEST = 8;
    public static final char TYPE_SOURCE_QUENCH = 4;
    public static final char TYPE_REDIRECT = 5;
    public static final char TYPE_TIME_EXCEEDED = 11;
    public static final char TYPE_PARAMETER_PROBLEM = 12;
    
    // Types

    char type;
    char code;
    short checksum;
    int quench;
    byte[] data;

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("ICMP [type=").append((int)type);
        sb.append(", code=").append((int)code);
        sb.append(", cksum=").append(checksum);
        sb.append(", quench=").append(quench);
        sb.append("]");
        return sb.toString();
    }

    public boolean isError() {
        return TYPE_UNREACH == code || TYPE_SOURCE_QUENCH == code ||
                TYPE_REDIRECT == code || TYPE_TIME_EXCEEDED == code ||
                TYPE_PARAMETER_PROBLEM == code;
    }

    public char getType() {
        return type;
    }

    public char getCode() {
        return code;
    }

    public short getChecksum() {
        return checksum;
    }

    public int getQuench() {
        return quench;
    }

    public final byte[] getData() {
        return data;
    }

    public void setUnreachable(UNREACH_CODE unreachCode, IPv4 ipPkt) {
        type = TYPE_UNREACH;
        code = unreachCode.value;
        checksum = 0;
        quench = 0;
        byte[] data = ipPkt.serialize();
        int length = ipPkt.headerLength*4 + 8;
        if (length >= data.length)
            this.data = data;
        else
            this.data = Arrays.copyOf(data, length);
    }

    public void setEchoRequest(short id, short seq, byte[] data) {
        type = TYPE_ECHO_REQUEST;
        code = CODE_NONE;
        checksum = 0;
        // TODO: check on the byte order for quench.
        quench = id << 16 | seq & 0xffff;
        this.data = data;
    }

    public void setEchoReply(short id, short seq, byte[] data) {
        type = TYPE_ECHO_REPLY;
        code = CODE_NONE;
        checksum = 0;
        // TODO: check on the byte order for quench.
        quench = id << 16 | seq & 0xffff;
        this.data = data;
    }

    /**
     * Serializes the packet. Will compute and set the checksum if it's set to
     * 0 (zero) at the time serialize is called.
     */
    @Override
    public byte[] serialize() {
        int length = 8 + ((data == null) ? 0 : data.length);
        byte[] bytes = new byte[length];
        ByteBuffer bb = ByteBuffer.wrap(bytes);
        bb.put((byte)type);
        bb.put((byte)code);
        bb.putShort(checksum);
        bb.putInt(quench);

        if (null != data)
            bb.put(data);

        // ICMP checksum is calculated on header+data, with checksum=0.
        if (checksum == 0) {
            checksum = IPv4.computeChecksum(bb, bb.position(), 2);
            bb.putShort(2, this.checksum);
        }
        return bytes;
    }

    @Override
    public IPacket deserialize(byte[] data, int offset, int length) {
        ByteBuffer bb = ByteBuffer.wrap(data, offset, length);
        type = (char)bb.get();
        code = (char)bb.get();
        checksum = bb.getShort();
        quench = bb.getInt();
        int remainingBytes = bb.limit() - bb.position();
        if (remainingBytes > 0) {
            this.data = new byte[remainingBytes];
            bb.get(this.data);
        }
        return this;
    }

}
