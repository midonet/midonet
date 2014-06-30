/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
 */
package org.midonet.netlink;

import java.nio.ByteBuffer;

/**
 * Helper class for writing and reading Netlink messages and attributes.
 */
public final class NetlinkMessage {
    private NetlinkMessage() { }

    static public final short NLA_F_NESTED = (short) (1 << 15);
    static public final short NLA_F_NET_BYTEORDER = (1 << 14);
    static public final short NLA_TYPE_MASK = ~NLA_F_NESTED | NLA_F_NET_BYTEORDER;

    /** Write onto a ByteBuffer a netlink attribute header.
     *  @param buffer the ByteBuffer the header is written onto.
     *  @param id the short id associated with the value type (nla_type).
     *  @param len a logical 2B short indicating the length in bytes of the
     *             attribute value written after the header. 4 bytes are added
     *             to it to account for the additional header size and conform
     *             to nla_len semantics. */
    public static void setAttrHeader(ByteBuffer buffer, short id, int len) {
        buffer.putShort((short)len);
        buffer.putShort(id);
    }

    /** Generic attribute writing function that can write an arbitrary value
     *  into a ByteBuffer, given a translator typeclass instance for that value
     *  type. Padding for 4B alignement is added to the message. Returns the
     *  total number of bytes written in the buffer. */
    public static <V> int writeAttrWithId(ByteBuffer buffer, short id, V value,
                                          Writer<V> translator) {

        int start = buffer.position();      // save position

        NetlinkMessage.setAttrHeader(buffer, id, 4); // space for nl_attr header

        translator.serializeInto(buffer, value);

        // write nl_attr length field
        buffer.putShort(start, (short)(buffer.position() - start));
        alignBuffer(buffer);

        return buffer.position() - start;
    }

    public static <V> int writeAttr(ByteBuffer buffer, V value,
                                    Writer<V> translator) {
        short id = translator.attrIdOf(value);
        return writeAttrWithId(buffer, id, value, translator);
    }

    /** write a C String attribute with null terminator, with header. */
    public static int writeStringAttr(ByteBuffer buf, short id, String value) {
        return writeAttrWithId(buf, id, value, stringSerializer);
    }

    /** write an attribute as a raw stream of bytes, with header. */
    public static int writeRawAttribute(ByteBuffer buf, short id, byte[] value) {
        return writeAttrWithId(buf, id, value, bytesSerializer);
    }

    /** Generic attribute sequence writing function that can write an arbitrary
     *  sequence of value into a ByteBuffer as a netlink nested attribute, given
     *  a translator typeclass instance for the type of these values and the id
     *  of the nested attribute. The message is 4B aligned. Returns the total
     *  number of bytes written in the buffer. */
    public static <V> int writeAttrSeq(ByteBuffer buffer, short id,
                                       Iterable<V> values,
                                       Writer<V> translator) {

        int start = buffer.position(); // save position for writing the
                                       // nla_len field after iterating

        int nByte = 4; // header length

        // when writing the attribute id of a sequence or compound attribute (a
        // struct of attributes), it needs to be flagged with the "nested" bit.
        NetlinkMessage.setAttrHeader(buffer, nested(id), 0); // len yet unknown

        for (V v : values) {
            nByte += writeAttr(buffer, v, translator);
        }

        buffer.putShort(start, (short) (buffer.position() - start));

        return nByte;
    }

    /** write a 8B long netlink attribute into a buffer, with header. */
    public static int writeLongAttr(ByteBuffer buf, short id, long value) {
        NetlinkMessage.setAttrHeader(buf, id, 12);
        buf.putLong(value);
        return 12;
    }

    /** write a 4B int netlink attribute into a buffer, with header. */
    public static int writeIntAttr(ByteBuffer buf, short id, int value) {
        NetlinkMessage.setAttrHeader(buf, id, 8);
        buf.putInt(value);
        return 8;
    }

    /** write a 2B short netlink attribute into a buffer, with header. Padding
     *  for 4B alignement is added. */
    public static int writeShortAttr(ByteBuffer buf, short id, short value) {
        NetlinkMessage.setAttrHeader(buf, id, 8);
        buf.putShort(value);
        addPaddingForShort(buf);
        return 8;
    }

    /** writes a 2B short attribute with a header len field assuming no padding,
     *  but adds the padding for 4B alignement nonetheless (needed because of
     *  a couple of quirks in the request validation code of the datapath). */
    public static int writeShortAttrNoPad(ByteBuffer buf,
                                          short id, short value) {
        NetlinkMessage.setAttrHeader(buf, id, 6);
        buf.putShort(value);
        addPaddingForShort(buf);
        return 8;
    }

    /** write a one byte netlink attribute into a buffer, with header. Padding
     *  for 4B alignement is added. */
    public static int writeByteAttr(ByteBuffer buf, short id, byte value) {
        NetlinkMessage.setAttrHeader(buf, id, 8);
        buf.put(value);
        addPaddingForByte(buf);
        return 8;
    }

    /** writes a byte attribute with a header len field assuming no padding,
     *  but adds the padding for 4B alignement nonetheless (needed because of
     *  a couple of quirks in the request validation code of the datapath). */
    public static int writeByteAttrNoPad(ByteBuffer buf, short id, byte value) {
        NetlinkMessage.setAttrHeader(buf, id, 5);
        buf.put(value);
        addPaddingForByte(buf);
        return 8;
    }

