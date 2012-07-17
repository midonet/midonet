/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.netlink.messages;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import com.midokura.util.netlink.NetlinkMessage;

/**
* // TODO: mtoader ! Please explain yourself.
*/
public abstract class BaseBuilder<Builder extends BaseBuilder<Builder, Result>, Result> {

    ByteBuffer buffer;

    public BaseBuilder(int size, ByteOrder byteOrder) {
        buffer = ByteBuffer.allocate(size);
        buffer.order(byteOrder);
    }

    public BaseBuilder(ByteBuffer buffer) {
        this.buffer = buffer;
    }

    protected abstract Builder self();
    public abstract Result build();

    public Builder addAttr(NetlinkMessage.AttrKey<Byte> attr, byte value) {
        NetlinkMessage.addAttribute(buffer, attr.getId(), value);
        return self();
    }

    public Builder addAttr(NetlinkMessage.AttrKey<Integer> attr, int value) {
        NetlinkMessage.addAttribute(buffer, attr.getId(), value);
        return self();
    }

    public Builder addAttr(NetlinkMessage.AttrKey<Long> attr, long value) {
        NetlinkMessage.addAttribute(buffer, attr.getId(), value);
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

        buffer.putShort(start, (short) (buffer.position() - start));

        return self();
    }

    public Builder addAttr(NetlinkMessage.AttrKey<byte[]> attr, byte[] value) {
        NetlinkMessage.addAttribute(buffer, attr.getId(), value);
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
    public Builder addValue(int value) {
        buffer.putInt(value);
        return self();
    }

    public Builder addValue(long value) {
        buffer.putLong(value);
        return self();
    }


    public Builder addValue(int[] bytes) {
        for (int aByte : bytes) {
            buffer.putInt(aByte);
        }
        return self();
    }

    public Builder addValue(byte [] bytes) {
        buffer.put(bytes);
        return self();
    }

    public Builder addAttr(NetlinkMessage.AttrKey<?> attr) {
        NetlinkMessage.addAttribute(buffer, attr.getId());
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
