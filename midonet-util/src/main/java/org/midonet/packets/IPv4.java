/**
*    Copyright 2011, Big Switch Networks, Inc.
*    Originally created by David Erickson, Stanford University
*
*    Licensed under the Apache License, Version 2.0 (the "License"); you may
*    not use this file except in compliance with the License. You may obtain
*    a copy of the License at
*
*         http://www.apache.org/licenses/LICENSE-2.0
*
*    Unless required by applicable law or agreed to in writing, software
*    distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
*    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
*    License for the specific language governing permissions and limitations
*    under the License.
*
*    THIS FILE HAS BEEN MODIFIED FROM ITS ORIGINAL CONTENTS.
**/

package org.midonet.packets;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * @author David Erickson (daviderickson@cs.stanford.edu)
 *
 */
public class IPv4 extends BasePacket {
    public static final String regex =
        "^(([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])\\.){3}" +
        "([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])$";

    public final static short ETHERTYPE = 0x0800;

    public static Map<Byte, Class<? extends IPacket>> protocolClassMap;

    static {
        protocolClassMap = new HashMap<Byte, Class<? extends IPacket>>();

        protocolClassMap.put(GRE.PROTOCOL_NUMBER, GRE.class);
        protocolClassMap.put(ICMP.PROTOCOL_NUMBER, ICMP.class);
        protocolClassMap.put(TCP.PROTOCOL_NUMBER, TCP.class);
        protocolClassMap.put(UDP.PROTOCOL_NUMBER, UDP.class);
    }

    public final static int MIN_HEADER_WORD_NUM = 5;
    public final static int MAX_HEADER_WORD_NUM = 0xF;
    public final static int MIN_HEADER_LEN = MIN_HEADER_WORD_NUM * 4;
    public final static int MAX_HEADER_LEN = MAX_HEADER_WORD_NUM * 4;
    public final static int MAX_PACKET_LEN = 0xFFFF;

    public final static int IP_FLAGS_DF = 0x02;
    public final static int IP_FLAGS_MF = 0x01;

    public final static int IP_CHECKSUM_OFFSET = 10;

    protected byte version;
    protected byte headerLength;
    protected byte diffServ;
    protected int totalLength;
    protected short identification;
    protected byte flags;
    protected short fragmentOffset;
    protected byte ttl;
    protected byte protocol;
    protected short checksum;
    protected int sourceAddress;
    protected int destinationAddress;
    protected byte[] options;

    /**
     * Default constructor that sets the version to 4.
     */
    public IPv4() {
        super();
        this.version = 4;
        // Set the ttl to 64 on all packets. The caller may modify if desired.
        this.ttl = 64;
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("IPv4 [headerL=").append(headerLength);
        sb.append(", totalL=").append(totalLength);
        sb.append(", ttl=").append(ttl);
        sb.append(", flags=");
        if (dontFragmentFlagSet())
            sb.append("DF");
        sb.append("|");
        if (moreFragmentsFlagSet())
            sb.append("MF");
        sb.append(", offset=").append(fragmentOffset);
        sb.append(", cksum=").append(checksum);
        sb.append(", proto=").append(protocol);
        sb.append(", nwSrc=").append(IPv4Addr.intToString(sourceAddress));
        sb.append(", nwDst=").append(IPv4Addr.intToString(destinationAddress));
        sb.append(", payload=").append(
                null == payload ? "null" : payload.toString());
        sb.append("]");
        return sb.toString();
    }

    /**
     * @return the version
     */
    public byte getVersion() {
        return version;
    }

    /**
     * @param version the version to set
     */
    public IPv4 setVersion(byte version) {
        this.version = version;
        return this;
    }

    /**
     * @return the headerLength
     */
    public byte getHeaderLength() {
        return headerLength;
    }

    /**
     * @param headerLength the headerLength to set
     */
    public IPv4 setHeaderLength(byte headerLength) {
        this.headerLength = headerLength;
        return this;
    }

    /**
     * @return the diffServ
     */
    public byte getDiffServ() {
        return diffServ;
    }