    public static void addPaddingForShort(ByteBuffer buffer) {
        short padding = 0;
        buffer.putShort(padding);
    }

    static void addPaddingForByte(ByteBuffer buffer) {
        byte padding = 0;
        buffer.put(padding);
        buffer.put(padding);
        buffer.put(padding);
    }

    public static short nested(short netlinkAttributeId) {
        return (short)(netlinkAttributeId | NLA_F_NESTED);
    }

    public static short unnest(short netlinkAttributeId) {
        return (short)(netlinkAttributeId & NLA_TYPE_MASK);
    }

    public static boolean isNested(short netlinkAttributeId) {
        return (netlinkAttributeId & NLA_F_NESTED) != 0;
    }

    private static final Writer<String> stringSerializer = new Writer<String>() {
        public short attrIdOf(String any) {
            throw new UnsupportedOperationException();
        }
        public int serializeInto(ByteBuffer receiver, String value) {
            receiver.put(value.getBytes());
            receiver.put((byte) 0);       // put a null terminator
            return value.length() + 1;    // add one for null terminator
        }
    };

    private static final Writer<byte[]> bytesSerializer = new Writer<byte[]>() {
        public short attrIdOf(byte[] any) {
            throw new UnsupportedOperationException();
        }
        public int serializeInto(ByteBuffer receiver, byte[] value) {
            receiver.put(value);
            return value.length;
        }
    };

    /** Iterates over a ByteBuffer containing a sequence of netlink attributes
     *  and calls a user given handler for every attributes, aligning the buffer
     *  position and limit at the edges of the attribute values before every
     *  calls to the handler. This function allows to process and deserialize a
     *  netlink message in one traversal. It is assumed that the buffer original
     *  position is pointing at a valid netlink header. */
    public static void scanAttributes(ByteBuffer buf, AttributeHandler handler) {
        int start = buf.position();
        int end = buf.limit();
        while (buf.remaining() > 0) {
            // read attribute header
            short attrLen = buf.getShort();
            short attrId = unnest(buf.getShort());

            // enforce attribute length to avoid overflow
            int current = buf.position();
            int next = current + attrLen - 4;
            buf.limit(next);
            handler.use(buf, attrId);

            // restore original limit and move position to next attribute
            buf.limit(end);
            buf.position(align(next));
        }
        buf.position(start);
    }

    /** Scans through a ByteBuffer containing a sequence of netlink attributes
     *  and stop when the user given attribute id is found, returning the
     *  position within the ByteBuffer of the attribute value associated to
     *  this id. If no mathing id is found, returns -1. It is assumed that the
     *  buffer original position is pointing at a valid netlink header. */
    public static int seekAttribute(ByteBuffer buf, short id) {
        id = unnest(id);
        int start = buf.position();
        while (buf.remaining() > 0) {
            // read attribute header
            short attrLen = buf.getShort();
            short attrId = unnest(buf.getShort());

            int current = buf.position();
            if (attrId == id) {
                buf.position(start);
                return current;
            }

            // move to next attr header
            buf.position(align(current + attrLen - 4));
        }
        buf.position(start);
        return -1;
    }

    /** Seeks a serialized attribute by id and reconstruct a POJO instance using
     *  the given reader. */
    public static <T> T readAttr(ByteBuffer buf, short id, Reader<T> reader) {
        int start = buf.position();
        int end = buf.limit();

        int pos = seekAttribute(buf, id);
        if (pos < 0)
            return null;

        // preparing buffer before calling handler
        buf.position(pos);
        buf.limit(pos + buf.getShort(pos - 4) - 4);
        T attr = reader.deserializeFrom(buf);

        // restoring buffer
        buf.position(start);
        buf.limit(end);
        return attr;
    }

    /** Seeks a serialized string by id and reconstruct a java String. */
    public static String readStringAttr(ByteBuffer buf, short id) {
        int pos = seekAttribute(buf, id);
        if (pos < 4)
            return null;
        return parseStringAttr(buf, pos);
    }

    /** Reads a cstring contained in a ByteBuffer at the given offset and
     *  returns a java String, removing the trailing null byte if it is present.
     *  It is assumed that the cstring is an attribute value with a matching
     *  netlink header in the 4 bytes immediately before the given offset. The
     *  length read in the buffer is used to determine the length of the cstring
     *  instead of assuming a terminating null byte. */
    public static String parseStringAttr(ByteBuffer buf, int index) {
        int start = buf.position();
        int slen = buf.getShort(index - 4) - 4;
        byte[] cstring = new byte[slen];
        buf.position(index);
        buf.get(cstring, 0, slen);    // 2nd arg is write offset in the array !
        buf.position(start);
        if (cstring[slen - 1] == 0)
            return new String(cstring, 0, slen - 1);
        else
            return new String(cstring);
    }

    /** Returns the first 4B aligned index after the given one (branch free). */
    public static int align(int index) {
        // previous_aligned_index + if (last 2 LSB of index == 0) then 0 else 4
        return (index & ~3) + (4 & ((index << 1) | (index << 2)));
    }

    /** pad the given buffer with 0 until the position is aligned to the next
     *  4B index. */
    public static void alignBuffer(ByteBuffer buf) {
        byte pad = 0;
        switch (buf.position() & 0x3) {
            case 1:
                buf.put(pad);
            case 2:
                buf.put(pad);
            case 3:
                buf.put(pad);
        }
    }
}
