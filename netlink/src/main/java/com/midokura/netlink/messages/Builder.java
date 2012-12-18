/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.netlink.messages;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import com.midokura.netlink.NetlinkMessage;

/**
* // TODO: mtoader ! Please explain yourself.
*/
public class Builder extends BaseBuilder<Builder, NetlinkMessage> {

    public Builder(int size, ByteOrder byteOrder) {
        super(size, byteOrder);
    }

    public Builder(ByteBuffer buffer) {
        super(buffer);
    }

    @Override
    protected Builder self() {
        return this;
    }

    @Override
    public NetlinkMessage build() {
        buffer.flip();
        return new NetlinkMessage(buffer);
    }
}
