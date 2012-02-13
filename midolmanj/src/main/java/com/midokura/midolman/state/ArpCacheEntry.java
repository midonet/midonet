// Copyright 2012 Midokura Inc.

package com.midokura.midolman.state;

import com.midokura.midolman.packets.MAC;

public class ArpCacheEntry implements Cloneable {
    public MAC macAddr;
    public long expiry;
    public long stale;
    public long lastArp;

    public ArpCacheEntry(MAC macAddr, long expiry, long stale, long lastArp) {
        this.macAddr = macAddr;
        this.expiry = expiry;
        this.stale = stale;
        this.lastArp = lastArp;
    }

    public ArpCacheEntry clone() {
        return new ArpCacheEntry(macAddr, expiry, stale, lastArp);
    }

    public String encode() {
        return (macAddr == null ? "null" : macAddr.toString()) + ";" +
               expiry + ";" + stale + ";" + lastArp;
    }
  
    public static ArpCacheEntry decode(String str) 
                throws ZkStateSerializationException {
        String[] fields = str.split(";");
        if (fields.length != 4)
            throw new ZkStateSerializationException(str, null, null);
        return new ArpCacheEntry(fields[0].equals("null") ? null 
                                        : MAC.fromString(fields[0]),
                                 Long.parseLong(fields[1]),
                                 Long.parseLong(fields[2]),
                                 Long.parseLong(fields[3]));
    }

    public String toString() {
        return "ArpCacheEntry [macAddr=" + macAddr +
               ", expiry=" + expiry + ", stale=" + stale +
               ", lastArp=" + lastArp + "]";
    }

}

