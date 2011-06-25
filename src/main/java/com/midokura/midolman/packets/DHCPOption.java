package com.midokura.midolman.packets;

import java.util.Arrays;

/**
 *
 * @author David Erickson (daviderickson@cs.stanford.edu)
 */
public class DHCPOption {
    protected byte code;
    protected byte length;
    protected byte[] data;

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
