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

public class ICMPv6 extends BasePacket {
    public static final byte PROTOCOL_NUMBER = 58;

    public static final char CODE_NONE = 0;

    public static final char TYPE_UNREACH = 1;
    public static enum UNREACH_CODE {
        UNREACH_NO_ROUTE((char)0), UNREACH_ADDR((char)3),
        UNREACH_FILTER_PROHIB((char)1);

        private final char value;
        private UNREACH_CODE(char value) { this.value = value; }
        public char toChar() { return value; }
    }

    public static final char TYPE_TIME_EXCEEDED = 3;
    public static enum EXCEEDED_CODE {
        EXCEEDED_HOP_LIMIT((char)0), EXCEEDED_REASSEMBLY((char)1);

        private final char value;
        private EXCEEDED_CODE(char value) { this.value = value; }
        public char toChar() { return value; }
    }

    public static final char TYPE_ECHO_REPLY = 129;
    public static final char TYPE_ECHO_REQUEST = 128;
    public static final char TYPE_PARAMETER_PROBLEM = 4;
    public static final char TYPE_PACKET_TOO_BIG = 2;

    /**
     * ICMP header length as a number of octets.
     */
    public static final int HEADER_LEN = 8;

    // Types

    char type;
    char code;
    short checksum;
    int quench;
    int mtu;
    byte[] data;

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("ICMP [type=").append((int)type);
        sb.append(", code=").append((int)code);
        sb.append(", cksum=").append(checksum);
        sb.append(", quench=").append(quench);
        sb.append(", mtu=").append(mtu);
        sb.append("]");
        return sb.toString();
    }

    public boolean isError() {
        return TYPE_UNREACH == type || TYPE_TIME_EXCEEDED == type ||
                TYPE_PACKET_TOO_BIG == type|| TYPE_PARAMETER_PROBLEM == type;
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

    public final byte[] getData() {
        return data;
    }

    @Override
    public int getPayloadLength() {
        return data != null ? data.length : 0;
    }

    private void setIPv6Packet(IPv6 ipPkt) {
        int payloadLength = payload.getPayloadLength();
        byte[] data = ipPkt.serialize();
        int length = data.length - payloadLength;
        if (length >= data.length)
            this.data = data;
        else
            this.data = Arrays.copyOf(data, length);
    }

    public void setUnreachable(UNREACH_CODE unreachCode, IPv6 ipPkt) {
        type = TYPE_UNREACH;
        code = unreachCode.value;
        checksum = 0;
        quench = 0;
        setIPv6Packet(ipPkt);
    }

    public void setTimeExceeded(EXCEEDED_CODE timeCode, IPv6 ipPkt) {
        type = TYPE_TIME_EXCEEDED;
        code = timeCode.value;
        checksum = 0;
        quench = 0;
        setIPv6Packet(ipPkt);
    }

    public void setPktTooBig(int maxTranUnit, IPv6 ipPkt) {
        type = TYPE_PACKET_TOO_BIG;
        code = CODE_NONE;
        checksum = 0;
        quench = 0;
        mtu = maxTranUnit;
        setIPv6Packet(ipPkt);
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
        quench = (id << 16) | (seq & 0xffff);
        this.data = data;
    }

    public short getIdentifier() {
        return (short)(quench >> 16);
    }

    public short getSequenceNum() {
        return (short)quench;
    }

    public int getMTU() {
        return mtu;
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
        if ((type == TYPE_ECHO_REQUEST) || (type == TYPE_ECHO_REPLY)) {
            bb.putInt(quench);
        } else if (type == TYPE_PACKET_TOO_BIG) {
            bb.putInt(mtu);
        }

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
        if ((type == TYPE_ECHO_REQUEST) || (type == TYPE_ECHO_REPLY)) {
            quench = bb.getInt();
        } else if (type == TYPE_PACKET_TOO_BIG) {
            mtu = bb.getInt();
        }
        if (bb.hasRemaining()) {
            this.data = new byte[bb.remaining()];
            bb.get(this.data);
        }
        return this;
    }
}
