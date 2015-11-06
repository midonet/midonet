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
**/

package org.midonet.packets;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 *
 * @author David Erickson (daviderickson@cs.stanford.edu)
 */
public class UDP extends BasePacket implements Transport {
    public static final byte PROTOCOL_NUMBER = 0x11;
    public static final int DHCP_SERVER = 67;
    public static final int DHCP_CLIENT = 68;
    public static final int DHCPV6_CLIENT = 546;
    public static final int DHCPV6_SERVER = 547;
    public static final int VXLAN = 4789;

    /**
     * UDP header length in bytes.
     */
    public static final int HEADER_LEN = 8;

    /**
     * Max len of a UDP packet.
     */
    public static final int MAX_PACKET_LEN = 0xFFFF;

    public static Map<Integer, Class<? extends IPacket>> decodeMap;

    static {
        decodeMap = new HashMap<Integer, Class<? extends IPacket>>();
        UDP.decodeMap.put(DHCP_SERVER, DHCP.class);
        UDP.decodeMap.put(DHCP_CLIENT, DHCP.class);
        UDP.decodeMap.put(DHCPV6_CLIENT, DHCPv6.class);
        UDP.decodeMap.put(DHCPV6_SERVER, DHCPv6.class);
        UDP.decodeMap.put(VXLAN, VXLAN.class);
    }

    protected int sourcePort;
    protected int destinationPort;
    protected int length;
    protected short checksum;

    @Override
    public int length() {
        return HEADER_LEN + childLength();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("UDP [sport=").append(sourcePort);
        sb.append(", dport=").append(destinationPort);
        sb.append(", length=").append(length);
        sb.append(", cksum=").append(checksum);
        sb.append(", payload=").append(
                null == payload ? "null" : payload.toString());
        sb.append("]");
        return sb.toString();
    }

    /**
     * @return the sourcePort
     */
    @Override
    public int getSourcePort() {
        return sourcePort;
    }

    /**
     * @param sourcePort the sourcePort to set
     */
    @Override
    public void setSourcePort(int sourcePort) {
        TCP.ensurePortInRange(sourcePort);
        this.sourcePort = sourcePort;
    }

    /**
     * @return the destinationPort
     */
    @Override
    public int getDestinationPort() {
        return destinationPort;
    }

    /**
     * @param destinationPort the destinationPort to set
     */
    @Override
    public void setDestinationPort(int destinationPort) {
        TCP.ensurePortInRange(destinationPort);
        this.destinationPort = destinationPort;
    }

    @Override
    public void clearChecksum() {
        checksum = 0;
    }

    /**
     * @return the length
     */
    public int getLength() {
        return length;
    }

    /**
     * @param length the length to set
     */
    public UDP setLength(int length) {
        if (length > 0xFFFF) {
            throw new IllegalArgumentException("Invalid length: " + length);
        }
        this.length = length;
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
    public UDP setChecksum(short checksum) {
        this.checksum = checksum;
        return this;
    }

    /**
     * Serializes the packet. Will compute and set the following fields if they
     * are set to specific values at the time serialize is called:
     *      -checksum : 0
     *      -length : 0
     */
    public byte[] serialize() {
        byte[] payloadData = null;
        if (payload != null) {
            payload.setParent(this);
            payloadData = payload.serialize();
        }

        int actualLength = (HEADER_LEN + ((payloadData == null) ? 0
                        : payloadData.length));

        this.length = actualLength;

        byte[] data = new byte[actualLength & 0xffff];
        ByteBuffer bb = ByteBuffer.wrap(data);

        bb.putShort((short)this.sourcePort);
        bb.putShort((short)this.destinationPort);
        bb.putShort((short) this.length);
        bb.putShort(this.checksum);
        if (payloadData != null)
            bb.put(payloadData);

        if (this.parent != null && this.parent instanceof IPv4)
            ((IPv4)this.parent).setProtocol(UDP.PROTOCOL_NUMBER);

        // compute checksum if needed
        if (this.checksum == 0) {
            bb.rewind();
            int accumulation = 0;

            // compute pseudo header mac
            if (this.parent != null && this.parent instanceof IPv4) {
                IPv4 ipv4 = (IPv4) this.parent;
                accumulation += ((ipv4.getSourceAddress() >> 16) & 0xffff)
                        + (ipv4.getSourceAddress() & 0xffff);
                accumulation += ((ipv4.getDestinationAddress() >> 16) & 0xffff)
                        + (ipv4.getDestinationAddress() & 0xffff);
                accumulation += ipv4.getProtocol() & 0xff;
                accumulation += this.length & 0xffff;
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
                accumulation += this.length & 0xffff;
            }

            for (int i = 0; i < this.length / 2; ++i) {
                accumulation += 0xffff & bb.getShort();
            }
            // pad to an even number of shorts
            if (this.length % 2 > 0) {
                accumulation += (bb.get() & 0xff) << 8;
            }

            accumulation = ((accumulation >> 16) & 0xffff)
                    + (accumulation & 0xffff);
            this.checksum = (short) (~accumulation & 0xffff);
            bb.putShort(6, this.checksum);
        }
        return data;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        final int prime = 5807;
        int result = super.hashCode();
        result = prime * result + checksum;
        result = prime * result + destinationPort;
        result = prime * result + length;
        result = prime * result + sourcePort;
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
        if (!(obj instanceof UDP))
            return false;
        UDP other = (UDP) obj;
        if (checksum != other.checksum)
            return false;
        if (destinationPort != other.destinationPort)
            return false;
        if (length != other.length)
            return false;
        if (sourcePort != other.sourcePort)
            return false;
        return true;
    }

    @Override
    public IPacket deserialize(ByteBuffer bb) throws MalformedPacketException {

        if (bb.remaining() < HEADER_LEN || bb.remaining() > MAX_PACKET_LEN) {
            throw new MalformedPacketException("Invalid UDP length: "
                    + bb.remaining());
        }

        this.sourcePort = Unsigned.unsign(bb.getShort());
        this.destinationPort = Unsigned.unsign(bb.getShort());
        this.length = Unsigned.unsign(bb.getShort());
        this.checksum = bb.getShort();

        if (UDP.decodeMap.containsKey(this.destinationPort)) {
            try {
                payload = UDP.decodeMap.get(this.destinationPort).getConstructor().newInstance();
            } catch (Exception e) {
                payload = new Data();
            }
        } else if (UDP.decodeMap.containsKey(this.sourcePort)) {
            try {
                payload = UDP.decodeMap.get(this.sourcePort).getConstructor().newInstance();
            } catch (Exception e) {
                payload = new Data();
            }
        } else {
            payload = new Data();
        }

        int payloadLen = length - HEADER_LEN;
        if (bb.remaining() > payloadLen) {
            bb.limit(bb.position() + payloadLen);
        }

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

}
