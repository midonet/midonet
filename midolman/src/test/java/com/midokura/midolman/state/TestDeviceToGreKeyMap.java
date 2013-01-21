package com.midokura.midolman.state;

import java.io.IOException;
import java.util.Random;
import java.util.UUID;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class TestDeviceToGreKeyMap {

    DeviceToGreKeyMap bridgeDir;
    Random rand;

    @Before
    public void setUp() {
        Directory dir = new MockDirectory();
        bridgeDir = new DeviceToGreKeyMap(dir);
        rand = new Random();
    }

    @Test
    public void testAddGetUpdateDelete() throws IOException, KeeperException,
            InterruptedException {
        UUID[] bridgeIds = new UUID[5];
        for (int i = 0; i < bridgeIds.length; i++) {
            bridgeIds[i] = new UUID(rand.nextLong(), rand.nextLong());
            bridgeDir.add(bridgeIds[i], i * 1000);
        }
        for (int i = 0; i < bridgeIds.length; i++) {
            Assert.assertEquals(i * 1000, bridgeDir.getGreKey(bridgeIds[i]));
            bridgeDir.setGreKey(bridgeIds[i], i * 222);
        }
        for (int i = 0; i < bridgeIds.length; i++) {
            Assert.assertEquals(i * 222, bridgeDir.getGreKey(bridgeIds[i]));
            bridgeDir.delete(bridgeIds[i]);
            try {
                bridgeDir.getGreKey(bridgeIds[i]);
                Assert.fail("getTunnelKey should throw NoNodeException.");
            } catch (NoNodeException e) {
            }
        }
    }
}
