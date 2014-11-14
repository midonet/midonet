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
import java.util.Arrays;

/**
 * @author David Erickson (daviderickson@cs.stanford.edu)
 */
public class LLDPTLV {

    public static final byte TYPE_END_OF_LLDPDU_TLV = 0x0;
    public static final byte TYPE_CHASSIS_ID = 0x1;
    public static final byte TYPE_PORT_ID= 0x2;
    public static final byte TYPE_TTL = 0x3;

    protected byte type;
    protected short length;
    protected byte[] value;

    /**
     * Minimum length of the TLV section of LLDP packet.
     */
    public final static int MIN_TLV_LEN = 2;

    /**
     * @return the type
     */
    public byte getType() {
        return type;
    }

    /**
     * @param type the type to set
     */
    public LLDPTLV setType(byte type) {
        this.type = type;
        return this;
    }

    /**
     * @return the length
     */
    public short getLength() {
        return length;
    }

    /**
     * @param length the length to set
     */
    public LLDPTLV setLength(short length) {
        this.length = length;
        return this;
    }

    /**
     * @return the value
     */
    public byte[] getValue() {
        return value;
    }

    /**
     * @param value the value to set
     */
    public LLDPTLV setValue(byte[] value) {
        this.value = value;
        return this;
    }

    public byte[] serialize() {
        // type = 7 bits
        // info string length 9 bits, each value == byte
        // info string
        short scratch = (short) (((0x7f & this.type) << 9) | (0x1ff & this.length));
        byte[] data = new byte[2+this.length];
        ByteBuffer bb = ByteBuffer.wrap(data);
        bb.putShort(scratch);
        if (this.value != null)
            bb.put(this.value);
        return data;
    }

    public LLDPTLV deserialize(ByteBuffer bb)
            throws MalformedPacketException {
        if (bb.remaining() < MIN_TLV_LEN) {
            throw new MalformedPacketException("Invalid LLDPTLV len: "
                + bb.remaining());
        }

        short sscratch;
        sscratch = bb.getShort();
        this.type = (byte) ((sscratch >> 9) & 0x7f);
        this.length = (short) (sscratch & 0x1ff);

        if (bb.remaining() < this.length) {
            throw new MalformedPacketException("LLDPTLV len: "
                    + this.length + " does not match the remaining size: "
                    + bb.remaining());
        }

        if (this.length > 0) {
            this.value = new byte[this.length];
            bb.get(this.value);
        }

        return this;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        final int prime = 1423;
        int result = 1;
        result = prime * result + length;
        result = prime * result + type;
        result = prime * result + Arrays.hashCode(value);
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
        if (!(obj instanceof LLDPTLV))
            return false;
        LLDPTLV other = (LLDPTLV) obj;
        if (length != other.length)
            return false;
        if (type != other.type)
            return false;
        if (!Arrays.equals(value, other.value))
            return false;
        return true;
    }
}
