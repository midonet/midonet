/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.midolman.state;

import org.junit.Before;
import org.junit.Test;

import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TestIp4ToMacReplicatedMap {

    Ip4ToMacReplicatedMap map;
    MockDirectory dir;

    final IPv4Addr ip = IPv4Addr.fromString("1.2.3.4");
    final IPv4Addr ip2 = IPv4Addr.fromString("5.6.7.8");
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

    @Test
    public void testLearnedEntriesAddHasRemove() throws Exception {
        Ip4ToMacReplicatedMap.addLearnedEntry(dir, ip, mac);
        assertTrue(Ip4ToMacReplicatedMap.hasLearnedEntry(dir, ip, mac));
        assertFalse(Ip4ToMacReplicatedMap.hasPersistentEntry(dir, ip, mac));
        assertEquals(mac, Ip4ToMacReplicatedMap.getEntry(dir, ip));

        // some deletes that should not have any effect
        Ip4ToMacReplicatedMap.deleteLearnedEntry(dir, ip, mac2);
        Ip4ToMacReplicatedMap.deleteLearnedEntry(dir, ip2, mac);

        // Reassert
        assertTrue(Ip4ToMacReplicatedMap.hasLearnedEntry(dir, ip, mac));
        assertFalse(Ip4ToMacReplicatedMap.hasPersistentEntry(dir, ip, mac));
        assertEquals(mac, Ip4ToMacReplicatedMap.getEntry(dir, ip));

        // Now for real
        Ip4ToMacReplicatedMap.deleteLearnedEntry(dir, ip, mac);

        assertFalse(Ip4ToMacReplicatedMap.hasLearnedEntry(dir, ip, mac));
        assertFalse(Ip4ToMacReplicatedMap.hasPersistentEntry(dir, ip, mac));
        assertEquals(null, Ip4ToMacReplicatedMap.getEntry(dir, ip));
    }

    @Test
    public void testRepeatedEntryFails() throws Exception {
        Ip4ToMacReplicatedMap.addLearnedEntry(dir, ip, mac);
        try {
            Ip4ToMacReplicatedMap.addLearnedEntry(dir, ip, mac);
            fail("StateAccesException expected when overwriting entry");
        } catch (Exception e) {
            // ok
        }
        assertTrue(Ip4ToMacReplicatedMap.hasLearnedEntry(dir, ip, mac));
    }

    /* The equivalent testLearnedDoesntOverwriteNonLearned cannot be done
     * because the map actually allows it. This is likely a bug, but will
     * require verifying that no feature is depending on the bug.
     */
    @Test
    public void testNotLearnedOverwritesLearned() throws Exception {
        Ip4ToMacReplicatedMap.addLearnedEntry(dir, ip, mac);
        assertTrue(Ip4ToMacReplicatedMap.hasLearnedEntry(dir, ip, mac));
        assertFalse(Ip4ToMacReplicatedMap.hasPersistentEntry(dir, ip, mac));
        Ip4ToMacReplicatedMap.addPersistentEntry(dir, ip, mac);
        assertFalse(Ip4ToMacReplicatedMap.hasLearnedEntry(dir, ip, mac));
        assertTrue(Ip4ToMacReplicatedMap.hasPersistentEntry(dir, ip, mac));
    }

    @Test // the ordinary deleteEntry should affect also learned entries
    public void testDeleteAffectsLearnedEntries() throws Exception {
        Ip4ToMacReplicatedMap.addLearnedEntry(dir, ip, mac);
        assertTrue(Ip4ToMacReplicatedMap.hasLearnedEntry(dir, ip, mac));
        Ip4ToMacReplicatedMap.deleteEntry(dir, ip, mac);
        assertFalse(Ip4ToMacReplicatedMap.hasLearnedEntry(dir, ip, mac));
    }

}
