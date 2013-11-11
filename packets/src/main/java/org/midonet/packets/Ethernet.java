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
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.midonet.packets.Unsigned.unsign;


/**
 * @author David Erickson (daviderickson@cs.stanford.edu)
 */
public class Ethernet extends BasePacket {
    private static String HEXES = "0123456789ABCDEF";

    /**
     * Mininum number of octets for an Ethernet packet header, without the
     * optional fields.  Preamble and frame check sequence are not included.
     */
    public static final int MIN_HEADER_LEN = 14;

    /**
     * EtherType that indicates the presence of a VLAN TAG
     */
    public final static short VLAN_TAGGED_FRAME = (short)0x8100;

    /**
     * EthernetType used to indicate the presence of multiple VLAN tags
     * (Provider bridging, QinQ)
     */
    public final static short PROVIDER_BRIDGING_TAG = (short)0x88A8;

    /**
     * The number of octets in the optional TPID field.
     */
    public static final int HEADER_TPID_LEN = 4;

    public static Map<Short, Class<? extends IPacket>> etherTypeClassMap;

    static {
        etherTypeClassMap = new HashMap<Short, Class<? extends IPacket>>();
        etherTypeClassMap.put(ARP.ETHERTYPE, ARP.class);
        etherTypeClassMap.put(IPv4.ETHERTYPE, IPv4.class);
        etherTypeClassMap.put(IPv6.ETHERTYPE, IPv6.class);
        etherTypeClassMap.put(LLDP.ETHERTYPE, LLDP.class);
    }

    protected byte[] destinationMACAddress;
    protected byte[] sourceMACAddress;
    protected byte priorityCode;
    protected List<Short> vlanIDs = new ArrayList<Short>();
    protected short etherType;
    protected boolean pad = false;

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Ethernet [dlSrc=");
        sb.append(null == sourceMACAddress ?
                        "null" : MAC.bytesToString(sourceMACAddress));
        sb.append(", dlDst=").append(
                null == destinationMACAddress ?
                        "null" : MAC.bytesToString(destinationMACAddress));
        sb.append(", vlanId=").append(vlanIDs);
        sb.append(", etherType=0x");
        sb.append(Integer.toHexString(unsign(etherType))).append(", payload=");
        sb.append(null == payload ? "null" : payload.toString());
        sb.append("]");
        return sb.toString();
    }

    /**
     * @return the destinationMACAddress
     */
    public MAC getDestinationMACAddress() {
        return MAC.fromAddress(destinationMACAddress);
    }

    /**
     * @param destinationMACAddress the destinationMACAddress to set
     */
    public Ethernet setDestinationMACAddress(MAC destinationMACAddress) {
        this.destinationMACAddress = destinationMACAddress.getAddress();
        return this;
    }

    /**
     * @return the sourceMACAddress
     */
    public MAC getSourceMACAddress() {
        return MAC.fromAddress(sourceMACAddress);
    }

    /**
     * @param sourceMACAddress the sourceMACAddress to set
     */
    public Ethernet setSourceMACAddress(MAC sourceMACAddress) {
        this.sourceMACAddress = sourceMACAddress.getAddress();
        return this;
    }

    /**
     * @return the priorityCode
     */
    public byte getPriorityCode() {
        return priorityCode;
    }

    /**
     * @param priorityCode the priorityCode to set
     */
    public Ethernet setPriorityCode(byte priorityCode) {
        this.priorityCode = priorityCode;
        return this;
    }

    /**
     * @return the vlanID
     */
    public List<Short> getVlanIDs() {
        return vlanIDs;
    }

    /**
     * @param vlanID the vlanID to set
     */
    public Ethernet setVlanID(short vlanID) {
        this.vlanIDs.add(vlanID);
        return this;
    }

    /**
     * @param vlanID the vlanIDs to set
     */
    public Ethernet setVlanIDs(List<Short> vlanIDs) {
        this.vlanIDs.addAll(vlanIDs);
        return this;
    }

    /**
     * @return the etherType
     */
    public short getEtherType() {
        return etherType;
    }

    /**
     * @param etherType the etherType to set
     */
    public Ethernet setEtherType(short etherType) {
        this.etherType = etherType;
        return this;
    }

    /**
     * Pad this packet to 60 bytes minimum, filling with zeros?
     * @return the pad
     */
    public boolean isPad() {
        return pad;
    }

    /**
     * Pad this packet to 60 bytes minimum, filling with zeros?
     * @param pad the pad to set
     */
    public Ethernet setPad(boolean pad) {
        this.pad = pad;
        return this;
    }

