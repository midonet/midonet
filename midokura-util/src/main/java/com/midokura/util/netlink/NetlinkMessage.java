/*
* Copyright 2012 Midokura KK
*/
package com.midokura.util.netlink;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

import com.midokura.util.netlink.clib.cLibrary;
import com.midokura.util.netlink.messages.Builder;
import com.midokura.util.netlink.messages.BuilderAware;

/**
 * Abstraction for a netlink message. It provides a builder to allow easy serialization
 * of netlink messages (using the same target buffer) and easy way to deserialize
 * a message.
 */
public class NetlinkMessage {

    public static class AttrKey<Type> {

        short id;

        public AttrKey(int id) {
            this.id = (short)id;
        }

        public short getId() {
            return id;
        }
    }

    public interface Attr<T> {

        public AttrKey<T> getKey();

        public T getValue();
    }

    static final short NLA_F_NESTED = (short) (1 << 15);
    static final short NLA_F_NET_BYTEORDER = (1 << 14);
    static final short NLA_TYPE_MASK = ~NLA_F_NESTED | NLA_F_NET_BYTEORDER;

    private ByteBuffer buf;
    private ByteOrder byteOrder;

    public NetlinkMessage(int size) {
        buf = ByteBuffer.allocateDirect(size);
        buf.order(ByteOrder.nativeOrder());
        byteOrder = buf.order();
    }

    public NetlinkMessage(ByteBuffer buf) {
        this.buf = buf;
        this.byteOrder = buf.order();
    }

    public ByteBuffer getBuffer() {
        return buf;
    }

    public byte getByte() {
        return buf.get();
    }

    public short getShort() {
        return buf.getShort();
    }

    public short getShort(ByteOrder order) {
        try {
            buf.order(order);
            return buf.getShort();
        } finally {
            buf.order(byteOrder);
        }
    }

    public int getInt() {
        return buf.getInt();
    }

    public int getInt(ByteOrder byteOrder) {
        try {
            buf.order(byteOrder);
            return buf.getInt();
        } finally {
            buf.order(this.byteOrder);
        }
    }

    public long getLong() {
        return buf.getLong();
    }

    public long getLong(ByteOrder byteOrder) {
        try {
            buf.order(byteOrder);
            return buf.getLong();
        } finally {
            buf.order(this.byteOrder);
        }
    }

    public int getInts(int[] bytes, ByteOrder newByteOrder) {
        try {
            buf.order(newByteOrder);
            return getInts(bytes);
        } finally {
            buf.order(this.byteOrder);
        }
    }

    public int getInts(int[] bytes) {
        for (int i = 0, bytesLength = bytes.length; i < bytesLength; i++) {
            bytes[i] = getInt();
        }

        return bytes.length;
    }

    public int getBytes(byte[] bytes) {
        buf.get(bytes);
        return bytes.length;
    }

    public boolean hasRemaining() {
        return buf.hasRemaining();
    }

    public Byte getAttrValue(AttrKey<Byte> attr) {
        return findByteValue(buf, attr.getId());
    }

    public Short getAttrValue(AttrKey<Short> attr) {
        return findShortValue(buf, attr.getId());
    }

    public Integer getAttrValue(AttrKey<Integer> attr) {
        return findIntValue(buf, attr.getId());
    }

    public Long getAttrValue(AttrKey<Long> attr) {
        return findLongValue(buf, attr.getId());
    }

    public String getAttrValue(AttrKey<String> attr) {
        return findStringValue(buf, attr.getId());
    }

    public byte[] getAttrValue(AttrKey<byte[]> attr) {
        return findBytesValue(buf, attr.getId());
    }

    public interface CustomBuilder<T> {
        T newInstance(short type);
    }

    public <T extends Attr & BuilderAware> List<T> getAttrValue(AttrKey<List<T>> attr, final CustomBuilder<T> builder) {
        final NetlinkMessage message = findNestedMessageValue(buf, attr.getId());
        if (message == null) {
            return null;
        }

        final List<T> attributes = new ArrayList<T>();
        message.iterateAttributes(new AttributeParser() {
            @Override
            public boolean processAttribute(short attributeType, ByteBuffer buffer) {

                T value = builder.newInstance(attributeType);

                if (value != null) {
                    value.deserialize(message);
                    attributes.add(value);
                }

                return true;
            }
        });

        return attributes;
    }

