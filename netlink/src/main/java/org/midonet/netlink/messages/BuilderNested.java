/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.netlink.messages;

import java.nio.ByteBuffer;

public class BuilderNested<Parent extends BaseBuilder> extends BaseBuilder<BuilderNested<Parent>, Parent> {

    private Parent parent;
    int start;

    @Override
    protected BuilderNested<Parent> self() {
        return this;
    }

    public BuilderNested(ByteBuffer buffer, Parent parent) {
        super(buffer);
        // save position

        start = buffer.position();
        this.parent = parent;
    }

    @Override
    public Parent build() {
        buffer.putShort(start, (short) (buffer.position() - start));
        return parent;
    }
}
