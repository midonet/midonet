// Copyright 2011 Midokura Inc.

package com.midokura.midolman.state;

import java.util.UUID;

import com.midokura.midolman.packets.MAC;
import com.midokura.midolman.state.MacPortMap;
import com.midokura.midolman.state.MockDirectory;

import org.apache.zookeeper.KeeperException;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class TestMacPortMap {

    MacPortMap map;

    @Before
    public void setUp() {
        MockDirectory dir = new MockDirectory();
        map = new MacPortMap(dir);
        map.start();
    }

    @Test
    public void testContainsEntryAfterPut() 
                throws KeeperException, InterruptedException {
        byte[] mac1 = { 0, 1, 2, 3, 4, 5 };
        byte[] mac2 = { 0, 1, 2, 3, 4, 5 };
        UUID port = UUID.fromString("c1fbd793-1ce9-42a1-86dc-65bbcaa945c0");
        map.put(new MAC(mac1), port);
        assertEquals(port, map.get(new MAC(mac2)));
    }
}