    public interface AttributeParser {
        boolean processAttribute(short attributeType, ByteBuffer buffer);
    }

    public void iterateAttributes(AttributeParser attributeParser) {
        buf.mark();

        try {
            while (buf.hasRemaining()) {
                short len           = buf.getShort();
                short attributeType = (short) (buf.getShort() & NLA_TYPE_MASK);

                int limit = buf.limit();
                int pos = buf.position();

                buf.limit(pos + len - 4);

                if ( ! attributeParser.processAttribute(attributeType, buf) ) {
                    return;
                }

                buf.limit(limit);

                int paddedLen = (int) (Math.ceil(len / 4.0) * 4);
                buf.position(pos + paddedLen - 4);
            }
        } finally {
            buf.reset();
        }
    }


    public NetlinkMessage getAttrValue(AttrKey<NetlinkMessage> attr) {
        return findNestedMessageValue(buf, attr.getId());
    }

    /**
     * It will tell the instance to deserialize from the current object.
     *
     * @param attr the attribute name
     * @param instance the instance that will have to deserialize itself.
     * @param <T> the attribute type
     *
     * @return the updated instance if deserialization was successful of null if not
     */
    @Nullable
    public <T extends BuilderAware> T getAttrValue(AttrKey<? extends T> attr, T instance) {
        NetlinkMessage message = findNestedMessageValue(buf, attr.getId());
        if (message != null && instance.deserialize(message)) {
            return instance;
        }

        return null;
    }

    public static void setAttrHeader(ByteBuffer buffer, short id, int len) {
        buffer.putShort((short) len);   // nla_len
        buffer.putShort(id);            // nla_type
    }

    public static int addAttribute(ByteBuffer buf, short id, String value) {

        int startPos = buf.position();
        int strLen = value.length() + 1;

        setAttrHeader(buf, id, 4 + strLen);

        // put the string
        buf.put(value.getBytes());
        buf.put((byte) 0);                  // put a null terminator

        // pad
        int padLen = pad(strLen);
        for (int i = 0; i < padLen - strLen; i++) {
            buf.put((byte) 0);
        }

        return buf.position() - startPos;
    }

    public static int addAttribute(ByteBuffer buffer, short id) {
        setAttrHeader(buffer, id, 4);
        return 4;
    }

    public static int addAttribute(ByteBuffer buffer, short id, byte value) {
        setAttrHeader(buffer, id, 8);
        buffer.put(value);
        return 8;
    }

    protected static int addAttribute(ByteBuffer buffer, short id, short value) {
        setAttrHeader(buffer, id, 8);
        buffer.putShort(value);
        return 8;
    }

    public static int addAttribute(ByteBuffer buffer, short id, int value) {
        setAttrHeader(buffer, id, 8);
        buffer.putInt(value);
        return 8;
    }

    public static int addAttribute(ByteBuffer buffer, short id, long value) {

        setAttrHeader(buffer, id, 12);
        buffer.putLong(value);
        return 12;
    }

    public static int addAttribute(ByteBuffer buf, short id, byte[] value) {
        // save position
        int start = buf.position();

        // put a nl_attr header
        buf.putShort((short) 0);
        buf.putShort(id); // nla_type

        // write the message
        buf.put(value);

        // update the nl_attr length
        buf.putShort(start, (short) (buf.position() - start));

        return buf.position() - start;
    }


    static Integer findIntValue(ByteBuffer buf, short type) {
        buf.mark();

        try {
            while (buf.hasRemaining()) {
                short len = buf.getShort();
                short t = (short) (buf.getShort() & NLA_TYPE_MASK);
                if (t == type) {
                    return buf.getInt();
                }

                buf.position(buf.position() + pad(len) - 4);
            }

            return null;
        } finally {
            buf.reset();
        }
    }

