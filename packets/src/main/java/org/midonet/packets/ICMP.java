/*
 * Copyright 2011 Midokura KK
 */
package org.midonet.packets;

import java.nio.ByteBuffer;
import java.util.Arrays;

public class ICMP extends BasePacket {
    public static final byte PROTOCOL_NUMBER = 1;

    public static final char CODE_NONE = 0;

    public static final char TYPE_UNREACH = 3;
    public static enum UNREACH_CODE {
        UNREACH_NET((char)0),
        UNREACH_HOST((char)1),
        UNREACH_PROTOCOL((char)2),
        UNREACH_PORT((char)3),
        UNREACH_FRAG_NEEDED((char)4),
        UNREACH_SOURCE_ROUTE((char)5),
        UNREACH_FILTER_PROHIB((char)13);

        private final char value;
        private UNREACH_CODE(char value) { this.value = value; }
        public char toChar() { return value; }
    }

    public static final char TYPE_TIME_EXCEEDED = 11;
    public static enum EXCEEDED_CODE {
        EXCEEDED_TTL((char)0), EXCEEDED_REASSEMBLY((char)1);

        private final char value;
        private EXCEEDED_CODE(char value) { this.value = value; }
        public char toChar() { return value; }
    }

    public static final char TYPE_ECHO_REPLY = 0;
    public static final char TYPE_ECHO_REQUEST = 8;
    public static final char TYPE_SOURCE_QUENCH = 4;
    public static final char TYPE_REDIRECT = 5;
    public static final char TYPE_PARAMETER_PROBLEM = 12;

    /**
     * ICMP header length as a number of octets.
     */
    public static final int HEADER_LEN = 8;

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
        return TYPE_UNREACH == type || TYPE_SOURCE_QUENCH == type ||
                TYPE_REDIRECT == type || TYPE_TIME_EXCEEDED == type ||
                TYPE_PARAMETER_PROBLEM == type;
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

    private void setIPv4Packet(IPv4 ipPkt) {
        byte[] data = ipPkt.serialize();
        int length = ipPkt.headerLength*4 + HEADER_LEN;
        if (length >= data.length)
            this.data = data;
        else
            this.data = Arrays.copyOf(data, length);
    }

    public void setError(char type, char code, byte[] data) {
        this.type = type;
        this.code = code;
        this.data = Arrays.copyOf(data, data.length);
    }

    public void setUnreachable(UNREACH_CODE unreachCode, IPv4 ipPkt) {
        type = TYPE_UNREACH;
        code = unreachCode.value;
        checksum = 0;
        quench = 0;
        setIPv4Packet(ipPkt);
    }

    public void setFragNeeded(int size, IPv4 ipPkt) {
        setUnreachable(UNREACH_CODE.UNREACH_FRAG_NEEDED, ipPkt);
        quench = size & 0xffff;
    }

    public void setTimeExceeded(EXCEEDED_CODE timeCode, IPv4 ipPkt) {
        type = TYPE_TIME_EXCEEDED;
        code = timeCode.value;
        checksum = 0;
        quench = 0;
        setIPv4Packet(ipPkt);
    }

    /**
     * BEWARE: direct assignment of data
     *
     * @param id
     * @param seq
     * @param data
     */
    public void setEchoRequest(short id, short seq, byte[] data) {
        type = TYPE_ECHO_REQUEST;
        code = CODE_NONE;
        checksum = 0;
        quench = id << 16 | seq & 0xffff;
        this.data = data;
    }

    /**
     * BEWARE: direct assignment of data
     *
     * @param id
     * @param seq
     * @param data
     */
    public void setEchoReply(short id, short seq, byte[] data) {
        type = TYPE_ECHO_REPLY;
        code = CODE_NONE;
        checksum = 0;
        quench = (id << 16) | (seq & 0xffff);
        this.data = data;
    }

    public short getIdentifier() {
        return (short)(quench >> 16);
    }

    public short getSequenceNum() {
        return (short)quench;
    }

    /**
     * BEWARE: direct assignment of data
     *
     * @param data
     */
    public void setData (byte[] data) {
        this.data = data;
    }

    /**
     * Serializes the packet. Will compute and set the checksum if it's set to
     * 0 (zero) at the time serialize is called.
     */
    @Override
    public byte[] serialize() {
        int length = HEADER_LEN + ((data == null) ? 0 : data.length);
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
            checksum = IPv4.computeChecksum(bytes, 0, bb.position(), 2);
            bb.putShort(2, this.checksum);
        }
        return bytes;
    }

    @Override
    public IPacket deserialize(ByteBuffer bb) throws MalformedPacketException {

        // Check that the size is correct to avoid BufferUnderflowException.
        if (bb.remaining() < HEADER_LEN) {
            throw new MalformedPacketException("Invalid ICMP header len: "
                    + bb.remaining());
        }

        type = (char) bb.get();
        code = (char) bb.get();
        checksum = bb.getShort();
        quench = bb.getInt();
        if (bb.hasRemaining()) {
            this.data = new byte[bb.remaining()];
            bb.get(this.data);
        }
        return this;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (!super.equals(obj))
            return false;
        if (!(obj instanceof ICMP))
            return false;
        final ICMP other = (ICMP) obj;
        if (this.type != other.type)
            return false;
        if (this.code != other.code)
            return false;
        if (this.checksum != other.checksum)
            return false;
        if (this.quench != other.quench)
            return false;
        return java.util.Arrays.equals(this.data, other.data);
    }
}
