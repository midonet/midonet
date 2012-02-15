/*
 * @(#)OfInPortNxmEntry.java        1.6 11/12/27
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.openflow.nxm;

import java.nio.ByteBuffer;

public abstract class ShortNomaskNxmEntry implements NxmEntry {

    protected short value;

    public ShortNomaskNxmEntry(short value) {
        this.value = value;
    }

    // Default constructor for class.newInstance used by NxmType.
    public ShortNomaskNxmEntry() {
    }

    /**
     * @return the value
     */
    public short getValue() {
        return value;
    }

    /**
     * @return the value
     */
    public int getValueAsInt() {
        return value & 0xffff;
    }

    @Override
    public NxmRawEntry createRawNxmEntry() {
        byte[] valueBytes = new byte[2];
        ByteBuffer buff = ByteBuffer.wrap(valueBytes).putShort(value);
        return new NxmRawEntry(getNxmType(), valueBytes);
    }

    @Override
    public NxmEntry fromRawEntry(NxmRawEntry rawEntry) {
        if (rawEntry.getType() != getNxmType())
            throw new IllegalArgumentException("Cannot make an entry of type " +
                    getClass() + " from a raw entry of type " +
                    rawEntry.getType());
        ByteBuffer buff = ByteBuffer.wrap(rawEntry.getValue());
        value = buff.getShort();
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        ShortNomaskNxmEntry that = (ShortNomaskNxmEntry) o;

        if (value != that.value) return false;

        return true;
    }

    @Override
    public int hashCode() {
        return (int) value;
    }
}
