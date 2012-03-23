package com.midokura.midolman.packets;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import com.midokura.midolman.util.Net;


/**
 * FIXME(dmd): Record the license this is under here.
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
     * The number of octets in the optional TPID field.
     */
    public static final int HEADER_TPID_LEN = 4;

    public static Map<Short, Class<? extends IPacket>> etherTypeClassMap;

    static {
        etherTypeClassMap = new HashMap<Short, Class<? extends IPacket>>();
        etherTypeClassMap.put(ARP.ETHERTYPE, ARP.class);
        etherTypeClassMap.put(IPv4.ETHERTYPE, IPv4.class);
        etherTypeClassMap.put(LLDP.ETHERTYPE, LLDP.class);
    }

    protected byte[] destinationMACAddress;
    protected byte[] sourceMACAddress;
    protected byte priorityCode;
    protected short vlanID;
    protected short etherType;
    protected boolean pad = false;

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Ethernet [dlSrc=");
        sb.append(null == sourceMACAddress ? "null" :
                        Net.convertByteMacToString(sourceMACAddress));
        sb.append(", dlDst=").append(
                null == destinationMACAddress ? "null" :
                        Net.convertByteMacToString(destinationMACAddress));
        sb.append(", etherType=").append(etherType).append(", payload=");
        sb.append(null == payload ? "null" : payload.toString());
        sb.append("]");
        return sb.toString();
    }

    /**
     * @return the destinationMACAddress
     */
    public MAC getDestinationMACAddress() {
        return new MAC(destinationMACAddress);
    }

    /**
     * @param destinationMACAddress the destinationMACAddress to set
     */
    public Ethernet setDestinationMACAddress(MAC destinationMACAddress) {
        this.destinationMACAddress = destinationMACAddress.address;
        return this;
    }

    /**
     * @return the sourceMACAddress
     */
    public MAC getSourceMACAddress() {
        return new MAC(sourceMACAddress);
    }

    /**
     * @param sourceMACAddress the sourceMACAddress to set
     */
    public Ethernet setSourceMACAddress(MAC sourceMACAddress) {
        this.sourceMACAddress = sourceMACAddress.address;
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
    public short getVlanID() {
        return vlanID;
    }

    /**
     * @param vlanID the vlanID to set
     */
    public Ethernet setVlanID(short vlanID) {
        this.vlanID = vlanID;
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
        int length = 14 + ((vlanID == 0) ? 0 : 4) + ((payloadData == null) ? 0 : payloadData.length);
        if (pad && length < 60) {
            length = 60;
        }
        byte[] data = new byte[length];
        ByteBuffer bb = ByteBuffer.wrap(data);
        bb.put(destinationMACAddress);
        bb.put(sourceMACAddress);
        if (vlanID != 0) {
            bb.putShort((short) 0x8100);
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

    @Override
    public IPacket deserialize(ByteBuffer bb) throws MalformedPacketException {

        // Check that the size is correct to avoid BufferUnderflowException.
        // Don't check the upper boundary since this method may need to handle
        // Super Jumbo frames.
        if (bb.remaining() < MIN_HEADER_LEN) {
            throw new MalformedPacketException("Invalid ethernet frame size: "
                    + bb.remaining());
        }

        if (this.destinationMACAddress == null)
            this.destinationMACAddress = new byte[6];
        bb.get(this.destinationMACAddress);

        if (this.sourceMACAddress == null)
            this.sourceMACAddress = new byte[6];
        bb.get(this.sourceMACAddress);

        short etherType = bb.getShort();
        if (etherType == (short) 0x8100) {
            // Check the buffer length.
            if (bb.remaining() < HEADER_TPID_LEN) {
                throw new MalformedPacketException("Not enough buffer for "
                        + "TPID fields: " + bb.remaining());
            }
            short tci = bb.getShort();
            this.priorityCode = (byte) ((tci >> 13) & 0x07);
            this.vlanID = (short) (tci & 0x0fff);
            etherType = bb.getShort();
        }
        this.etherType = etherType;

        IPacket payload;
        if (Ethernet.etherTypeClassMap.containsKey(this.etherType)) {
            Class<? extends IPacket> clazz = Ethernet.etherTypeClassMap.get(this.etherType);
            try {
                payload = clazz.newInstance();
            } catch (Exception e) {
                throw new RuntimeException("Error parsing payload for Ethernet packet", e);
            }
        } else {
            payload = new Data();
        }
        this.payload = payload.deserialize(bb.slice());
        this.payload.setParent(this);
        return this;
    }

    /**
     * Accepts a MAC address of the form 00:aa:11:bb:22:cc, case does not
     * matter, and returns a corresponding byte[].
     * @param macAddress
     * @return
     */
    static byte[] toMACAddress(String macAddress) {
        byte[] address = new byte[6];
        String[] macBytes = macAddress.split(":");
        if (macBytes.length != 6)
            throw new IllegalArgumentException(
                    "Specified MAC Address must contain 12 hex digits" +
                    " separated pairwise by :'s.");
        for (int i = 0; i < 6; ++i) {
            address[i] = (byte) ((HEXES.indexOf(macBytes[i].toUpperCase()
                    .charAt(0)) << 4) | HEXES.indexOf(macBytes[i].toUpperCase()
                    .charAt(1)));
        }

        return address;
    }

    /**
     * Accepts a MAC address and returns the corresponding long, where the
     * MAC bytes are set on the lower order bytes of the long.
     * @param macAddress
     * @return a long containing the mac address bytes
     */
    public static long toLong(byte[] macAddress) {
        long mac = 0;
        for (int i = 0; i < 6; i++) {
          long t = (macAddress[i] & 0xffL) << ((5-i)*8);
          mac |= t;
        }
        return mac;
    }

    public boolean isMcast() {
        return isMcast(destinationMACAddress);
    }

    public static boolean isMcast(MAC mac) {
        return isMcast(mac.getAddress());
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
            if (mac[i] != 0xFF) {
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
        if (vlanID != other.vlanID)
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
