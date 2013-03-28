/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.netlink.messages;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.midonet.netlink.NetlinkMessage;

public class Builder extends BaseBuilder<Builder, NetlinkMessage> {

    public Builder(ByteBuffer buffer) {
        super(buffer);
    }

    public Builder(ByteBuffer buffer, ByteOrder byteOrder) {
        super(buffer, byteOrder);
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
