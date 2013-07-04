// Copyright 2011 Midokura Inc.

package org.midonet.midolman.state;

import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;

import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.junit.Assert.assertEquals;

public class TestArpTable {

    ArpTable map;
    MockDirectory dir;

    final IPv4Addr ip = IPv4Addr.fromString("1.2.3.4");
    final byte[] macBytes = { 0, 1, 2, 3, 4, 5 };
    final MAC mac = new MAC(macBytes);
    final ArpCacheEntry cacheEntry = new ArpCacheEntry(mac,10,100,1000);

    @Before
    public void setUp() {
        dir = new MockDirectory();
        map = new ArpTable(dir);
        map.start();
    }

    @Test
    public void testContainsEntryAfterPut() throws Exception {
        map.put(ip, cacheEntry);
        assertEquals(cacheEntry, map.get(ip));
    }

    @Test
    public void testSerialization() throws Exception {
        assertEquals("1.2.3.4", map.encodeKey(ip));
        assertEquals("00:01:02:03:04:05;10;100;1000", map.encodeValue(cacheEntry));

        assertEquals(ip, map.decodeKey("1.2.3.4"));
        assertEquals(cacheEntry, map.decodeValue("00:01:02:03:04:05;10;100;1000"));
    }
}