    /**
     * @param diffServ the diffServ to set
     */
    public IPv4 setDiffServ(byte diffServ) {
        this.diffServ = diffServ;
        return this;
    }

    /**
     * @return the totalLength
     */
    public int getTotalLength() {
        return totalLength;
    }

    /**
     * @param totalLength the totalLength to set
     */
    public IPv4 setTotalLength(int totalLength) {
        if (totalLength > 0xFFFF) {
            throw new IllegalArgumentException("Invalid totalLength "
                    + totalLength);
        }
        this.totalLength = totalLength;
        return this;
    }

    /**
     * @return the identification
     */
    public short getIdentification() {
        return identification;
    }

    /**
     * @param identification the identification to set
     */
    public IPv4 setIdentification(short identification) {
        this.identification = identification;
        return this;
    }

    /**
     * @return the flags
     */
    public byte getFlags() {
        return flags;
    }

    public boolean dontFragmentFlagSet() {
        return (flags & IP_FLAGS_DF) != 0;
    }

    public boolean moreFragmentsFlagSet() {
        return (flags & IP_FLAGS_MF) != 0;
    }

    /**
     * @param flags the flags to set
     */
    public IPv4 setFlags(byte flags) {
        this.flags = flags;
        return this;
    }

    /**
     * @return the fragmentOffset
     */
    public short getFragmentOffset() {
        return fragmentOffset;
    }

    /**
     * @param fragmentOffset the fragmentOffset to set
     */
    public IPv4 setFragmentOffset(short fragmentOffset) {
        this.fragmentOffset = fragmentOffset;
        return this;
    }

    /**
     * @return the ttl
     */
    public byte getTtl() {
        return ttl;
    }

    /**
     * @param ttl the ttl to set
     */
    public IPv4 setTtl(byte ttl) {
        this.ttl = ttl;
        return this;
    }

    /**
     * @return the protocol
     */
    public byte getProtocol() {
        return protocol;
    }

    /**
     * @param protocol the protocol to set
     */
    public IPv4 setProtocol(byte protocol) {
        this.protocol = protocol;
        return this;
    }

    /**
     * @return the checksum
     */
    public short getChecksum() {
        return checksum;
    }

    /**
     * @param checksum the checksum to set
     */
    public IPv4 setChecksum(short checksum) {
        this.checksum = checksum;
        return this;
    }

    public IPv4 clearChecksum() {
        this.checksum = 0;
        return this;
    }

    /**
     * @return the sourceAddress
     */
    public int getSourceAddress() {
        return sourceAddress;
    }

    public IPv4Addr getSourceIPAddress() {
        return IPv4Addr.fromInt(sourceAddress);
    }

    /**
     * @param sourceAddress the sourceAddress to set
     */
    public IPv4 setSourceAddress(int sourceAddress) {
        this.sourceAddress = sourceAddress;
        return this;
    }

    /**
     * @param sourceAddress the sourceAddress to set
     */
    public IPv4 setSourceAddress(String sourceAddress) {
        this.sourceAddress = IPv4Addr.stringToInt(sourceAddress);
        return this;
    }

    public IPv4 setSourceAddress(IPv4Addr sourceAddress) {
        this.sourceAddress = sourceAddress.toInt();
        return this;
    }

    /**
     * @return the destinationAddress
     */
    public int getDestinationAddress() {
        return destinationAddress;
    }

    public IPv4Addr getDestinationIPAddress() {
        return IPv4Addr.fromInt(destinationAddress);
    }

    /**
     * @param destinationAddress the destinationAddress to set
     */
    public IPv4 setDestinationAddress(int destinationAddress) {
        this.destinationAddress = destinationAddress;
        return this;
    }

    /**
     * @param destinationAddress the destinationAddress to set
     */
    public IPv4 setDestinationAddress(String destinationAddress) {
        this.destinationAddress = IPv4Addr.stringToInt(destinationAddress);
        return this;
    }

    public IPv4 setDestinationAddress(IPv4Addr destinationAddress) {
        this.destinationAddress = destinationAddress.toInt();
        return this;
    }

    /**
     * @return the options
     */
    public byte[] getOptions() {
        return options;
    }

