// Copyright 2011 Midokura Inc.

package org.midonet.midolman.state;

import java.util.UUID;

import org.midonet.packets.IntIPv4;
import org.midonet.packets.MAC;

import org.apache.zookeeper.KeeperException;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.junit.Assert.assertEquals;

public class TestIp4ToMacReplicatedMap {

    Ip4ToMacReplicatedMap map;
    MockDirectory dir;

    final IntIPv4 ip = IntIPv4.fromString("1.2.3.4");
    final byte[] macBytes = { 0, 1, 2, 3, 4, 5 };
    final MAC mac = new MAC(macBytes);

    @Before
    public void setUp() {
        dir = new MockDirectory();
        map = new Ip4ToMacReplicatedMap(dir);
        map.start();
    }

    @Test
    public void testContainsEntryAfterPut() throws Exception {
        map.put(ip, mac);
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
