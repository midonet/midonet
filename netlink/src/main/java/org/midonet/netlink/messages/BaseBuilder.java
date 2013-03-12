/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.netlink.messages;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.packets.Ethernet;

public abstract class BaseBuilder<Builder extends BaseBuilder<Builder, Result>, Result> {

    ByteBuffer buffer;
    ByteOrder order;

    public BaseBuilder(ByteBuffer buffer) {
        this.buffer = buffer;
        this.order = buffer.order();
    }

    public BaseBuilder(ByteBuffer buffer, ByteOrder byteOrder) {
        buffer.order(byteOrder);
        this.order = byteOrder;
        this.buffer = buffer;
    }

    protected abstract Builder self();
    public abstract Result build();

    public Builder addAttr(NetlinkMessage.AttrKey<Byte> attr, byte value) {
        NetlinkMessage.setAttrHeader(buffer, attr.getId(), 8);
        addValue(value);

        // Pad for 4-byte alignment
        byte padding = 0;
        addValue(padding);
        addValue(padding);
        addValue(padding);
        return self();
    }

    /*
     * see nla_msg_put/get (in linux/include/net/netlink.h) and NLMSG_ALIGN
     * (from include/uapi/linux/netlink.h)
     * In those implementations, NLMSG_ALIGN accounts for padding (in its
     * offset and byte read calculations), and therefore isn't needed to be
     * reflected on netlink header's "length" field
     * Strictly for Netlink protocol, whether the length field accounts for
     * padding or not does not affect its message parsing; unfortunately for
     * some OVS attributes, the parsing code does a length check expecting
     * the length to be without the padding bytes - so use addAttrNoPad for
     * those attributes
     * The length field "5" here corresponds to Netlink header length (4 bytes)
     * + the length of a byte of data (1 byte)
     */
    public Builder addAttrNoPad(NetlinkMessage.AttrKey<Byte> attr, byte value) {
        NetlinkMessage.setAttrHeader(buffer, attr.getId(), 5);
        addValue(value);

        // Pad for 4-byte alignment
        byte padding = 0;
        addValue(padding);
        addValue(padding);
        addValue(padding);
        return self();
    }

    public Builder addAttr(NetlinkMessage.AttrKey<Short> attr, short value) {
        NetlinkMessage.setAttrHeader(buffer, attr.getId(), 8);
        addValue(value);

        // Pad for 4-byte alignment
        short padding = 0;
        addValue(padding);
        return self();
    }

    /*
     * The number "6" below corresponds to length of Netlink header (4 bytes)
     * plus the length of a short (2 bytes)
     */
    public Builder addAttrNoPad(NetlinkMessage.AttrKey<Short> attr,
                                short value) {
        NetlinkMessage.setAttrHeader(buffer, attr.getId(), 6);
        addValue(value);

        // Pad for 4-byte alignment
        short padding = 0;
        addValue(padding);
        return self();
    }

    public Builder addAttr(NetlinkMessage.AttrKey<Integer> attr, int value) {
        NetlinkMessage.setAttrHeader(buffer, attr.getId(), 8);
        addValue(value);
        return self();
    }

    public Builder addAttr(NetlinkMessage.AttrKey<Long> attr, long value) {
        NetlinkMessage.setAttrHeader(buffer, attr.getId(), 12);
        addValue(value);
        return self();
    }

    public Builder addAttr(NetlinkMessage.AttrKey<Short> attr, short value,
                           ByteOrder order) {
        NetlinkMessage.setAttrHeader(buffer, attr.getId(), 8);
        addValue(value, order);

        // Pad for 4-byte alignment
        short padding = 0;
        addValue(padding);
        return self();
    }

    public Builder addAttr(NetlinkMessage.AttrKey<Integer> attr, int value,
                           ByteOrder order) {
        NetlinkMessage.setAttrHeader(buffer, attr.getId(), 8);
        addValue(value, order);
        return self();
    }

    public Builder addAttr(NetlinkMessage.AttrKey<Long> attr, long value,
                           ByteOrder order) {
        NetlinkMessage.setAttrHeader(buffer, attr.getId(), 12);
        addValue(value, order);
        return self();
    }

    public Builder addAttr(NetlinkMessage.AttrKey<String> attr, String value) {
        NetlinkMessage.addAttribute(buffer, attr.getId(), value);
        return self();
    }

    public Builder addAttr(NetlinkMessage.AttrKey<? extends BuilderAware> attr, BuilderAware value) {
        // save position
        int start = buffer.position();

        // put a nl_attr header (with zero length)
        NetlinkMessage.setAttrHeader(buffer, attr.getId(), 0);

        value.serialize(self());

        int len = buffer.position() - start;
        buffer.putShort(start, (short) len);

        int padLen = NetlinkMessage.pad(len);
        while (padLen != len) {
            buffer.put((byte)0);
            padLen--;
        }
        return self();
    }

    public Builder addAttr(NetlinkMessage.AttrKey<byte[]> attr, byte[] value) {
        NetlinkMessage.addAttribute(buffer, attr.getId(), value);
        return self();
    }

    public Builder addAttr(NetlinkMessage.AttrKey<Ethernet> attr, Ethernet value) {
        NetlinkMessage.addAttribute(buffer, attr.getId(), value.serialize());
        return self();
    }

    public Builder addValue(byte value) {
        buffer.put(value);
        return self();
    }

    public Builder addValue(short value) {
        buffer.putShort(value);
        return self();
    }

    public Builder addValue(short value, ByteOrder order) {
        buffer.order(order).putShort(value).order(this.order);
        return self();
    }

    public Builder addValue(int value) {
        buffer.putInt(value);
        return self();
    }

    public Builder addValue(int value, ByteOrder order) {
        buffer.order(order).putInt(value).order(this.order);
        return self();
    }

    public Builder addValue(long value) {
        buffer.putLong(value);
        return self();
    }

    public Builder addValue(long value, ByteOrder order) {
        buffer.order(order).putLong(value).order(this.order);
        return self();
    }

    public Builder addValue(int[] bytes) {
        for (int aByte : bytes) {
            buffer.putInt(aByte);
        }
        return self();
    }

    public Builder addValue(int[] ints, ByteOrder order) {
        for (int anInt : ints) {
            addValue(anInt, order);
        }
        return self();
    }

    public Builder addValue(byte [] bytes) {
        buffer.put(bytes);
        return self();
    }

    public Builder addAttr(NetlinkMessage.AttrKey<?> attr) {
        NetlinkMessage.setAttrHeader(buffer, attr.getId(), 4);
        return self();
    }

    public BuilderNested<Builder> addAttrNested(NetlinkMessage.AttrKey<?> attr) {

        BuilderNested<Builder> builderNested =
            new BuilderNested<Builder>(buffer, self());

        // put a nl_attr header (with zero length)
        NetlinkMessage.setAttrHeader(buffer, (short) ((1 << 15) | attr.getId()), 0);

        return builderNested;
    }

    public Builder addAttrs(List<? extends NetlinkMessage.Attr> attributes) {
        for (NetlinkMessage.Attr<? extends BuilderAware> attribute : attributes) {
            addAttr(attribute.getKey(), attribute.getValue());
        }

        return self();
    }
}