    static Long findLongValue(ByteBuffer buf, short type) {
        buf.mark();

        try {
            while (buf.hasRemaining()) {
                short len = buf.getShort();
                short t = (short) (buf.getShort() & NLA_TYPE_MASK);
                if (t == type) {
                    return buf.getLong();
                }

                buf.position(buf.position() + pad(len) - 4);
            }

            return null;
        } finally {
            buf.reset();
        }
    }

    static Short findShortValue(ByteBuffer buf, short type) {
        buf.mark();

        try {
            while (buf.hasRemaining()) {
                short len = buf.getShort();
                short t = (short) (buf.getShort() & NLA_TYPE_MASK);
                if (t == type) {
                    return buf.getShort();
                }
                buf.position(buf.position() + pad(len) - 4);
            }

            return null;
        } finally {
            buf.reset();
        }
    }

    static Byte findByteValue(ByteBuffer buf, short type) {
        buf.mark();

        try {
            while (buf.hasRemaining()) {
                short len = buf.getShort();
                short t = (short) (buf.getShort() & NLA_TYPE_MASK);
                if (t == type) {
                    return buf.get();
                }

                int paddedLen = (int) (Math.ceil(len / 4.0) * 4);
                buf.position(buf.position() + paddedLen - 4);
            }

            return null;
        } finally {
            buf.reset();
        }
    }

    static byte[] findBytesValue(ByteBuffer buf, short type) {
        buf.mark();

        try {
            while (buf.hasRemaining()) {
                short len = buf.getShort();
                short t = (short) (buf.getShort() & NLA_TYPE_MASK);
                if (t == type) {
                    byte[] b = new byte[len - 4];
                    buf.get(b);
                    return b;
                }

                int nextPos = buf.position() + pad(len) - 4;
                if ( nextPos < buf.capacity() )
                    buf.position(nextPos);
            }

            return null;
        } finally {
            buf.reset();
        }
    }

    static String findStringValue(ByteBuffer buf, short type) {
        buf.mark();

        try {
            while (buf.hasRemaining()) {
                short len = buf.getShort();
                short t = (short) (buf.getShort() & NLA_TYPE_MASK);
                if (t == type) {
                    byte[] b = new byte[len - 4];
                    buf.get(b);
                    return new String(b, 0, len - 5);
                }

                int paddedLen = len & ~0x03;
                if ( paddedLen < len ) {
                    paddedLen += 0x04;
                }

                int nextPos = buf.position() + paddedLen - 4;
                if ( nextPos < buf.capacity() )
                    buf.position(nextPos);
            }

            return null;
        } finally {
            buf.reset();
        }
    }

    static NetlinkMessage findNestedMessageValue(ByteBuffer buf, short type) {
        buf.mark();

        try {
            while (buf.hasRemaining()) {
                short len = buf.getShort();
                short t = (short) (buf.getShort() & NLA_TYPE_MASK);
                if (t == type) {
                    int limit = buf.limit();

                    buf.limit(buf.position() + len - 4);

                    ByteBuffer slice = buf.slice();
                    slice.order(ByteOrder.nativeOrder());

                    buf.limit(limit);

                    return new NetlinkMessage(slice);
                }

                buf.position(buf.position() + pad(len) - 4);
            }

            return null;
        } finally {
            buf.reset();
        }
    }

    public static Builder newMessageBuilder(int size, ByteOrder order) {
        return new Builder(size, order);
    }

    public static Builder newMessageBuilder(int size) {
        return new Builder(size, ByteOrder.nativeOrder());
    }

    public static Builder newMessageBuilder(ByteOrder order) {
        return newMessageBuilder(cLibrary.PAGE_SIZE, order);
    }

    public static Builder newMessageBuilder() {
        return newMessageBuilder(cLibrary.PAGE_SIZE);
    }

    private static int pad(int len) {
        int paddedLen = len & ~0x03;
        if ( paddedLen < len ) {
            paddedLen += 0x04;
        }

        return paddedLen;
    }
}

