/*
 * Copyright 2011 Midokura KK 
 */

package com.midokura.midolman.state;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;

public class PortLocationMap extends ReplicatedMap<UUID, InetAddress> {

    public PortLocationMap(Directory dir) {
        super(dir);
    }

    @Override
    protected String encodeKey(UUID key) {
        return key.toString();
    }

    @Override
    protected UUID decodeKey(String str) {
        return UUID.fromString(str);
    }

    @Override
    protected String encodeValue(InetAddress value) {
        byte[] addr = value.getAddress();
        StringBuilder builder = new StringBuilder();
        builder.append(addr[0]);
        for (int i=1; i < addr.length; i++)
            builder.append(".").append(addr[i]);
        return builder.toString();
    }

    @Override
    protected InetAddress decodeValue(String str) {
        String[] parts = str.split(".");
        byte[] bytes = new byte[parts.length];
        for (int i=0; i<parts.length; i++)
            bytes[i] = Byte.parseByte(parts[i]);
        try {
            return InetAddress.getByAddress(bytes);
        }
        catch (UnknownHostException e) {
                throw new RuntimeException(e);
            }
    }
    
}
