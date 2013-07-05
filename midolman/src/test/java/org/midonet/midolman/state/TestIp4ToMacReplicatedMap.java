// Copyright 2011 Midokura Inc.

package org.midonet.midolman.state;

import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;

import org.junit.Before;
import org.junit.Test;
import scala.collection.Map;

import static org.junit.Assert.*;
import static org.junit.Assert.assertEquals;

public class TestIp4ToMacReplicatedMap {

    Ip4ToMacReplicatedMap map;
    MockDirectory dir;

    final IPv4Addr ip = IPv4Addr.fromString("1.2.3.4");
    final byte[] macBytes = { 0, 1, 2, 3, 4, 5 };
    final MAC mac = new MAC(macBytes);
    final byte[] mac2Bytes = { 0, 1, 6, 7, 8, 9 };
    final MAC mac2 = new MAC(mac2Bytes);


    @Before
    public void setUp() {
        dir = new MockDirectory();
        map = new Ip4ToMacReplicatedMap(dir);
        map.start();
    }

    @Test
    public void testContainsEntryAfterPut() throws Exception {
        // Add learned entry
        map.put(ip, mac);
        assertEquals(mac, map.get(ip));

        // Delete learned (non-persistent) entry
        Ip4ToMacReplicatedMap.deleteEntry(dir, ip, mac);
        assertEquals(null, map.get(ip));

        // Deleting twice is OK
        Ip4ToMacReplicatedMap.deleteEntry(dir, ip, mac);
        assertEquals(null, map.get(ip));

        // Re-add learned entry
        map.put(ip, mac);
        assertEquals(mac, map.get(ip));

        // Attempting to delete existing ip->non existentmac causes no errors,
        // changes no state
        Ip4ToMacReplicatedMap.deleteEntry(dir, ip, mac2);
        assertEquals(mac, map.get(ip));
    }

    @Test
    public void testSerialization() throws Exception {
        assertEquals("1.2.3.4", map.encodeKey(ip));
        assertEquals("00:01:02:03:04:05", map.encodeValue(mac));

        assertEquals(ip, map.decodeKey("1.2.3.4"));
        assertEquals(mac, map.decodeValue("00:01:02:03:04:05"));
    }
}
