/* Copyright 2012 Midokura Inc. */

package com.midokura.midolman.state;

import org.apache.zookeeper.KeeperException;

import com.midokura.packets.IntIPv4;

public class ArpTable extends ReplicatedMap<IntIPv4, ArpCacheEntry> {

    public ArpTable(Directory dir) {
        super(dir);
    }

    private IntIPv4 unicastIPv4(IntIPv4 ip) {
        return (ip.getMaskLength() != 32) ?
            new IntIPv4(ip.getAddress(), 32) : ip;
    }

    @Override
    public ArpCacheEntry get(IntIPv4 key) {
        return super.get(unicastIPv4(key));
    }

    @Override
    public boolean containsKey(IntIPv4 key) {
        return super.containsKey(unicastIPv4(key));
    }

    @Override
    public void put(IntIPv4 key, ArpCacheEntry value) {
        super.put(unicastIPv4(key), value);
    }

    @Override
    public ArpCacheEntry removeIfOwner(IntIPv4 key)
        throws InterruptedException, KeeperException {
        return super.removeIfOwner(unicastIPv4(key));
    }

    @Override
    public synchronized boolean isKeyOwner(IntIPv4 key) {
        return super.isKeyOwner(unicastIPv4(key));
    }

    @Override
    protected String encodeKey(IntIPv4 key) {
        return key.toUnicastString();
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
