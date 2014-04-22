/*
 * Copyright (c) 2012 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.netlink.messages;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.packets.Ethernet;

public class Builder {

    protected final ByteBuffer buffer;
    protected final ByteOrder order;

    public Builder(ByteBuffer buffer) {
        this.buffer = buffer;
        this.order = buffer.order();
    }

    public Builder(ByteBuffer buffer, ByteOrder byteOrder) {
        buffer.order(byteOrder);
        this.order = byteOrder;
        this.buffer = buffer;
    }

    public void addAttr(NetlinkMessage.AttrKey<Byte> attr, byte value) {
        NetlinkMessage.setAttrHeader(buffer, attr.getId(), 8);
        buffer.put(value);
        addPaddingForByte(buffer);  // Pad for 4-byte alignment
    }

    /*
     * see nla_msg_put/get (in linux/include/net/netlink.h) and NLMSG_ALIGN
     * (from include/uapi/linux/netlink.h)
     * In those implementations, NLMSG_ALIGN accounts for padding (in its
     * offset and byte read calculations), and therefore isn't needed to be
     * reflected on netlink header's "length" field
     * Strictly for Netlink protocol, whether the length field accounts for
     * padding or not does not affect its message parsing;
     *
     * unfortunately for some OVS attributes, the parsing code does a length
     * check expecting the length to be without the padding bytes. For these
     * special 1 or 2 bytes attributes you can use addAttrNoPad() for writing
     * an attribute header indicating no padding.
     */
    public void addAttrNoPad(NetlinkMessage.AttrKey<Byte> attr, byte value) {
        // write the header len field assuming no padding
        NetlinkMessage.setAttrHeader(buffer, attr.getId(), 5);
        buffer.put(value);
        addPaddingForByte(buffer);  // Pad for 4-byte alignment
    }

    public void addAttr(NetlinkMessage.AttrKey<Short> attr, short value) {
        NetlinkMessage.setAttrHeader(buffer, attr.getId(), 8);
        buffer.putShort(value);
        NetlinkMessage.addPaddingForShort(buffer); // Pad for 4-byte alignment
    }

    public void addAttrNoPad(NetlinkMessage.AttrKey<Short> attr,
                                short value) {
        // write the header len field assuming no padding
        NetlinkMessage.setAttrHeader(buffer, attr.getId(), 6);
        buffer.putShort(value);
        NetlinkMessage.addPaddingForShort(buffer); // Pad for 4-byte alignment
    }

    public void addAttr(NetlinkMessage.AttrKey<Integer> attr, int value) {
        NetlinkMessage.setAttrHeader(buffer, attr.getId(), 8);
        buffer.putInt(value);
    }

    public void addAttr(NetlinkMessage.AttrKey<Long> attr, long value) {
        NetlinkMessage.setAttrHeader(buffer, attr.getId(), 12);
        buffer.putLong(value);
    }

    public void addAttr(NetlinkMessage.AttrKey<Short> attr, short value,
                           ByteOrder order) {
        NetlinkMessage.setAttrHeader(buffer, attr.getId(), 8);
        addValue(value, order);
        short padding = 0;
        addValue(padding); // Pad for 4-byte alignment
    }

    public void addAttr(NetlinkMessage.AttrKey<Integer> attr, int value,
                           ByteOrder order) {
        NetlinkMessage.setAttrHeader(buffer, attr.getId(), 8);
        addValue(value, order);
    }

    public void addAttr(NetlinkMessage.AttrKey<Long> attr, long value,
                           ByteOrder order) {
        NetlinkMessage.setAttrHeader(buffer, attr.getId(), 12);
        addValue(value, order);
    }

    public void addAttr(NetlinkMessage.AttrKey<String> attr, String value) {
        NetlinkMessage.addAttribute(buffer, attr.getId(), value);
    }

    public void addAttr(NetlinkMessage.AttrKey<? extends BuilderAware> attr, BuilderAware value) {
        // save position
        int start = buffer.position();

        // put a nl_attr header (with zero length)
        NetlinkMessage.setAttrHeader(buffer, attr.getId(), 4);

        value.serialize(this);

        int len = buffer.position() - start;
        buffer.putShort(start, (short) len);

        int padLen = NetlinkMessage.pad(len);
        while (padLen != len) {
            buffer.put((byte)0);
            padLen--;
        }
    }

    public void addAttr(NetlinkMessage.AttrKey<byte[]> attr, byte[] value) {
        NetlinkMessage.addAttribute(buffer, attr.getId(), value);
    }

    public void addAttr(NetlinkMessage.AttrKey<Ethernet> attr, Ethernet value) {
        NetlinkMessage.addAttribute(buffer, attr.getId(), value.serialize());
    }

    public void addValue(byte value) {
        buffer.put(value);
    }

    public void addValue(short value) {
        buffer.putShort(value);
    }

    public void addValue(short value, ByteOrder order) {
        buffer.order(order).putShort(value).order(this.order);
    }

    public void addValue(int value) {
        buffer.putInt(value);
    }

    public void addValue(int value, ByteOrder order) {
        buffer.order(order).putInt(value).order(this.order);
    }

    public void addValue(long value) {
        buffer.putLong(value);
    }

    public void addValue(long value, ByteOrder order) {
        buffer.order(order).putLong(value).order(this.order);
    }

    public void addValue(int[] bytes) {
        for (int aByte : bytes) {
            buffer.putInt(aByte);
        }
    }

    public void addValue(int[] ints, ByteOrder order) {
        for (int anInt : ints) {
            addValue(anInt, order);
        }
    }

    public void addValue(byte [] bytes) {
        buffer.put(bytes);
    }

    public void addAttr(NetlinkMessage.AttrKey<?> attr) {
        NetlinkMessage.setAttrHeader(buffer, attr.getId(), 0);
    }

    static void addPaddingForByte(ByteBuffer buffer) {
        byte padding = 0;
        buffer.put(padding);
        buffer.put(padding);
        buffer.put(padding);
    }

    /** Writes a list of attritubes into a NetlinkMessage as a nested attribute.
     *  First, space for the attribute header is provisioned and the attributes
     *  are read from the Iterable and serialized. Then the complete nested
     *  attribute length is calculated and written in the attribute header. */
    public void addAttrs(
            NetlinkMessage.AttrKey<?> attr,
            Iterable<? extends NetlinkMessage.Attr> attributes) {

        final int start = buffer.position(); // save position for writing the
                                             // nla_len field after iterating

        short nestedId = (short) ((1 << 15) | attr.getId());
        NetlinkMessage.setAttrHeader(buffer, nestedId, 0); // len yet unknown

        for (NetlinkMessage.Attr<? extends BuilderAware> attribute : attributes) {
            addAttr(attribute.getKey(), attribute.getValue());
        }

        buffer.putShort(start, (short) (buffer.position() - start));

    }

    public NetlinkMessage build() {
        buffer.flip();
        return new NetlinkMessage(buffer);
    }
}
