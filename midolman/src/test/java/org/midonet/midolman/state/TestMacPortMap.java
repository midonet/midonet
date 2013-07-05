// Copyright 2011 Midokura Inc.

package org.midonet.midolman.state;

import java.util.UUID;

import org.midonet.packets.MAC;

import org.apache.zookeeper.KeeperException;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.junit.Assert.assertEquals;

public class TestMacPortMap {

    MacPortMap map;
    MockDirectory dir;
    final byte[] macBytes = { 0, 1, 2, 3, 4, 5 };
    final MAC mac = new MAC(macBytes);
    final UUID port = UUID.fromString("c1fbd793-1ce9-42a1-86dc-65bbcaa945c0");
    final UUID port2 = UUID.fromString("e3256890-e520-11e2-a28f-0800200c9a66");

    @Before
    public void setUp() {
        dir = new MockDirectory();
        map = new MacPortMap(dir);
        map.start();
    }

    @Test
    public void testContainsEntryAfterPut() throws Exception {
        map.put(mac, port);
        assertEquals(port, map.get(mac));

        // Add learned entry
        map.put(mac, port);
        assertEquals(port, map.get(mac));

        // Delete learned (non-persistent) entry
        MacPortMap.deleteEntry(dir, mac, port);
        assertEquals(null, map.get(mac));

        // Deleting twice is OK
        MacPortMap.deleteEntry(dir, mac, port);
        assertEquals(null, map.get(mac));

        // Re-add learned entry
        map.put(mac, port);
        assertEquals(port, map.get(mac));

        // Attempting to delete existing ip->non existentmac causes no errors,
        // changes no state
        MacPortMap.deleteEntry(dir, mac, port2);
        assertEquals(port, map.get(mac));
    }

    @Test
    public void testSerialization() throws Exception {
        assertEquals("00:01:02:03:04:05", map.encodeKey(mac));
        assertEquals("c1fbd793-1ce9-42a1-86dc-65bbcaa945c0", map.encodeValue(port));

        assertEquals(mac, map.decodeKey("00:01:02:03:04:05"));
        assertEquals(port, map.decodeValue("c1fbd793-1ce9-42a1-86dc-65bbcaa945c0"));
    }
}