    /**
     * @param options the options to set
     */
    public IPv4 setOptions(byte[] options) {
        if (options != null && (options.length % 4) > 0)
            throw new IllegalArgumentException(
                    "Options length must be a multiple of 4");
        this.options = options;
        return this;
    }

    /**
     * Compute the IPv4 checksum of length bytes of an array starting at offset.
     * From RFC 1071: "The checksum field is the 16-bit one's complement of the
     * one's complement sum of all 16-bit words in the header. For purposes of
     * computing the checksum, the value of the checksum field is zero."
     * @param data The data array.
     * @param offset The index of the first byte to include in the checksum.
     * @param length The number of bytes on which to compute the checksum.
     * @param cksumPos The absolute index of the 2-byte checksum field. For
     * example, the checksum field is at bytes 10 and 11 in the IPv4 header.
     * These bytes are ignored in the checksum computation.
     * @return The computed checksum as a short.
     */
    public static short computeChecksum(byte[] data, int offset, int length,
            int cksumPos) {
        int accumulation = 0;
        for (int i = offset; i < offset+length; i++) {
            int leftByte = 0xff & data[i];
            if (i == cksumPos || i == cksumPos+1)
                leftByte = 0;
            i++;
            // If length is odd, then eventually i == offset+length. In that
            // case, set the right byte to zero.
            int rightByte = i == offset+length ? 0 : 0xff & data[i];
            if (i == cksumPos || i == cksumPos+1)
                rightByte = 0;
            accumulation += (leftByte << 8) | rightByte;
        }
        // Now add all the carry bits. This implementation will work as long
        // as length < 2^16.
        accumulation = ((accumulation >> 16) & 0xffff)
                + (accumulation & 0xffff);
        return (short) (~accumulation & 0xffff);
    }

    /**
     * Serializes the packet. Will compute and set the following fields if they
     * are set to specific values at the time serialize is called:
     *      -checksum : 0
     *      -headerLength : 0
     *      -totalLength : 0
     */
    @Override
    public byte[] serialize() {
        byte[] payloadData = null;
        if (payload != null) {
            payload.setParent(this);
            payloadData = payload.serialize();
        }

        int optionsLength = 0;
        if (this.options != null)
            optionsLength = this.options.length / 4;
        this.headerLength = (byte) (5 + optionsLength);

        int actualLength = (this.headerLength * 4 + ((payloadData == null)
                                                     ? 0 : payloadData.length));
        if (this.totalLength == 0) {
            this.totalLength = actualLength;
        }

        byte[] data = new byte[actualLength];
        ByteBuffer bb = ByteBuffer.wrap(data);

        bb.put((byte) (((this.version & 0xf) << 4) | (this.headerLength & 0xf)));
        bb.put(this.diffServ);
        bb.putShort((short) this.totalLength);
        bb.putShort(this.identification);
        bb.putShort((short) (((this.flags & 0x7) << 13) | (this.fragmentOffset & 0x1fff)));
        bb.put(this.ttl);
        bb.put(this.protocol);
        bb.putShort(this.checksum);
        bb.putInt(this.sourceAddress);
        bb.putInt(this.destinationAddress);
        if (this.options != null)
            bb.put(this.options);
        if (payloadData != null)
            bb.put(payloadData);

        // compute checksum if needed
        if (this.checksum == 0) {
            this.checksum = computeChecksum(data, 0, this.headerLength * 4, 10);
            bb.putShort(10, this.checksum);
        }
        return data;
    }

