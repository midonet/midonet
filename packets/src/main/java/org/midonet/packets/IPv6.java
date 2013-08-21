// Copyright 2012 Midokura Inc.

package org.midonet.packets;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class IPv6 extends BasePacket {
    public final static short ETHERTYPE = (short)0x86dd;

    public final static int MIN_HEADER_LEN = 40;
    //public final static int MAX_HEADER_LEN =
    //public final static int MAX_PACKET_LEN =

    public static Map<Byte, Class<? extends IPacket>> nextHeaderClassMap;

    static {
        nextHeaderClassMap = new HashMap<Byte, Class<? extends IPacket>>();

        nextHeaderClassMap.put(GRE.PROTOCOL_NUMBER, GRE.class);
        nextHeaderClassMap.put(ICMPv6.PROTOCOL_NUMBER, ICMPv6.class);
        nextHeaderClassMap.put(TCP.PROTOCOL_NUMBER, TCP.class);
        nextHeaderClassMap.put(UDP.PROTOCOL_NUMBER, UDP.class);
    }

    protected byte version;
    protected byte trafficClass;
    protected int flowLabel;
    protected int totalLength;
    protected short payloadLength;
    protected byte nextHeader;
    protected byte hopLimit;
    protected IPv6Addr sourceAddress;
    protected IPv6Addr destinationAddress;

    /**
     * Default constructor that sets the version to 6
     */
    public IPv6() {
        super();
        this.version = 6;
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("IPv6 [traffic class=").append(trafficClass);
        sb.append(", flow label=").append(flowLabel);
        sb.append(", payload length=").append(payloadLength);
        sb.append(", next header=").append(nextHeader);
        sb.append(", hop limit=").append(hopLimit);
        sb.append(", nwSrc=").append(sourceAddress.toString());
        sb.append(", nwDst=").append(destinationAddress.toString());
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
    public IPv6 setVersion(byte version) {
        this.version = version;
        return this;
    }

    /**
     * @return Traffic Class
     */
    public byte getTrafficClass() {
        return trafficClass;
    }

    /**
     * @param trafficClass the traffic class field to set
     */
    public IPv6 setTrafficClass(byte trafficClass) {
        this.trafficClass = trafficClass;
        return this;
    }

    /**
     * @return the flow label
     */
    public int getFlowLabel() {
        return flowLabel;
    }

    /**
     * @param flowLabel set flow label field
     */
    public IPv6 setFlowLabel(int flowLabel) {
        this.flowLabel = flowLabel;
        return this;
    }

    /**
     * @return the payload length
     */
    public int getPayloadLength() {
        return payloadLength;
    }

    /**
     * @param payloadLength setting payload length field in the packet
     */
    public IPv6 setPayloadLength(int payloadLength) {
        if (payloadLength > 0xFFFF) {
            throw new IllegalArgumentException("Invalid IPv6 payload length "
                    + payloadLength);
        }
        this.payloadLength = (short)payloadLength;
        return this;
    }

    /**
     * @return the next header (protocol) field
     */
    public byte getNextHeader() {
        return nextHeader;
    }

    /**
     * @param nextHeader setting the next Header field
     */
    public IPv6 setNextHeader(byte nextHeader) {
        this.nextHeader = nextHeader;
        return this;
    }

    /**
     * @return the value of the hop limit field
     */
    public byte getHopLimit() {
        return hopLimit;
    }

    /**
     * @param hopLimit the hop limit value to set
     */
    public IPv6 setHopLimit(byte hopLimit) {
        this.hopLimit = hopLimit;
        return this;
    }

    /**
     * @return the source IPv6 Address
     */
    public IPv6Addr getSourceAddress() {
        return sourceAddress;
    }

    /**
     * @param sourceAddress the source IPv6 address value to set
     */
    public IPv6 setSourceAddress(IPv6Addr sourceAddress) {
        this.sourceAddress = sourceAddress;
        return this;
    }

    /**
     * @param srcAddrUpperWord the source IPv6 address value to set (upper 8 bytes)
     * @param srcAddrLowerWord the source IPv6 address value to set (lower 8 bytes)
     */
    public IPv6 setSourceAddress(long srcAddrUpperWord, long srcAddrLowerWord) {
        this.sourceAddress = new IPv6Addr(srcAddrUpperWord, srcAddrLowerWord);
        return this;
    }

    /**
     * @param sourceAddress the source IPv6 address value to set
     */
    public IPv6 setSourceAddress(String sourceAddress) {
        this.sourceAddress = IPv6Addr.fromString(sourceAddress);
        return this;
    }

    /**
     * @return the destination IPv6 Address
     */
    public IPv6Addr getDestinationAddress() {
        return destinationAddress;
    }

    /**
     * @param destinationAddress the dest IPv6 address value to set
     */
    public IPv6 setDestinationAddress(IPv6Addr destinationAddress) {
        this.destinationAddress = destinationAddress;
        return this;
    }

    /**
     * @param dstAddrUpperWord the dest IPv6 address value to set (upper 8 bytes)
     * @param dstAddrLowerWord the dest IPv6 address value to set (lower 8 bytes)
     */
    public IPv6 setDestinationAddress(long dstAddrUpperWord, long dstAddrLowerWord) {
        this.destinationAddress = new IPv6Addr(dstAddrUpperWord, dstAddrLowerWord);
        return this;
    }

    /**
     * @param destinationAddress the dest IPv6 address value to set
     */
    public IPv6 setDestinationAddress(String destinationAddress) {
        this.destinationAddress = IPv6Addr.fromString(destinationAddress);
        return this;
    }

    /**
     * Serializes the packet.
     */
    @Override
    public byte[] serialize() {
        byte[] payloadData = null;
        if (payload != null) {
            payload.setParent(this);
            payloadData = payload.serialize();
        }

        this.totalLength = (MIN_HEADER_LEN + ((payloadData == null) ? 0
                    : payloadData.length));
        byte[] data = new byte[totalLength];
        ByteBuffer bb = ByteBuffer.wrap(data);

        bb.put((byte) (((this.version & 0xf) << 4) |
                        ((this.trafficClass & 0xf0) >> 4)));
        bb.put((byte) (((this.trafficClass & 0x0f) << 4) |
                        ((this.flowLabel & 0x000f0000) << 4)));
        bb.putShort((short) ((this.flowLabel & 0x0000ffff) << 16));
        this.payloadLength = (short)payloadData.length;
        bb.putShort(this.payloadLength);
        bb.put(this.nextHeader);
        bb.put(this.hopLimit);
        bb.putLong(sourceAddress.upperWord());
        bb.putLong(sourceAddress.lowerWord());
        bb.putLong(destinationAddress.upperWord());
        bb.putLong(destinationAddress.lowerWord());
        if (payloadData != null)
            bb.put(payloadData);
        return data;
    }

    @Override
    public IPacket deserialize(ByteBuffer bb) throws MalformedPacketException {
        int length = bb.remaining();

        if (length < MIN_HEADER_LEN) {
            throw new MalformedPacketException("Invalid IPv6 packet size: "
                      + length);
        }

        byte byteField = bb.get();
        short shortField = 0;
        this.version = (byte)((byteField & 0xf0) >> 4);
        this.trafficClass |= ((byteField & 0x0f) << 4);
        byteField = bb.get();
        this.trafficClass |= ((byteField & 0xf0) >> 4);
        shortField = bb.getShort();
        this.flowLabel |= ((byteField & 0x0f) << 16);
        this.flowLabel |= shortField;
        this.payloadLength = bb.getShort();
        this.nextHeader = bb.get();
        this.hopLimit = bb.get();
        this.sourceAddress = new IPv6Addr(bb.getLong(), bb.getLong());
        this.destinationAddress = new IPv6Addr(bb.getLong(), bb.getLong());
        if (IPv6.nextHeaderClassMap.containsKey(this.nextHeader)) {
            Class<? extends IPacket> clazz = IPv6.nextHeaderClassMap.get(this.nextHeader);
            try {
                payload = clazz.newInstance();
            } catch (Exception e) {
                throw new RuntimeException("Error parsing payload for IPv6 packet", e);
            }
        } else {
            payload = new Data();
        }
        payload.deserialize(bb.slice());
        payload.setParent(this);
        return this;
    }
}