    public byte[] serialize() {
        byte[] payloadData = null;
        if (payload != null) {
            payload.setParent(this);
            payloadData = payload.serialize();
        }
        int length = 14 + (vlanIDs.size() * 4) + ((payloadData == null) ? 0 : payloadData.length);
        if (pad && length < 60) {
            length = 60;
        }
        byte[] data = new byte[length];
        ByteBuffer bb = ByteBuffer.wrap(data);
        bb.put(destinationMACAddress);
        bb.put(sourceMACAddress);
        for (Iterator<Short> it = vlanIDs.iterator(); it.hasNext();) {
            short vlanID = it.next();
            // if it's the last tag we need to use the VLAN_TAGGED_FRAME type
            bb.putShort(it.hasNext() ? PROVIDER_BRIDGING_TAG : VLAN_TAGGED_FRAME);
            bb.putShort((short) ((priorityCode << 13) | (vlanID & 0x0fff)));
        }
        bb.putShort(etherType);
        if (payloadData != null)
            bb.put(payloadData);
        if (pad) {
            Arrays.fill(data, bb.position(), data.length, (byte)0x0);
        }
        return data;
    }

    public static Ethernet deserialize(byte[] data)
            throws MalformedPacketException {
        ByteBuffer bb = ByteBuffer.wrap(data, 0, data.length);
        Ethernet ethPkt = new Ethernet();
        ethPkt.deserialize(bb);
        return ethPkt;
    }

    @Override
    public IPacket deserialize(ByteBuffer bb) throws MalformedPacketException {

        // Check that the size is correct to avoid BufferUnderflowException.
        // Don't check the upper boundary since this method may need to handle
        // Super Jumbo frames.
        if (bb.remaining() < MIN_HEADER_LEN) {
            throw new MalformedPacketException("Invalid ethernet frame size: "
                    + bb.remaining());
        }

        bb.order(ByteOrder.BIG_ENDIAN);

        if (this.destinationMACAddress == null)
            this.destinationMACAddress = new byte[6];
        bb.get(this.destinationMACAddress);

        if (this.sourceMACAddress == null)
            this.sourceMACAddress = new byte[6];
        bb.get(this.sourceMACAddress);

        short etherType = bb.getShort();
        while (etherType == VLAN_TAGGED_FRAME || etherType == PROVIDER_BRIDGING_TAG) {
            // Check the buffer length.
            if (bb.remaining() < HEADER_TPID_LEN) {
                throw new MalformedPacketException("Not enough buffer for "
                        + "TPID fields: " + bb.remaining());
            }
            short tci = bb.getShort();
            this.priorityCode = (byte) ((tci >> 13) & 0x07);
            this.vlanIDs.add((short) (tci & 0x0fff));
            etherType = bb.getShort();
        }
        this.etherType = etherType;

        if (Ethernet.etherTypeClassMap.containsKey(this.etherType)) {
            Class<? extends IPacket> clazz = Ethernet.etherTypeClassMap.get(this.etherType);
            try {
                this.payload = clazz.newInstance().deserialize(bb.slice());
            } catch (Exception e) {
                this.payload = (new Data()).deserialize(bb.slice());
            }
        } else {
            this.payload = (new Data()).deserialize(bb.slice());
        }
        this.payload.setParent(this);
        return this;
    }

    public boolean isMcast() {
        return isMcast(destinationMACAddress);
    }

    public static boolean isMcast(MAC mac) {
        return (mac == null) ? false : isMcast(mac.getAddress());
    }

    private static boolean isMcast(byte[] mac) {
        return 0 != (mac[0] & 0x01);
    }

    public boolean isBroadcast() {
        return isBroadcast(destinationMACAddress);
    }

    public static boolean isBroadcast(MAC mac) {
        return isBroadcast(mac.getAddress());
    }

    private static boolean isBroadcast(byte[] mac) {
        if (mac.length != 6) {
            return false;
        }

        for (int i=0; i<6; i++) {
            if ((mac[i] & 0xFF) != 0xFF) {
                return false;
            }
        }

        return true;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        final int prime = 7867;
        int result = super.hashCode();
        result = prime * result + Arrays.hashCode(destinationMACAddress);
        result = prime * result + etherType;
        result = prime * result + priorityCode << 16 + vlanIDs.hashCode();
        result = prime * result + (pad ? 1231 : 1237);
        result = prime * result + Arrays.hashCode(sourceMACAddress);
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
        if (!(obj instanceof Ethernet))
            return false;
        Ethernet other = (Ethernet) obj;
        if (!Arrays.equals(destinationMACAddress, other.destinationMACAddress))
            return false;
        if (priorityCode != other.priorityCode)
            return false;
        if (!vlanIDs.equals(other.vlanIDs))
            return false;
        if (etherType != other.etherType)
            return false;
        if (pad != other.pad)
            return false;
        if (!Arrays.equals(sourceMACAddress, other.sourceMACAddress))
            return false;
        return true;
    }
}
