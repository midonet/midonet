/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midolman.state;

import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.zookeeper.CreateMode;
import org.junit.Before;
import org.junit.Test;

import com.midokura.midolman.Setup;
import com.midokura.midolman.eventloop.MockReactor;
import com.midokura.midolman.state.PortDirectory.BridgePortConfig;


import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.core.IsNull.nullValue;

public class TestPortConfigCache {
    private MockReactor reactor;
    private PortZkManager portMgr;
    private UUID bridgeID;
    private UUID portID;
    private PortConfigCache portCache;

    @Before
    public void setUp() throws Exception {
        String basePath = "/midolman";
        Directory dir = new MockDirectory();
        dir.add(basePath, null, CreateMode.PERSISTENT);
        Setup.createZkDirectoryStructure(dir, basePath);
        portMgr = new PortZkManager(dir, basePath);
        reactor = new MockReactor();
        portCache = new PortConfigCache(reactor, dir, basePath);

        BridgeZkManager bridgeMgr = new BridgeZkManager(dir, basePath);
        BridgeZkManager.BridgeConfig bridgeConfig =
                new BridgeZkManager.BridgeConfig();
        bridgeID = bridgeMgr.create(bridgeConfig);
    }

    @Test
    public void testMissingPortID() {
        assertThat("The cache returns null if the portID is missing from ZK.",
                portCache.get(UUID.randomUUID()), nullValue());
    }

    @Test
    public void testExistingPortID() throws StateAccessException {
        PortConfig config = new BridgePortConfig(bridgeID);
        config.outboundFilter = UUID.randomUUID();
        config.portGroupIDs = new HashSet<UUID>();
        config.portGroupIDs.add(UUID.randomUUID());
        portID = portMgr.create(config);
        PortConfig zkConfig = portMgr.get(portID).value;
        assertThat("The config in ZK should have the expected outboundFilter.",
                zkConfig.outboundFilter, equalTo(config.outboundFilter));
        assertThat("The config in ZK should have the expected portGroups.",
                zkConfig.portGroupIDs, equalTo(config.portGroupIDs));
        PortConfig cachedConfig = portCache.get(portID);
        assertThat("The config in the cache should be identical to ZK's.",
                cachedConfig, equalTo(zkConfig));
    }

    private BridgePortConfig getNewConfig() {
        BridgePortConfig config = new BridgePortConfig(bridgeID);
        config.greKey = 0;
        config.inboundFilter = UUID.randomUUID();
        config.outboundFilter = UUID.randomUUID();
        config.portGroupIDs = new HashSet<UUID>();
        config.portGroupIDs.add(UUID.randomUUID());
        return config;
    }

    @Test
    public void testExpiration() throws StateAccessException {
        testExistingPortID();
        assertThat("The cache should contain the portID as key",
                portCache.hasKey(portID));
        // Advance the clock to allow expiration to occur.
        reactor.incrementTime(
                PortConfigCache.expiryMillis, TimeUnit.MILLISECONDS);
        // Modify the entry in ZK to trigger removal of the key.
        portMgr.update(new ZkNodeEntry<UUID, PortConfig>(
                portID, getNewConfig()));
        assertThat("The cache should no longer contain the portID as key",
                !portCache.hasKey(portID));
    }

    @Test
    public void testPinning() throws StateAccessException {
        testExistingPortID();
        assertThat("The cache should contain the portID as key",
                portCache.hasKey(portID));
        // Pin the value.
        portCache.pin(portID);
        // Advance the clock and verify that expiration did not occur.
        reactor.incrementTime(
                PortConfigCache.expiryMillis, TimeUnit.MILLISECONDS);
        // Modify the entry in ZK to trigger removal of the key.
        portMgr.update(new ZkNodeEntry<UUID, PortConfig>(
                portID, getNewConfig()));
        assertThat("The cache should still contain the portID as key",
                portCache.hasKey(portID));
        // Unpin the value
        portCache.unPin(portID);
        // Advance the clock to allow expiration to occur.
        reactor.incrementTime(
                PortConfigCache.expiryMillis, TimeUnit.MILLISECONDS);
        // Modify the entry in ZK to trigger removal of the key.
        portMgr.update(new ZkNodeEntry<UUID, PortConfig>(
                portID, getNewConfig()));
        assertThat("The cache should no longer contain the portID as key",
                !portCache.hasKey(portID));
    }

    @Test
    public void testConfigChanges() throws StateAccessException {
        testExistingPortID();
        assertThat("The cache should contain the portID as key",
                portCache.hasKey(portID));
        PortConfig cachedConfig = portCache.get(portID);
        PortConfig config = getNewConfig();
        assertThat("The cached config should not equal the one we specified.",
                cachedConfig, not(equalTo(config)));
        // Now update ZK.
        portMgr.update(new ZkNodeEntry<UUID, PortConfig>(portID, config));
        PortConfig zkConfig = portMgr.get(portID).value;
        assertThat("ZK's config should be equal to the one we specified.",
                zkConfig, equalTo(config));
        cachedConfig = portCache.get(portID);
        assertThat("Now the cached config should equal the one we specified.",
                cachedConfig, equalTo(config));
    }
}
