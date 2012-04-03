/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.openflow.nxm;

import java.nio.ByteBuffer;

/**
 * // TODO: Explain yourself.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 4/3/12
 */
public abstract class IntNomaskNxmEntry implements NxmEntry {

    protected int value;

    public IntNomaskNxmEntry(int value) {
        this.value = value;
    }

    // Default constructor for class.newInstance used by NxmType.
    public IntNomaskNxmEntry() {
    }

    /**
     * @return the value
     */
    public int getValue() {
        return value;
    }

    @Override
    public NxmRawEntry createRawNxmEntry() {
        byte[] valueBytes = new byte[4];
        ByteBuffer buff = ByteBuffer.wrap(valueBytes).putInt(value);
        return new NxmRawEntry(getNxmType(), valueBytes);
    }

    @Override
    public NxmEntry fromRawEntry(NxmRawEntry rawEntry) {
        if (rawEntry.getType() != getNxmType())
            throw new IllegalArgumentException("Cannot make an entry of type " +
                                                   getClass() + " from a raw entry of type " +
                                                   rawEntry.getType());
        ByteBuffer buff = ByteBuffer.wrap(rawEntry.getValue());
        value = buff.getInt();
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        IntNomaskNxmEntry that = (IntNomaskNxmEntry) o;

        if (value != that.value) return false;

        return true;
    }

    @Override
    public int hashCode() {
        return value;
    }
}