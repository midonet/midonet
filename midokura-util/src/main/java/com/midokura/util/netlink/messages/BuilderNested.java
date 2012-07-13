/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.netlink.messages;

import java.nio.ByteBuffer;

/**
* // TODO: mtoader ! Please explain yourself.
*/
public class BuilderNested<Parent extends BaseBuilder> extends BaseBuilder<BuilderNested<Parent>, Parent> {

    private Parent parent;

    @Override
    protected BuilderNested<Parent> self() {
        return this;
    }

    public BuilderNested(ByteBuffer buffer, Parent parent) {
        super(buffer);
        this.parent = parent;
    }

    @Override
    public Parent build() {
        return parent;
    }
}
