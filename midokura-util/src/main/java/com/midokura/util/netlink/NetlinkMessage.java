/*
* Copyright 2012 Midokura KK
*/
package com.midokura.util.netlink;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import javax.annotation.Nullable;

import com.midokura.util.netlink.clib.cLibrary;

/**
 * Abstraction for a netlink message. It provides a builder to allow easy serialization
 * of netlink messages (using the same target buffer) and easy way to deserialize
 * a message.
 */
public class NetlinkMessage {

    public interface BuilderAware {

        public void serialize(Builder builder);

        public boolean deserialize(NetlinkMessage message);
    }

    public static class Attr<Type>{
        short id;

        public Attr(int id) {
            this.id = (short)id;
        }

        public short getId() {
            return id;
        }
    }

    static final short NLA_F_NESTED = (short) (1 << 15);
    static final short NLA_F_NET_BYTEORDER = (1 << 14);
    static final short NLA_TYPE_MASK = ~NLA_F_NESTED | NLA_F_NET_BYTEORDER;

    public ByteBuffer buf;

    public NetlinkMessage(int size) {
        buf = ByteBuffer.allocateDirect(size);
        buf.order(ByteOrder.nativeOrder());
    }

    public NetlinkMessage(ByteBuffer buf) {
        this.buf = buf;
    }

    public int getInt() {
        return buf.getInt();
    }

    public long getLong() {
        return buf.getLong();
    }

    public boolean hasRemaining() {
        return buf.hasRemaining();
    }

    public Byte getAttrValue(Attr<Byte> attr) {
        return findByteValue(buf, attr.getId());
    }

    public Short getAttrValue(Attr<Short> attr) {
        return findShortValue(buf, attr.getId());
    }

    public int getAttrValue(Attr<Integer> attr) {
        return findIntValue(buf, attr.getId());
    }

    public Long getAttrValue(Attr<Long> attr) {
        return findLongValue(buf, attr.getId());
    }

    public String getAttrValue(Attr<String> attr) {
        return findStringValue(buf, attr.getId());
    }

    public byte[] getAttrValue(Attr<byte[]> attr) {
        return findBytesValue(buf, attr.getId());
    }

    public NetlinkMessage getAttrValue(Attr<NetlinkMessage> attr) {
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
    public <T extends BuilderAware> T getAttrValue(Attr<? extends T> attr, T instance) {
        NetlinkMessage message = findNestedMessageValue(buf, attr.getId());
        if (message != null && instance.deserialize(message)) {
            return instance;
        }

        return null;
    }

    private static void setAttrHeader(ByteBuffer buffer, short id, int len) {
        buffer.putShort((short) len);   // nla_len
        buffer.putShort(id);            // nla_type
    }

    protected static int addAttribute(ByteBuffer buf, short id, String value) {

        int startPos = buf.position();
        int strLen = value.length() + 1;

        setAttrHeader(buf, id, 4 + strLen);

        // put the string
        buf.put(value.getBytes());
        buf.put((byte) 0);                  // put a null terminator

        // pad
        int padLen = padding(strLen);
        for (int i = 0; i < padLen; i++) {
            buf.put((byte) 0);
        }

        return buf.position() - startPos;
    }


    protected static int addAttribute(ByteBuffer buffer, short id, byte value) {

        setAttrHeader(buffer, id, 8);
        buffer.put(value);
        return 8;
    }

    protected static int addAttribute(ByteBuffer buffer, short id, int value) {
        setAttrHeader(buffer, id, 8);
        buffer.putInt(value);
        return 8;
    }

    protected static int addAttribute(ByteBuffer buffer, short id, long value) {

        setAttrHeader(buffer, id, 12);
        buffer.putLong(value);
        return 12;
    }

    private static int addAttribute(ByteBuffer buf, short id, byte[] value) {
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

                int paddedLen = (int) (Math.ceil(len / 4.0) * 4);
                buf.position(buf.position() + paddedLen - 4);
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

                int paddedLen = (int) (Math.ceil(len / 4.0) * 4);
                buf.position(buf.position() + paddedLen - 4);
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

                int paddedLen = (int) (Math.ceil(len / 4.0) * 4);
                buf.position(buf.position() + paddedLen - 4);
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

                int paddedLen = (int) (Math.ceil(len / 4.0) * 4);
                buf.position(buf.position() + paddedLen - 4);
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

    public static class Builder {

        ByteBuffer buffer;

        private Builder(int size, ByteOrder byteOrder) {
            buffer = ByteBuffer.allocate(size);
            buffer.order(byteOrder);
        }

        public Builder addAttr(Attr<Byte> attr, byte value) {
            NetlinkMessage.addAttribute(buffer, attr.getId(), value);
            return this;
        }

        public Builder addAttr(Attr<Integer> attr, int value) {
            NetlinkMessage.addAttribute(buffer, attr.getId(), value);
            return this;
        }

        public Builder addAttr(Attr<Long> attr, long value) {
            NetlinkMessage.addAttribute(buffer, attr.getId(), value);
            return this;
        }

        public Builder addAttr(Attr<String> attr, String value) {
            NetlinkMessage.addAttribute(buffer, attr.getId(), value);
            return this;
        }

        public Builder addAttr(Attr<? extends BuilderAware> attr, BuilderAware value) {
            // save position
            int start = buffer.position();

            // put a nl_attr header (with zero length)
            setAttrHeader(buffer, attr.getId(), 0);

            value.serialize(this);

            buffer.putShort(start, (short) (buffer.position() - start));

            return this;
        }

        public Builder addAttr(Attr<byte[]> attr, byte[] value) {
            NetlinkMessage.addAttribute(buffer, attr.getId(), value);
            return this;
        }

        public Builder addValue(int value) {
            buffer.putInt(value);
            return this;
        }

        public Builder addValue(long value) {
            buffer.putLong(value);
            return this;
        }

        public NetlinkMessage build() {
            buffer.flip();
            return new NetlinkMessage(buffer);
        }

    }

    private static int padding(int len) {
        return (int) (Math.ceil(len / 4.0) * 4) - len;
    }
}

