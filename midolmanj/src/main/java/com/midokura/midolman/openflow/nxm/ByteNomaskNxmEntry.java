/*
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.openflow.nxm;

import java.nio.ByteBuffer;

public abstract class ByteNomaskNxmEntry implements NxmEntry {

    protected byte value;

    public ByteNomaskNxmEntry(byte value) {
        this.value = value;
    }

    // Default constructor for class.newInstance used by NxmType.
    public ByteNomaskNxmEntry() {
    }

    /**
     * @return the value
     */
    public byte getValue() {
        return value;
    }

    /**
     * @return the value
     */
    public int getValueAsInt() {
        return value & 0xff;
    }

    @Override
    public NxmRawEntry createRawNxmEntry() {
        byte[] valueBytes = new byte[1];
        ByteBuffer buff = ByteBuffer.wrap(valueBytes).put(value);
        return new NxmRawEntry(getNxmType(), valueBytes);
    }

    @Override
    public NxmEntry fromRawEntry(NxmRawEntry rawEntry) {
        if (rawEntry.getType() != getNxmType())
            throw new IllegalArgumentException("Cannot make an entry of type " +
                    getClass() + " from a raw entry of type " +
                    rawEntry.getType());
        ByteBuffer buff = ByteBuffer.wrap(rawEntry.getValue());
        value = buff.get();
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        ByteNomaskNxmEntry that = (ByteNomaskNxmEntry) o;

        if (value != that.value) return false;

        return true;
    }

    @Override
    public int hashCode() {
        return (int) value;
    }
}
