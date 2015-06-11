/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.packets;

import java.nio.ByteBuffer;
import java.util.Arrays;

public class ICMP extends BasePacket {
    public static final byte PROTOCOL_NUMBER = 1;

    public static final byte CODE_NONE = 0;

    public static final byte TYPE_UNREACH = 3;
    public static enum UNREACH_CODE {
        UNREACH_NET(0),
        UNREACH_HOST(1),
        UNREACH_PROTOCOL(2),
        UNREACH_PORT(3),
        UNREACH_FRAG_NEEDED(4),
        UNREACH_SOURCE_ROUTE(5),
        UNREACH_FILTER_PROHIB(13);

        private final byte value;
        private UNREACH_CODE(int value) { this.value = (byte)value; }
        public byte toByte() { return value; }
    }

    public static final byte TYPE_TIME_EXCEEDED = 11;
    public static enum EXCEEDED_CODE {
        EXCEEDED_TTL(0), EXCEEDED_REASSEMBLY(1);

        private final byte value;
        private EXCEEDED_CODE(int value) { this.value = (byte)value; }
        public byte toByte() { return value; }
    }

    public static final byte TYPE_ECHO_REPLY = 0;
    public static final byte TYPE_ECHO_REQUEST = 8;
    public static final byte TYPE_SOURCE_QUENCH = 4;
    public static final byte TYPE_REDIRECT = 5;
    public static final byte TYPE_ROUTER_SOLICITATION= 10;
    public static final byte TYPE_PARAMETER_PROBLEM = 12;

    /**
     * ICMP header length as a number of octets.
     */
    public static final int HEADER_LEN = 8;

    // Types

    private byte type;
    private byte code;
    private short checksum;
    private int quench;
    private byte[] data;

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

    public static boolean isError(byte type) {
        return type != TYPE_ECHO_REQUEST && type != TYPE_ECHO_REPLY;
    }

    public boolean isError() {
        return isError(type);
    }

    public byte getType() {
        return type;
    }

    public byte getCode() {
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
        IPacket payload = ipPkt.payload;
        while (!(payload instanceof Data) && payload != null) {
            payload = payload.getPayload();
        }
        int payloadLength = payload != null ? ((Data) payload).getData().length : 0;

        byte[] data = ipPkt.serialize();
        int length = ipPkt.totalLength - payloadLength;
        if (length >= data.length)
            this.data = data;
        else
            this.data = Arrays.copyOf(data, length);
    }

    public void setType(byte type, byte code, byte[] data) {
        this.type = type;
        this.code = code;
        this.data = (data == null) ? null : Arrays.copyOf(data, data.length);
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

    public void setIdentifier(short id) {
        quench |= id << 16;
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

    public void clearChecksum() {
        checksum = 0;
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
        bb.put(type);
        bb.put(code);
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

        type = bb.get();
        code = bb.get();
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
