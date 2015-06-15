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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TCP extends BasePacket implements Transport {

    public enum Flag {

        Fin(0, "FIN"),
        Syn(1, "SYN"),
        Rst(2, "RST"),
        Psh(3, "PSH"),
        Ack(4, "ACK"),
        Urg(5, "URG"),
        Ece(6, "ECE"),
        Cwr(7, "CWR"),
        Ns(8, "NS");

        public final int position;
        public final int bit;
        public final String name;

        Flag(int position, String name) {
            this.position = position;
            this.bit = 1 << position;
            this.name = name;
        }

        @Override public String toString() { return this.name; }

        /** builds a list of flags from the bits set in @param b */
        public static List<Flag> allOf(int b) {
            List<Flag> flags = new ArrayList<>();
            for (Flag f : Flag.values()) {
                if ((f.bit & b) != 0) { flags.add(f);}
            }
            return flags;
        }

        /** builds a list of flags from the bits set in @param b */
        public static short allOf(List<Flag> flagsLst) {
            short res = 0;
            for (Flag f : flagsLst)
                res |= f.bit;
            return res;
        }

        /** build a string representation for a flags set */
        public static String allOfToString(int b) {
            StringBuilder buf = new StringBuilder();
            if (b != 0) {
                for (TCP.Flag f : TCP.Flag.allOf(b)) {
                    buf.append(f).append("|");
                }
                int bl = buf.length();
                if (bl > 0)
                    buf.deleteCharAt(bl - 1);
            }
            return buf.toString();
        }
    }

    public static final byte PROTOCOL_NUMBER = 6;

    public static final int MIN_HEADER_LEN = 20;
    public static final int MIN_DATA_OFFSET = 5;

    protected int sourcePort;
    protected int destinationPort;
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
        sb.append(", flags=").append(Flag.allOfToString(flags));
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
        bb.putShort((short)sourcePort);
        bb.putShort((short)destinationPort);
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

        if (checksum == 0)
            calculateChecksum(bb, length);

        return data;
    }

    private void calculateChecksum(ByteBuffer bb, int length) {
        bb.rewind();
        int accumulation = 0;

        if (this.parent != null && this.parent instanceof IPv4) {
            IPv4 ipv4 = (IPv4) this.parent;
            accumulation += ((ipv4.getSourceAddress() >> 16) & 0xffff)
                    + (ipv4.getSourceAddress() & 0xffff);
            accumulation += ((ipv4.getDestinationAddress() >> 16) & 0xffff)
                    + (ipv4.getDestinationAddress() & 0xffff);
            accumulation += ipv4.getProtocol() & 0xff;
            accumulation += length & 0xffff;
        }

        if (this.parent != null && this.parent instanceof IPv6) {
            IPv6 ipv6 = (IPv6) this.parent;
            long sauw = ipv6.getSourceAddress().upperWord();
            long salw = ipv6.getSourceAddress().lowerWord();
            long dauw = ipv6.getDestinationAddress().upperWord();
            long dalw = ipv6.getDestinationAddress().lowerWord();
            accumulation +=  (sauw & 0xffff)
                          + ((sauw  >> 16) & 0xffff)
                          + ((sauw  >> 32) & 0xffff)
                          + ((sauw  >> 48) & 0xffff)
                          + (salw & 0xffff)
                          + ((salw  >> 16) & 0xffff)
                          + ((salw  >> 32) & 0xffff)
                          + ((salw  >> 48) & 0xffff);
            accumulation +=  (dauw & 0xffff)
                          + ((dauw  >> 16) & 0xffff)
                          + ((dauw  >> 32) & 0xffff)
                          + ((dauw  >> 48) & 0xffff)
                          + (dalw & 0xffff)
                          + ((dalw  >> 16) & 0xffff)
                          + ((dalw  >> 32) & 0xffff)
                          + ((dalw  >> 48) & 0xffff);
            accumulation += ipv6.getNextHeader() & 0xff;
            accumulation += length & 0xffff;
        }

        for (int i = 0; i < length / 2; ++i) {
            accumulation += 0xffff & bb.getShort();
        }
        // pad to an even number of shorts
        if (length % 2 > 0) {
            accumulation += (bb.get() & 0xff) << 8;
        }

        accumulation = ((accumulation >> 16) & 0xffff)
                + (accumulation & 0xffff);
        this.checksum = (short) (~accumulation & 0xffff);
        bb.putShort(16, this.checksum);
    }

    @Override
    public IPacket deserialize(ByteBuffer bb) throws MalformedPacketException {

        if (bb.remaining() < MIN_HEADER_LEN) {
            throw new MalformedPacketException("TCP packet size is invalid: "
                + bb.remaining());
        }

        sourcePort = Unsigned.unsign(bb.getShort());
        destinationPort = Unsigned.unsign(bb.getShort());
        seqNo = bb.getInt();
        ackNo = bb.getInt();
        flags = bb.getShort(); //TODO: parse flags
        windowSize = bb.getShort();
        checksum = bb.getShort();
        urgent = bb.getShort();
        //TODO: verify checksum
        int dataOffset = (flags >> 12) & 0xf;
        if (dataOffset < MIN_DATA_OFFSET) {
            throw new MalformedPacketException("TCP data offset is invalid: "
                    + dataOffset);
        }

        if (dataOffset > MIN_DATA_OFFSET) {
            int optionsLength = (dataOffset - MIN_DATA_OFFSET) * 4;
            if (optionsLength > bb.remaining()) {
                throw new MalformedPacketException("Packet size left "
                        + bb.remaining()
                        + " does not match the specified data offset: "
                        + dataOffset);
            }
            options = new byte[optionsLength];
            bb.get(options);
        }

        if (bb.hasRemaining()) {
            payload = new Data();
            int start= bb.position();
            int end = bb.limit();
            payload.deserialize(bb);
            bb.position(start);
            bb.limit(end);
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

    public void setFlags(List<TCP.Flag> flags) {
        setFlags(TCP.Flag.allOf(flags));
    }

    public boolean getFlag(Flag f) { return (flags & f.bit) != 0; }

    public void setFlag(Flag f, boolean v) {
        if (v)
            flags |= f.bit;
        else
            flags &= ~f.bit;
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

    @Override
    public void setSourcePort(int sourcePort) {
        ensurePortInRange(sourcePort);
        this.sourcePort = sourcePort;
    }

    @Override
    public void setDestinationPort(int destinationPort) {
        ensurePortInRange(destinationPort);
        this.destinationPort = destinationPort;
    }

    @Override
    public void clearChecksum() {
        checksum = 0;
    }

    @Override
    public int getSourcePort() {
        return sourcePort;
    }

    @Override
    public int getDestinationPort() {
        return destinationPort;
    }

    public Data getPayload() {
        return (Data) payload;
    }

    public static int getSourcePort(ByteBuffer bb) throws MalformedPacketException {
        try {
            return Unsigned.unsign(bb.getShort(0));
        } catch (Exception e) {
            throw new MalformedPacketException("Cannot read tpSrc, corrupted data", e);
        }
    }

    public static int getDestinationPort(ByteBuffer bb) throws MalformedPacketException {
        try {
            return Unsigned.unsign(bb.getShort(2));
        } catch (Exception e) {
            throw new MalformedPacketException("Cannot read tpDst, corrupted data", e);
        }
    }

    public static void ensurePortInRange(int port) {
        if (!isPortInRange(port))
            throw new IllegalArgumentException("transport port out of range");
    }

    public static boolean isPortInRange(int port) {
        return Transport.MIN_PORT_NO <= port && port <= Transport.MAX_PORT_NO;
    }
}
