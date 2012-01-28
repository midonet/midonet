package com.midokura.midolman.packets;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 *
 * @author David Erickson (daviderickson@cs.stanford.edu)
 */
public class Data extends BasePacket {
    protected byte[] data;

    /**
     * 
     */
    public Data() {
    }

    /**
     * @param data
     */
    public Data(byte[] data) {
        this.data = data;
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
    public Data setData(byte[] data) {
        this.data = data;
        return this;
    }

    public byte[] serialize() {
        return this.data;
    }

    @Override
    public IPacket deserialize(ByteBuffer bb) {
        this.data = new byte[bb.remaining()];
        bb.get(this.data);
        return this;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        final int prime = 1571;
        int result = super.hashCode();
        result = prime * result + Arrays.hashCode(data);
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
        if (!(obj instanceof Data))
            return false;
        Data other = (Data) obj;
        if (!Arrays.equals(data, other.data))
            return false;
        return true;
    }
}
