/* Copyright 2011 Midokura Inc. */

package com.midokura.midolman.state;

import java.util.UUID;

import com.midokura.midolman.packets.Ethernet;
import com.midokura.midolman.util.Net;

public class MacPortMap extends ReplicatedMap<byte[], UUID> {

    public MacPortMap(Directory dir) {
        super(dir);
    }

    @Override
    protected String encodeKey(byte[] key) {
        return Net.convertByteMacToString(key);
    }

    @Override
    protected byte[] decodeKey(String str) {
        return Ethernet.toMACAddress(str);
        // TODO: Test this.
    }

    @Override
    protected String encodeValue(UUID value) {
        return value.toString();
    }

    @Override
    protected UUID decodeValue(String str) {
        return UUID.fromString(str);
    }

}
