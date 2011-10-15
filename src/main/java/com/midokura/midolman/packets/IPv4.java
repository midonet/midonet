/**
 * 
 */
package com.midokura.midolman.packets;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import com.midokura.midolman.util.Net;

/**
 * @author David Erickson (daviderickson@cs.stanford.edu)
 *
 */
public class IPv4 extends BasePacket {
    public static short ETHERTYPE = 0x0800;
    
    public static Map<Byte, Class<? extends IPacket>> protocolClassMap;

    static {
        protocolClassMap = new HashMap<Byte, Class<? extends IPacket>>();
        
        protocolClassMap.put(ICMP.PROTOCOL_NUMBER, ICMP.class);
        protocolClassMap.put(TCP.PROTOCOL_NUMBER, TCP.class);
        protocolClassMap.put(UDP.PROTOCOL_NUMBER, UDP.class);
    }

    protected byte version;
    protected byte headerLength;
    protected byte diffServ;
    protected short totalLength;
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
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("IPv4 [headerL=").append(headerLength);
        sb.append(", totalL=").append(totalLength);
        sb.append(", ttl=").append(ttl);
        sb.append(", frag=").append(fragmentOffset);
        sb.append(", cksum=").append(checksum);
        sb.append(", proto=").append(protocol);
        sb.append(", nwSrc=").append(
                Net.convertIntAddressToString(sourceAddress));
        sb.append(", nwDst=").append(
                Net.convertIntAddressToString(destinationAddress));
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
    public short getTotalLength() {
        return totalLength;
    }

