/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
 */
package org.midonet.netlink;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

import org.midonet.netlink.clib.cLibrary;
import org.midonet.netlink.messages.Builder;
import org.midonet.netlink.messages.BuilderAware;
import org.midonet.packets.Ethernet;
import org.midonet.packets.MalformedPacketException;

/**
 * Abstraction for a netlink message. It provides a builder to allow easy serialization
 * of netlink messages (using the same target buffer) and easy way to deserialize
 * a message.
 */
public class NetlinkMessage {
    public static final class AttrKey<Type> {

        public final short id;

        private AttrKey(int id) {
            this.id = (short) id;
        }

        public short getId() {
            return id;
        }

        public static <T> AttrKey<T> attr(int id) {
            return new AttrKey<T>(id);
        }

        public static <T> AttrKey<T> attrNested(int id) {
            return new AttrKey<T>(nested((short)id));
        }
    }

    public interface Attr<T> {

        public AttrKey<? extends T> getKey();

        public T getValue();
    }

    static public final short NLA_F_NESTED = (short) (1 << 15);
    static public final short NLA_F_NET_BYTEORDER = (1 << 14);
    static public final short NLA_TYPE_MASK = ~NLA_F_NESTED | NLA_F_NET_BYTEORDER;

    private ByteBuffer buf;

