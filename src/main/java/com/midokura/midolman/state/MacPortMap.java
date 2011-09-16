/* Copyright 2011 Midokura Inc. */

package com.midokura.midolman.state;

import java.util.UUID;

public class MacPortMap extends ReplicatedMap<byte[], UUID> {

    public MacPortMap(Directory dir) {
        super(dir);
    }

    @Override
    protected String encodeKey(byte[] key) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%02x", key[0]));
        for (int i=1; i<key.length; i++)
            sb.append(":").append(String.format("%02x", key[0]));
        return sb.toString();
	// TODO: Test this.
    }

    @Override
    protected byte[] decodeKey(String str) {
        String[] parts = str.split(":");
        byte[] mac = new byte[parts.length];
        for (int i=0; i<parts.length; i++)
            mac[i] = Byte.parseByte(parts[i], 16);
        return mac;
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
