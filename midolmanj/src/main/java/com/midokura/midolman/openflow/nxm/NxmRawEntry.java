/*
 * @(#)NxmRawEntry.java        1.6 11/12/27
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.openflow.nxm;

/*
 * @(#)NxmRawEntry.java        1.6 11/12/27
 *
 * Copyright 2011 Midokura KK
 */
public class NxmRawEntry {

    private final NxmType type;
    private final boolean hasMask;
    private final byte[] value;
    private final byte[] mask;

    public NxmRawEntry(NxmType type, byte[] value) {
        this(type, value, false, null);
    }

    public NxmRawEntry(NxmType type, byte[] value, boolean hasMask, byte[] mask) {

        // Perform sanity checks to make sure we can construct a valid object.
        if (type == null) {
            throw new IllegalArgumentException("Type is null");
        }

        if (value == null) {
            throw new IllegalArgumentException("Value is null");
        }

        if (value.length != type.getLen()) {
            throw new IllegalArgumentException("Value has an invalid size");
        }

        if (hasMask) {
            if (mask == null) {
                throw new IllegalArgumentException("Mask is null but " +
                        "hasMask is true.");
            }

            if (!type.isMaskable()) {
                throw new IllegalArgumentException(
                        "This NxmType is not maskable: " + type);
            }

            if (mask.length != value.length) {
                throw new IllegalArgumentException(
                        "Mask length does not match value length.");
            }
        }
        else if (mask != null)
            throw new IllegalArgumentException("Mask is not null but " +
                    "hasMask is false.");

        this.type = type;
        this.hasMask = hasMask;
        this.value = value;
        this.mask = mask;
    }

    /**
     * @return the type
     */
    public NxmType getType() {
        return this.type;
    }

    /**
     * @return True if the header indicates that there is a mask.
     */
    public boolean hasMask() {
        return this.hasMask;
    }

    /**
     * @return the value
     */
    public byte[] getValue() {
        return this.value;
    }

    /**
     * @return the mask
     */
    public byte[] getMask() {
        return this.mask;
    }

    public int getHeader() {
        return this.type.makeHeader(this.hasMask);
    }
}