    @Override
    public IPacket deserialize(ByteBuffer bb) throws MalformedPacketException {
        this.deserializeHeader(bb);
        if (IPv4.protocolClassMap.containsKey(this.protocol)) {
            Class<? extends IPacket> clazz = IPv4.protocolClassMap.get(this.protocol);
            try {
                payload = clazz.newInstance();
            } catch (Exception e) {
                payload = new Data();
            }
        } else {
            payload = new Data();
        }

        int payloadLen = this.totalLength - (4 * this.headerLength);
        if (bb.remaining() > payloadLen) {
            // Assume byte buffer is correct
            this.totalLength = bb.remaining() + (4 * this.headerLength);
        }

        // TODO: Currently we treat packets that have less data than
        // specified in the header as a valid packet because OVS could
        // cut off the packet.  In the future, we will fix this perhaps
        // by adding a catch-all flow rule that sends the right length
        // of each type of packet to the controller.
        int start = bb.position();
        int end = bb.limit();
        try {
            payload.deserialize(bb);
        } catch (Exception e) {
            payload = (new Data()).deserialize(bb);
        }
        bb.limit(end);
        bb.position(start);
        payload.setParent(this);
        return this;
    }

    /**
     * Deserializes only the header of an IPv4 packet only, ignoring all the
     * rest of data in the given ByteBuffer.
     *
     * @param bb
     * @throws MalformedPacketException
     */
    public void deserializeHeader(ByteBuffer bb)
        throws MalformedPacketException {

        int length = bb.remaining();

        // Check that the size is correct to avoid BufferUnderflowException.
        if (length < MIN_HEADER_LEN || length > MAX_PACKET_LEN) {
            throw new MalformedPacketException("Invalid IPv4 packet size: "
                                                   + length);
        }

        short sscratch;
        byte versionAndHeaderLen = bb.get();
        this.headerLength = (byte) (versionAndHeaderLen & 0xf);
        if (this.headerLength < MIN_HEADER_WORD_NUM) {
            // Don't process if it contains a bad header word num value.
            throw new MalformedPacketException("Bad IPv4 header word num: "
                                                   + this.headerLength);
        }

        this.version = (byte) ((versionAndHeaderLen >> 4) & 0xf);
        this.diffServ = bb.get();
        this.totalLength = bb.getShort() & 0xffff;
        if (this.totalLength < (this.headerLength * 4)) {
            // Don't process if the total length is corrupted.
            throw new MalformedPacketException("Bad IPv4 datagram length: "
                       + this.totalLength + " based on the given header size: "
                       + this.headerLength);
        }

        this.identification = bb.getShort();
        sscratch = bb.getShort();
        this.flags = (byte) ((sscratch >> 13) & 0x7);
        this.fragmentOffset = (short) (sscratch & 0x1fff);
        this.ttl = bb.get();
        this.protocol = bb.get();
        this.checksum = bb.getShort();
        this.sourceAddress = bb.getInt();
        this.destinationAddress = bb.getInt();

        if (this.headerLength > MIN_HEADER_WORD_NUM) {
            int optionsLength = (this.headerLength - MIN_HEADER_WORD_NUM) * 4;
            this.options = new byte[optionsLength];
            bb.get(this.options);
        }

    }

    /* (non-Javadoc)
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        final int prime = 2521;
        int result = super.hashCode();
        result = prime * result + checksum;
        result = prime * result + destinationAddress;
        result = prime * result + diffServ;
        result = prime * result + flags;
        result = prime * result + fragmentOffset;
        result = prime * result + headerLength;
        result = prime * result + identification;
        result = prime * result + Arrays.hashCode(options);
        result = prime * result + protocol;
        result = prime * result + sourceAddress;
        result = prime * result + totalLength;
        result = prime * result + ttl;
        result = prime * result + version;
        return result;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (!super.equals(obj))
            return false;
        if (!(obj instanceof IPv4))
            return false;
        IPv4 other = (IPv4) obj;
        if (checksum != other.checksum)
            return false;
        if (destinationAddress != other.destinationAddress)
            return false;
        if (diffServ != other.diffServ)
            return false;
        if (flags != other.flags)
            return false;
        if (fragmentOffset != other.fragmentOffset)
            return false;
        if (headerLength != other.headerLength)
            return false;
        if (identification != other.identification)
            return false;
        if (!Arrays.equals(options, other.options))
            return false;
        if (protocol != other.protocol)
            return false;
        if (sourceAddress != other.sourceAddress)
            return false;
        if (totalLength != other.totalLength)
            return false;
        if (ttl != other.ttl)
            return false;
        if (version != other.version)
            return false;
        return true;
    }
}
