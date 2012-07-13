/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.netlink.messages;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collection;

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

    public <T extends BuilderAware> Builder addAttr(NetlinkMessage.AttrKey<T> attr, T value) {
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
        // save position
        int start = buffer.position();

        // put a nl_attr header (with zero length)
        NetlinkMessage.setAttrHeader(buffer, attr.getId(), 0);

        return new BuilderNested<Builder>(buffer, self());
    }

    public Builder addAttrs(Collection<? extends NetlinkMessage.Attr<BuilderAware>> attributes) {
        for (NetlinkMessage.Attr<BuilderAware> attribute : attributes) {
            addAttr(attribute.getKey(), attribute.getValue());
        }

        return self();
    }
}
