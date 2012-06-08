package com.midokura.midolman.packets;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 *
 * @author David Erickson (daviderickson@cs.stanford.edu)
 */
public class DHCPOption {

    public static final Map<Byte, String> codeToName
            = new HashMap<Byte, String>();
    public static enum Code {
        PAD((byte) 0, (byte) 0, "PAD"),
        END((byte) 255, (byte) 0, "END"),
        MASK((byte) 1, (byte) 4, "SUBNET MASK"),
        TIME_OFFSET((byte) 2, (byte) 4, "TIME OFFSET"),
        ROUTER((byte) 3, (byte) 4, "ROUTER"),
        DNS((byte) 6, (byte) 4, "DNS"),
        HOST_NAME((byte) 12, (byte) 1, "HOST NAME"),
        DOMAIN_NAME((byte) 15, (byte) 1, "DOMAIN NAME"),
        INTERFACE_MTU((byte) 26, (byte) 2, "INTERFACE MTU"),
        BCAST_ADDR((byte) 28, (byte) 4, "BROADCAST ADDRESS"),
        NIS_DOMAIN((byte) 40, (byte) 1, "NIS DOMAIN"),
        NIS_SERVERS((byte) 41, (byte) 4, "NIS SERVERS"),
        NTP_SERVERS((byte) 42, (byte) 4, "NTP SERVERS"),
        REQUESTED_IP((byte) 50, (byte) 4, "REQUESTED IP"),
        IP_LEASE_TIME((byte) 51, (byte) 4, "IP LEASE TIME"),
        OPTION_OVERLOAD((byte) 52, (byte) 1, "OPTION OVERLOAD"),
        DHCP_TYPE((byte) 53, (byte) 1, "DHCP MESSAGE TYPE"),
        SERVER_ID((byte) 54, (byte) 4, "SERVER ID"),
        PRM_REQ_LIST((byte) 55, (byte) 1, "PARAMETER REQUEST LIST"),
        ERROR_MESSAGE((byte) 56, (byte) 1, "ERROR MESSAGE");

        private final byte code;
        private final byte length;
        private final String name;

        Code(byte code, byte length, String name) {
            this.code = code;
            this.length = length;
            this.name = name;
            codeToName.put(code, name);
        }

        public byte value() {
            return code;
        }

        public byte length() {
            return length;
        }

        public String getName() {
            return name;
        }
    }

    public static final Map<Byte, String> msgTypeToName
            = new HashMap<Byte, String>();
    public static enum MsgType {
        DISCOVER((byte)1, "DISCOVER"),
        OFFER((byte)2, "OFFER"),
        REQUEST((byte)3, "REQUEST"),
        DECLINE((byte)4, "DECLINE"),
        ACK((byte)5, "ACK"),
        NAK((byte)6, "NAK"),
        RELEASE((byte)7, "RELEASE");

        private final byte value;
        private final String name;

        MsgType(byte value, String name) {
            this.value = value;
            this.name = name;
            msgTypeToName.put(value, name);
        }

        public byte value() {
            return value;
        }

        public String getName() {
            return name;
        }
    }

    protected byte code;
    protected byte length;
    protected byte[] data;

    public DHCPOption() {}

    public DHCPOption(byte code, byte length, byte[] data) {
        this.code = code;
        this.length = length;
        this.data = data;
    }

    /**
     * @return the code
     */
    public byte getCode() {
        return code;
    }

    /**
     * @param code the code to set
     */
    public DHCPOption setCode(byte code) {
        this.code = code;
        return this;
    }

    /**
     * @return the length
     */
    public byte getLength() {
        return length;
    }

    /**
     * @param length the length to set
     */
    public DHCPOption setLength(byte length) {
        this.length = length;
        return this;
    }

    /**
     * @return the data
     */
    public byte[] getData() {
        return data;
    }

    /**
     * @param data the data to set
     */
    public DHCPOption setData(byte[] data) {
        this.data = data;
        return this;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + code;
        result = prime * result + Arrays.hashCode(data);
        result = prime * result + length;
        return result;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (!(obj instanceof DHCPOption))
            return false;
        DHCPOption other = (DHCPOption) obj;
        if (code != other.code)
            return false;
        if (!Arrays.equals(data, other.data))
            return false;
        if (length != other.length)
            return false;
        return true;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return "DHCPOption [code=" + code + ", length=" + length + ", data="
                + Arrays.toString(data) + "]";
    }
}
