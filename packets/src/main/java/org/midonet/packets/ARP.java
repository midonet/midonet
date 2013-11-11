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
import java.util.Arrays;


/**
 *
 * @author David Erickson (daviderickson@cs.stanford.edu)
 */
public class ARP extends BasePacket {
    public final static short ETHERTYPE = 0x0806;

    public final static short HW_TYPE_ETHERNET = 0x1;

    public final static short PROTO_TYPE_IP = 0x800;

    public final static short OP_REQUEST = 0x1;
    public final static short OP_REPLY = 0x2;

    /**
     * ARP packet size.  The ARP itself is 28 bytes.
     */
    public static final int PACKET_SIZE = 28;

    /**
     * The hardware address length as a number of octets.
     */
    private static final int HW_ADDR_LEN = 6;

    /**
     * The maximum prototype address length as a number of octets.
     */
    private static final int MAX_PROTO_ADDR_LEN = 4;

    protected short hardwareType;
    protected short protocolType;
    protected byte hardwareAddressLength;
    protected byte protocolAddressLength;
    protected short opCode;
    protected byte[] senderHardwareAddress;
    protected byte[] senderProtocolAddress;
    protected byte[] targetHardwareAddress;
    protected byte[] targetProtocolAddress;

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("ARP [opcode=").append(opCode);
        sb.append(", hwType=").append(hardwareType);
        sb.append(", protoType=").append(protocolType);
        sb.append(", sha=").append(
                null == senderHardwareAddress ?
                        "null" : MAC.bytesToString(senderHardwareAddress));
        sb.append(", tha=").append(
                null == targetHardwareAddress ?
                        "null" : MAC.bytesToString(targetHardwareAddress));
        sb.append(", spa=").append(
                null == senderProtocolAddress
                        || senderProtocolAddress.length != 4 ? "null"
                        : IPv4Addr.apply(senderProtocolAddress).toString());
        sb.append(", tpa=").append(
                null == targetProtocolAddress
                        || targetProtocolAddress.length != 4 ? "null"
                        : IPv4Addr.apply(targetProtocolAddress).toString());
        sb.append("]");
        return sb.toString();
    }

    /**
     * @return the hardwareType
     */
    public short getHardwareType() {
        return hardwareType;
    }

    /**
     * @param hardwareType the hardwareType to set
     */
    public ARP setHardwareType(short hardwareType) {
        this.hardwareType = hardwareType;
        return this;
    }

    /**
     * @return the protocolType
     */
    public short getProtocolType() {
        return protocolType;
    }

    /**
     * @param protocolType the protocolType to set
     */
    public ARP setProtocolType(short protocolType) {
        this.protocolType = protocolType;
        return this;
    }

    /**
     * @return the hardwareAddressLength
     */
    public byte getHardwareAddressLength() {
        return hardwareAddressLength;
    }

    /**
     * @param hardwareAddressLength the hardwareAddressLength to set
     */
    public ARP setHardwareAddressLength(byte hardwareAddressLength) {
        this.hardwareAddressLength = hardwareAddressLength;
        return this;
    }

    /**
     * @return the protocolAddressLength
     */
    public byte getProtocolAddressLength() {
        return protocolAddressLength;
    }

    /**
     * @param protocolAddressLength the protocolAddressLength to set
     */
    public ARP setProtocolAddressLength(byte protocolAddressLength) {
        this.protocolAddressLength = protocolAddressLength;
        return this;
    }

    /**
     * @return the opCode
     */
    public short getOpCode() {
        return opCode;
    }

    /**
     * @param opCode the opCode to set
     */
    public ARP setOpCode(short opCode) {
        this.opCode = opCode;
        return this;
    }

    /**
     * @return the senderHardwareAddress
     */
    public MAC getSenderHardwareAddress() {
        return MAC.fromAddress(senderHardwareAddress);
    }

    /**
     * @param senderHardwareAddress the senderHardwareAddress to set
     */
    public ARP setSenderHardwareAddress(MAC senderHardwareAddress) {
        this.senderHardwareAddress = senderHardwareAddress.getAddress();
        return this;
    }

    /**
     * @return the senderProtocolAddress
     */
    public byte[] getSenderProtocolAddress() {
        return senderProtocolAddress;
    }

    /**
     * @param senderProtocolAddress the senderProtocolAddress to set
     */
    public ARP setSenderProtocolAddress(byte[] senderProtocolAddress) {
        this.senderProtocolAddress = senderProtocolAddress;
        return this;
    }

    /**
     * @return the targetHardwareAddress
     */
    public MAC getTargetHardwareAddress() {
        return MAC.fromAddress(targetHardwareAddress);
    }

    /**
     * @param targetHardwareAddress the targetHardwareAddress to set
     */
    public ARP setTargetHardwareAddress(MAC targetHardwareAddress) {
        this.targetHardwareAddress = targetHardwareAddress.getAddress();
        return this;
    }

    /**
     * @return the targetProtocolAddress
     */
    public byte[] getTargetProtocolAddress() {
        return targetProtocolAddress;
    }

    /**
     * @param targetProtocolAddress the targetProtocolAddress to set
     */
    public ARP setTargetProtocolAddress(byte[] targetProtocolAddress) {
        this.targetProtocolAddress = targetProtocolAddress;
        return this;
    }

    @Override
    public byte[] serialize() {
        int length = 8 + (2 * (0xff & this.hardwareAddressLength))
                + (2 * (0xff & this.protocolAddressLength));
        byte[] data = new byte[length];
        ByteBuffer bb = ByteBuffer.wrap(data);
        bb.putShort(this.hardwareType);
        bb.putShort(this.protocolType);
        bb.put(this.hardwareAddressLength);
        bb.put(this.protocolAddressLength);
        bb.putShort(this.opCode);
        bb.put(this.senderHardwareAddress, 0, 0xff & this.hardwareAddressLength);
        bb.put(this.senderProtocolAddress, 0, 0xff & this.protocolAddressLength);
        bb.put(this.targetHardwareAddress, 0, 0xff & this.hardwareAddressLength);
        bb.put(this.targetProtocolAddress, 0, 0xff & this.protocolAddressLength);
        return data;
    }

    @Override
    public IPacket deserialize(ByteBuffer bb) throws MalformedPacketException {

        // Check that the size is correct to avoid BufferUnderflowException.
        if (bb.remaining() < PACKET_SIZE) {
            throw new MalformedPacketException("Invalid ARP packet size: "
                    + bb.remaining());
        }

        this.hardwareType = bb.getShort();
        this.protocolType = bb.getShort();
        this.hardwareAddressLength = bb.get();
        int hwAddrLen = 0xff & this.hardwareAddressLength;
        if (hwAddrLen != HW_ADDR_LEN) {
            // Check the length to avoid BufferUnderflowException.
            // Only MAC address of 6 bytes is currently supported.
            throw new MalformedPacketException(
                    "Invalid hardware address len: " + hwAddrLen);
        }

        this.protocolAddressLength = bb.get();
        int protoAddrLen = 0xff & this.protocolAddressLength;
        if (protoAddrLen != MAX_PROTO_ADDR_LEN) {
            // Check the max length to avoid BufferUnderflowException
            throw new MalformedPacketException(
                    "Invalid protocol address len: " + protoAddrLen);
        }

        this.opCode = bb.getShort();
        this.senderHardwareAddress = new byte[hwAddrLen];
        bb.get(this.senderHardwareAddress);
        this.senderProtocolAddress = new byte[protoAddrLen];
        bb.get(this.senderProtocolAddress);
        this.targetHardwareAddress = new byte[hwAddrLen];
        bb.get(this.targetHardwareAddress);
        this.targetProtocolAddress = new byte[protoAddrLen];
        bb.get(this.targetProtocolAddress);

        return this;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        final int prime = 13121;
        int result = super.hashCode();
        result = prime * result + hardwareAddressLength;
        result = prime * result + hardwareType;
        result = prime * result + opCode;
        result = prime * result + protocolAddressLength;
        result = prime * result + protocolType;
        result = prime * result + Arrays.hashCode(senderHardwareAddress);
        result = prime * result + Arrays.hashCode(senderProtocolAddress);
        result = prime * result + Arrays.hashCode(targetHardwareAddress);
        result = prime * result + Arrays.hashCode(targetProtocolAddress);
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
        if (!(obj instanceof ARP))
            return false;
        ARP other = (ARP) obj;
        if (hardwareAddressLength != other.hardwareAddressLength)
            return false;
        if (hardwareType != other.hardwareType)
            return false;
        if (opCode != other.opCode)
            return false;
        if (protocolAddressLength != other.protocolAddressLength)
            return false;
        if (protocolType != other.protocolType)
            return false;
        if (!Arrays.equals(senderHardwareAddress, other.senderHardwareAddress))
            return false;
        if (!Arrays.equals(senderProtocolAddress, other.senderProtocolAddress))
            return false;
        if (!Arrays.equals(targetHardwareAddress, other.targetHardwareAddress))
            return false;
        if (!Arrays.equals(targetProtocolAddress, other.targetProtocolAddress))
            return false;
        return true;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#toString()
    @Override
    public String toString() {
        return "ARP [hardwareType=" + hardwareType + ", protocolType="
                + protocolType + ", hardwareAddressLength="
                + hardwareAddressLength + ", protocolAddressLength="
                + protocolAddressLength + ", opCode=" + opCode
                + ", senderHardwareAddress="
                + Arrays.toString(senderHardwareAddress)
                + ", senderProtocolAddress="
                + Arrays.toString(senderProtocolAddress)
                + ", targetHardwareAddress="
                + Arrays.toString(targetHardwareAddress)
                + ", targetProtocolAddress="
                + Arrays.toString(targetProtocolAddress) + "]";
    }
     */

    public static Ethernet makeArpReply(MAC sha, MAC tha,
                                        byte[] spa, byte[] tpa) {
        ARP arp = new ARP();
        arp.setHardwareType(ARP.HW_TYPE_ETHERNET);
        arp.setProtocolType(ARP.PROTO_TYPE_IP);
        arp.setHardwareAddressLength((byte)6);
        arp.setProtocolAddressLength((byte)4);
        arp.setOpCode(ARP.OP_REPLY);
        arp.setSenderHardwareAddress(sha);
        arp.setSenderProtocolAddress(spa);
        arp.setTargetHardwareAddress(tha);
        arp.setTargetProtocolAddress(tpa);

        Ethernet eth = new Ethernet();
        eth.setPayload(arp);
        eth.setSourceMACAddress(sha);
        eth.setDestinationMACAddress(tha);
        eth.setEtherType(ARP.ETHERTYPE);
        return eth;
    }
}
