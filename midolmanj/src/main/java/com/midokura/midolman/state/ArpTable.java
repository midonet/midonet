/* Copyright 2012 Midokura Inc. */

package com.midokura.midolman.state;

import com.midokura.packets.IntIPv4;

public class ArpTable extends ReplicatedMap<IntIPv4, ArpCacheEntry> {

    public ArpTable(Directory dir) {
        super(dir);
    }

    @Override
    protected String encodeKey(IntIPv4 key) {
        return key.toString();
    }

    @Override
    protected IntIPv4 decodeKey(String str) {
        return IntIPv4.fromString(str);
    }

    @Override
    protected String encodeValue(ArpCacheEntry value) {
        return value.encode();
    }

    @Override
    protected ArpCacheEntry decodeValue(String str) {
        try {
            return ArpCacheEntry.decode(str);
        } catch (ZkStateSerializationException e) {
            throw new RuntimeException(e);
        }
    }
}
