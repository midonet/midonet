/*
 * Copyright 2011 Midokura KK 
 */
package com.midokura.midolman.packets;

import java.nio.ByteBuffer;
import java.util.Arrays;

public class ICMP extends BasePacket {
    public static final byte PROTOCOL_NUMBER = 1;

    public static final char CODE_NONE = 0;

    // Code and Types for 'Destination Unreachable'
    public static final char TYPE_UNREACH = 3;
    public static final char CODE_UNREACH_NET = 0;
    public static final char CODE_UNREACH_HOST = 1;
    public static final char CODE_UNREACH_FILTER_PROHIB = 13;

    public static final char TYPE_ECHO_REPLY = 0;
    public static final char TYPE_ECHO_REQUEST = 8;
    
    // Types

    char type;
    char code;
    short checksum;
    int quench;
    byte[] data;
    IPv4 ip;

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

    public void setUnreachable(char unreach_code, IPv4 ipPkt) {
        type = TYPE_UNREACH;
        code = unreach_code;
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
        ByteBuffer bb = ByteBuffer.allocate(8);
        
        bb.putChar(type);
        bb.putChar(code);
        bb.putShort(checksum);
        bb.putInt(quench); // padding. ICMP has an 8-byte header.

        if (TYPE_UNREACH == type) {
            bb.put(ip.serialize(true));
        }
        if (null != data)
            bb.put(data);

        // ICMP checksum is calculated on header+data, with checksum=0.
        if (this.checksum == 0) {
            this.checksum = IPv4.computeChecksum(bb, bb.position(), 2);
            bb.putShort(2, this.checksum);
        }
        return bb.array();
    }

    @Override
    public IPacket deserialize(byte[] data, int offset, int length) {
        ByteBuffer bb = ByteBuffer.wrap(data, offset, length);
        type = bb.getChar();
        code = bb.getChar();
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
