/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.midolman.state;

import java.io.IOException;
import java.util.Random;
import java.util.UUID;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import org.midonet.cluster.data.storage.Directory;

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
