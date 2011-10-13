package com.midokura.midolman.packets;

import java.util.Arrays;

/**
 *
 * @author David Erickson (daviderickson@cs.stanford.edu)
 */
public class DHCPOption {
    public enum Code {
        END((byte) 255, (byte) 0),
        MASK((byte) 1, (byte) 4),
        ROUTER((byte) 3, (byte) 4),
        DNS((byte) 6, (byte) 4),
        DHCP_TYPE((byte) 53, (byte) 1);

        private final byte code;
        private final byte length;

        Code(byte code, byte length) {
            this.code = code;
            this.length = length;
        }

        public byte value() {
            return code;
        }

        public byte length() {
            return length;
        }
    }

    public static final byte DISCOVER = 1;
    public static final byte OFFER = 2;
    public static final byte REQUEST = 3;

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
