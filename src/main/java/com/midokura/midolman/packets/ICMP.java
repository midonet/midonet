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
    IPv4 ip;

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

    public final IPv4 getIp() {
        return ip;
    }

    public void setUnreachable(UNREACH_CODE unreachCode, IPv4 ipPkt) {
        type = TYPE_UNREACH;
        code = unreachCode.value;
        checksum = 0;
        quench = 0;
        ip = ipPkt;
        IPacket ipPayload = ipPkt.getPayload();
        if (null != ipPayload) {
            data = ipPayload.serialize();
            data = Arrays.copyOf(data, 8);
        }
        else
            data = null;
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
        byte[] ipData = null;
        if (TYPE_UNREACH == type) {
            ipData = ip.serialize(true);
        }
        int length = 8 + ((ipData == null) ? 0 : ipData.length) +
                ((data == null) ? 0 : ipData.length);
        ByteBuffer bb = ByteBuffer.allocate(length);
        bb.put((byte)type);
        bb.put((byte)code);
        bb.putShort(checksum);
        bb.putInt(quench);

        if (null != ipData)
            bb.put(ipData);
        if (null != data)
            bb.put(data);

        // ICMP checksum is calculated on header+data, with checksum=0.
        if (checksum == 0) {
            checksum = IPv4.computeChecksum(bb, bb.position(), 2);
            bb.putShort(2, this.checksum);
        }
        return bb.array();
    }

    @Override
    public IPacket deserialize(byte[] data, int offset, int length) {
        ByteBuffer bb = ByteBuffer.wrap(data, offset, length);
        type = (char)bb.get();
        code = (char)bb.get();
        checksum = bb.getShort();
        quench = bb.getInt();
        int remainingLength = bb.limit()-bb.position();
        if (TYPE_UNREACH == type) {
            ip = new IPv4();
            ip.deserialize(data, bb.position(), remainingLength, true);
            remainingLength -= ip.headerLength;
        }
        if (0 < remainingLength)
            data = Arrays.copyOfRange(data, length-remainingLength, length);
        return this;
    }

}