    /**
     * @param totalLength the totalLength to set
     */
    public IPv4 setTotalLength(short totalLength) {
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

    /**
     * @return the sourceAddress
     */
    public int getSourceAddress() {
        return sourceAddress;
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
        this.sourceAddress = IPv4.toIPv4Address(sourceAddress);
        return this;
    }

    /**
     * @return the destinationAddress
     */
    public int getDestinationAddress() {
        return destinationAddress;
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
        this.destinationAddress = IPv4.toIPv4Address(destinationAddress);
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

    public boolean isMcast() {
        int byte1 = getDestinationAddress() >>> 24;
        // TODO(pino): check on this range.
        return byte1 >= 224 && byte1 < 240;
    }

    public boolean isSubnetBcast(int nwAddr, int nwLength) {
        int mask = 0xffffffff >>> nwLength;
        int subnetBcast = mask | nwAddr;
        return getDestinationAddress() == subnetBcast;
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

    public static String addrToString(int addr) {
        StringBuffer sbuf = new StringBuffer();
        for (int i=0; i<4; i++) {
            sbuf.append((addr >>> (3-i)*8) & 0xff);
            if (i != 3)
                sbuf.append(".");
        }
        return sbuf.toString(); 
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

        if (this.headerLength == 0) {
            int optionsLength = 0;
            if (this.options != null)
                optionsLength = this.options.length / 4;
            this.headerLength = (byte) (5 + optionsLength);
        }

        if (this.totalLength == 0) {
            this.totalLength = (short) (this.headerLength * 4 + ((payloadData == null) ? 0
                    : payloadData.length));
        }

        byte[] data = new byte[this.totalLength];
        ByteBuffer bb = ByteBuffer.wrap(data);

        bb.put((byte) (((this.version & 0xf) << 4) | (this.headerLength & 0xf)));
        bb.put(this.diffServ);
        bb.putShort(this.totalLength);
        bb.putShort(this.identification);
        bb.putShort((short) (((this.flags & 0x7) << 29) | (this.fragmentOffset & 0x1fff)));
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
    public IPacket deserialize(byte[] data, int offset, int length) {
        ByteBuffer bb = ByteBuffer.wrap(data, offset, length);
        short sscratch;

        this.version = bb.get();
        this.headerLength = (byte) (this.version & 0xf);
        this.version = (byte) ((this.version >> 4) & 0xf);
        this.diffServ = bb.get();
        this.totalLength = bb.getShort();
        this.identification = bb.getShort();
        sscratch = bb.getShort();
        this.flags = (byte) ((sscratch >> 29) & 0x7);
        this.fragmentOffset = (short) (sscratch & 0x1fff);
        this.ttl = bb.get();
        this.protocol = bb.get();
        this.checksum = bb.getShort();
        this.sourceAddress = bb.getInt();
        this.destinationAddress = bb.getInt();

        if (this.headerLength > 5) {
            int optionsLength = (this.headerLength - 5) * 4;
            this.options = new byte[optionsLength];
            bb.get(this.options);
        }

        // Calculate offset and length for next packet.
        offset = bb.position();
        length = bb.limit() - offset;
        IPacket payload;
        if (IPv4.protocolClassMap.containsKey(this.protocol)) {
            Class<? extends IPacket> clazz = IPv4.protocolClassMap.get(this.protocol);
            try {
                payload = clazz.newInstance();
            } catch (Exception e) {
                throw new RuntimeException("Error parsing payload for IPv4 packet", e);
            }
        } else {
            payload = new Data();
            // Adjust length (the remaining bytes in data) if it is larger than
            // the expected payload length (totalLength - 4 * headerLength).
            length = Math.min(length, totalLength - 4 * headerLength);
        }
        this.payload = payload.deserialize(data, offset, length);
        this.payload.setParent(this);
        return this;
    }

    /**
     * Accepts an IPv4 address of the form xxx.xxx.xxx.xxx, ie 192.168.0.1 and
     * returns the corresponding 32 bit integer.
     * @param ipAddress
     * @return
     */
    public static int toIPv4Address(String ipAddress) {
        String[] octets = ipAddress.split("\\.");
        if (octets.length != 4) 
            throw new IllegalArgumentException("Specified IPv4 address must" +
                "contain 4 sets of numerical digits separated by periods");

        int result = 0;
        for (int i = 0; i < 4; ++i) {
            result |= Integer.valueOf(octets[i]) << ((3-i)*8);
        }
        return result;
    }

    /**
     * Accepts an IPv4 address in a byte array and returns the corresponding
     * 32-bit integer value.
     * @param ipAddress
     * @return
     */
    public static int toIPv4Address(byte[] ipAddress) {
        int ip = 0;
        for (int i = 0; i < 4; i++) {
          int t = (ipAddress[i] & 0xff) << ((3-i)*8);
          ip |= t;
        }
        return ip;
    }

    /**
     * Accepts an IPv4 address and returns the correspondent bytes.
     * 
     * @param ipAddress
     * @return
     */
    public static byte[] toIPv4AddressBytes(int ipAddress) {
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            bytes[i] = (byte) ((ipAddress >>> (3-i)*8) & 0xff);
        }
        return bytes;
    }


    /**
     * Accepts an IPv4 address and returns of string of the form xxx.xxx.xxx.xxx
     * ie 192.168.0.1
     * 
     * @param ipAddress
     * @return
     */
    public static String fromIPv4Address(int ipAddress) {
        StringBuffer sb = new StringBuffer();
        int result = 0;
        for (int i = 0; i < 4; ++i) {
            result = (ipAddress >> ((3-i)*8)) & 0xff;
            sb.append(Integer.valueOf(result).toString());
            if (i != 3)
                sb.append(".");
        }
        return sb.toString();
    }

    /**
     * Accepts a collection of IPv4 addresses as integers and returns a single
     * String useful in toString method's containing collections of IP
     * addresses.
     * 
     * @param ipAddresses collection
     * @return
     */
    public static String fromIPv4AddressCollection(Collection<Integer> ipAddresses) {
        if (ipAddresses == null)
            return "null";
        StringBuffer sb = new StringBuffer();
        sb.append("[");
        for (Integer ip : ipAddresses) {
            sb.append(fromIPv4Address(ip));
            sb.append(",");
        }
        sb.replace(sb.length()-1, sb.length(), "]");
        return sb.toString();
    }

    /**
     * Accepts an IPv4 address of the form xxx.xxx.xxx.xxx, ie 192.168.0.1 and
     * returns the corresponding byte array
     * @param ipAddress
     * @return
     */
    public static byte[] toIPv4AddressBytes(String ipAddress) {
        String[] octets = ipAddress.split("\\.");
        if (octets.length != 4) 
            throw new IllegalArgumentException("Specified IPv4 address must" +
                "contain 4 sets of numerical digits separated by periods");

        byte[] result = new byte[4];
        for (int i = 0; i < 4; ++i) {
            result[i] = Integer.valueOf(octets[i]).byteValue();
        }
        return result;
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