    public NetlinkMessage(ByteBuffer buf) {
        this.buf = buf;
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

    public int getInt() {
        return buf.getInt();
    }

    public long getLong() {
        return buf.getLong();
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

    /*
     * Name: getAttrValueNone
     * @ attr: a boolean attribute which isn't associated with any data,
     *          declared as a boolean to own a boolean data type
     * returns: TRUE if the attribute exists, null otherwise
     * In some cases, we need to verify if an attribute exists where
     * the attribute may not be corresponding to any data (a flag, for
     * example)
     * Note: parseBuffer returning false is intended to end the attribute
     * buffer iteration - this has nothing to do with the Boolean this
     * method returns
     */
    public Boolean getAttrValueNone(AttrKey<Boolean> attr) {
        return new SingleAttributeParser<Boolean>(attr) {
            @Override
            protected boolean parseBuffer(ByteBuffer buffer) {
                data = new Boolean(Boolean.TRUE);
                return false;
            }
        }.parse(this);
    }

    public Byte getAttrValueByte(AttrKey<Byte> attr) {
        return new SingleAttributeParser<Byte>(attr) {
            @Override
            protected boolean parseBuffer(ByteBuffer buffer) {
                data = buffer.get();
                return false;
            }
        }.parse(this);
    }

    public Short getAttrValueShort(AttrKey<Short> attr) {
        return new SingleAttributeParser<Short>(attr) {
            @Override
            protected boolean parseBuffer(ByteBuffer buffer) {
                data = buffer.getShort();
                return false;
            }
        }.parse(this);
    }

    public Integer getAttrValueInt(AttrKey<Integer> attr) {
        return new SingleAttributeParser<Integer>(attr) {
            @Override
            protected boolean parseBuffer(ByteBuffer buffer) {
                data = buffer.getInt();
                return false;
            }
        }.parse(this);
    }

    public Long getAttrValueLong(AttrKey<Long> attr) {
        return new SingleAttributeParser<Long>(attr) {
            @Override
            protected boolean parseBuffer(ByteBuffer buffer) {
                data = buffer.getLong();
                return false;
            }
        }.parse(this);
    }

    public String getAttrValueString(AttrKey<String> attr) {
        return new SingleAttributeParser<String>(attr) {
            @Override
            protected boolean parseBuffer(ByteBuffer buffer) {
                byte[] bytes = new byte[buffer.remaining()];
                buf.get(bytes);
                data = new String(bytes, 0, bytes.length - 1);
                return false;
            }
        }.parse(this);
    }

    public byte[] getAttrValueBytes(final AttrKey<byte[]> attr) {
       return new SingleAttributeParser<byte[]>(attr) {
           @Override
           protected boolean parseBuffer(ByteBuffer buffer) {
               data = new byte[buffer.remaining()];
               buffer.get(data);
               return false;
           }
       }.parse(this);
    }

    public Ethernet getAttrValueEthernet(final AttrKey<Ethernet> attr) {
        return new SingleAttributeParser<Ethernet>(attr) {
            @Override
            protected boolean parseBuffer(ByteBuffer buffer) {
                data = new Ethernet();
                try {
                    data.deserialize(buffer);
                } catch (MalformedPacketException e) {
                    data = null;
                }
                return false;
            }
        }.parse(this);
    }

    public interface CustomBuilder<T> {
        T newInstance(short type);
    }

    public <T extends Attr & BuilderAware> List<T> getAttrValue(AttrKey<List<T>> attr, final CustomBuilder<T> builder) {
        return new SingleAttributeParser<List<T>>(attr) {
            @Override
            protected boolean parseBuffer(ByteBuffer buffer) {
                final NetlinkMessage message = sliceFrom(buffer);
                data = new ArrayList<T>();
                message.iterateAttributes(new AttributeParser() {
                    @Override
                    public boolean processAttribute(short attributeType, ByteBuffer buffer) {
                        T value = builder.newInstance(attributeType);

                        if (value != null) {
                            value.deserialize(message);
                            data.add(value);
                        }

                        return true;
                    }
                });

                return false;
            }
        }.parse(this);
    }

    public interface AttributeParser {
        /**
         * @param attributeType the type of attribute.
         * @param buffer the buffer with the data
         *
         * @return true if the next attribute is to be parsed, false if not.
         */
        boolean processAttribute(short attributeType, ByteBuffer buffer);
    }

    public void iterateAttributes(AttributeParser attributeParser) {

        int limit = buf.limit();
        buf.mark();

        try {
            while (buf.hasRemaining()) {
                char len = buf.getChar();
                if (len == 0)
                    return;

                short attrType = (short) (buf.getShort() & NLA_TYPE_MASK);
                int pos = buf.position();

                buf.limit(pos + len - 4);

                if (!attributeParser.processAttribute(attrType, buf)) {
                    return;
                }

                buf.limit(limit);
                buf.position(pos + pad(len) - 4);
                buf.order(ByteOrder.nativeOrder());
            }
        } finally {
            buf.limit(limit);
            buf.reset();
            buf.order(ByteOrder.nativeOrder());
        }
    }


    public NetlinkMessage getAttrValueNested(AttrKey<NetlinkMessage> attr) {
        return new SingleAttributeParser<NetlinkMessage>(attr) {
            @Override
            protected boolean parseBuffer(ByteBuffer buffer) {
                data = sliceFrom(buffer);
                return false;
            }
        }.parse(this);
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
    public <T extends BuilderAware> T getAttrValue(AttrKey<? extends T> attr, final T instance) {
        return new SingleAttributeParser<T>(attr) {
            @Override
            protected boolean parseBuffer(ByteBuffer buffer) {
                NetlinkMessage message = sliceFrom(buffer);
                instance.deserialize(message);
                data = instance;
                return false;
            }
        }.parse(this);
    }

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

    public static int addAttribute(ByteBuffer buf, short id, String value) {

        int startPos = buf.position();
        int strLen = value.length() + 1;

        setAttrHeader(buf, id, 4 + strLen);

        // put the string
        buf.put(value.getBytes());
        buf.put((byte) 0);                  // put a null terminator

        // pad
        int padLen = pad(strLen);
        while (padLen != strLen) {
            buf.put((byte)0);
            padLen--;
        }

        return buf.position() - startPos;
    }

    /** Generic attribute writing function that can write an arbitrary value
     *  into a ByteBuffer, given a translator typeclass instance for that value
     *  type. Padding for 4B alignement is added to the message. Returns the
     *  total number of bytes written in the buffer. */
    public static <V> int writeAttr(ByteBuffer buffer, V value,
                                    Translator<V> translator) {

        int start = buffer.position();      // save position

        short id = translator.attrIdOf(value);
        NetlinkMessage.setAttrHeader(buffer, id, 4); // space for nl_attr header

        int advertisedLen = translator.serializeInto(buffer, value);
        int len = buffer.position() - start;

        buffer.putShort(start, (short) len); // write nl_attr length field

        int padLen = NetlinkMessage.pad(len);
        while (padLen != len) {
            buffer.put((byte)0);
            padLen--;
        }

        return padLen;
    }

    /** Generic attribute sequence writing function that can write an arbitrary
     *  sequence of value into a ByteBuffer as a netlink nested attribute, given
     *  a translator typeclass instance for the type of these values and the id
     *  of the nested attribute. The message is 4B aligned. Returns the total
     *  number of bytes written in the buffer. */
    public static <V> int writeAttrSeq(ByteBuffer buffer, short id,
                                       Iterable<V> values,
                                       Translator<V> translator) {

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

    /** write an 8B long netlink attribute into a buffer, with header. */
    public static int writeLongAttr(ByteBuffer buf, short id, long value) {
        NetlinkMessage.setAttrHeader(buf, id, 12);
        buf.putLong(value);
        return 12;
    }

    /** write an 4B int netlink attribute into a buffer, with header. */
    public static int writeIntAttr(ByteBuffer buf, short id, int value) {
        NetlinkMessage.setAttrHeader(buf, id, 8);
        buf.putInt(value);
        return 8;
    }

    /** write an 2B int netlink attribute into a buffer, with header. Padding
     *  for 4B alignement is added. */
    public static int writeShortAttr(ByteBuffer buf, short id, short value) {
        NetlinkMessage.setAttrHeader(buf, id, 8);
        buf.putShort(value);
        addPaddingForShort(buf);
        return 8;
    }

    /** writes a short attribute with a header len field assuming no padding,
     *  but adds the padding for 4B alignement nonetheless (needed because of
     *  a couple of quirks in the request validation code of the datapath). */
    public static int writeShortAttrNoPad(ByteBuffer buf,
                                          short id, short value) {
        NetlinkMessage.setAttrHeader(buf, id, 6);
        buf.putShort(value);
        addPaddingForShort(buf);
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

    public static int pad(int len) {
        int paddedLen = len & ~0x03;
        if ( paddedLen < len ) {
            paddedLen += 0x04;
        }

        return paddedLen;
    }

    private abstract static class SingleAttributeParser<T> implements AttributeParser {
        protected T data;
        AttrKey<? extends T> attr;

        protected SingleAttributeParser(AttrKey<? extends T> attr) {
            this.attr = attr;
        }

        @Override
        public boolean processAttribute(short attributeType, ByteBuffer buffer) {
            if ((attr.getId() & NLA_TYPE_MASK) != attributeType)
                return true;

            return parseBuffer(buffer);
        }

        protected abstract boolean parseBuffer(ByteBuffer buffer);

        public T parse(NetlinkMessage message) {
            message.iterateAttributes(this);
            return data;
        }
    }

    private static NetlinkMessage sliceFrom(ByteBuffer buffer) {
        return new NetlinkMessage(BytesUtil.instance.sliceOf(buffer));
    }

    public static short nested(short netlinkAttributeId) {
        return (short)(netlinkAttributeId | NLA_F_NESTED);
    }

    public static boolean isNested(short netlinkAttributeId) {
        return (netlinkAttributeId & NLA_F_NESTED) != 0;
    }
}
