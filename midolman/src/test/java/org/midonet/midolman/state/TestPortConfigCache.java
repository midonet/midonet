/*
 * Copyright 2012 Midokura Europe SARL
 */

package org.midonet.midolman.state;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.Injector;
import com.google.inject.Guice;
import org.apache.zookeeper.CreateMode;
import org.junit.Before;
import org.junit.Test;
import org.midonet.midolman.Setup;
import org.midonet.midolman.guice.serialization.SerializationModule;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.zkManagers.BridgeZkManager;
import org.midonet.midolman.state.zkManagers.PortGroupZkManager;
import org.midonet.midolman.state.zkManagers.PortZkManager;
import org.midonet.midolman.version.guice.VersionModule;
import org.midonet.util.eventloop.MockReactor;
import org.midonet.util.eventloop.Reactor;

import java.util.HashSet;
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.core.IsNull.nullValue;

public class TestPortConfigCache {
    private MockReactor reactor;
    private PortZkManager portMgr;
    private UUID bridgeID;
    private UUID portID;
    private UUID portGroupID;
    private PortConfigCache portCache;
    private Injector injector;

    public class TestModule extends AbstractModule {

        private final String basePath;

        public TestModule(String basePath) {
            this.basePath = basePath;
        }

        @Override
        protected void configure() {
            bind(Reactor.class).toInstance(new MockReactor());
            bind(PathBuilder.class).toInstance(new PathBuilder(basePath));
            bind(ZkConnectionAwareWatcher.class).toInstance(
                    new MockZookeeperConnectionWatcher());
        }

        @Provides @Singleton
        public Directory provideDirectory(PathBuilder paths) {
            Directory directory = new MockDirectory();
            try {
                directory.add(paths.getBasePath(), null, CreateMode.PERSISTENT);
                Setup.ensureZkDirectoryStructureExists(directory,
                        paths.getBasePath());
            } catch (Exception ex) {
                throw new RuntimeException("Could not initialize zk", ex);
            }
            return directory;
        }

        @Provides @Singleton
        public ZkManager provideZkManager(Directory directory) {
            return new ZkManager(directory);
        }

        @Provides @Singleton
        public PortZkManager providePortZkManager(ZkManager zkManager,
                                                  PathBuilder paths,
                                                  Serializer serializer) {
            return new PortZkManager(zkManager, paths, serializer);
        }

        @Provides @Singleton
        public BridgeZkManager provideBridgeZkManager(ZkManager zkManager,
                                                      PathBuilder paths,
                                                      Serializer serializer) {
            return new BridgeZkManager(zkManager, paths, serializer);
        }

        @Provides @Singleton
        public PortGroupZkManager providePortGroupZkManager(
                ZkManager zkManager, PathBuilder paths, Serializer serializer) {
            return new PortGroupZkManager(zkManager, paths, serializer);
        }

        @Provides @Singleton
        public PortConfigCache providePortConfigCache(
                Reactor reactor, Directory directory, PathBuilder paths,
                ZkConnectionAwareWatcher watcher, Serializer serializer) {
            return new PortConfigCache(reactor, directory, paths.getBasePath(),
                    watcher, serializer);
        }

    }

    @Before
    public void setUp() throws Exception {
        String basePath = "/midolman";
        injector = Guice.createInjector(
                new TestModule(basePath),
                new VersionModule(),
                new SerializationModule()
        );
        portMgr = injector.getInstance(PortZkManager.class);
        portCache = injector.getInstance(PortConfigCache.class);
        BridgeZkManager bridgeMgr = injector.getInstance(BridgeZkManager.class);
        BridgeZkManager.BridgeConfig bridgeConfig =
                new BridgeZkManager.BridgeConfig();
        bridgeID = bridgeMgr.create(bridgeConfig);

        PortGroupZkManager portGroupMgr = injector.getInstance(
                PortGroupZkManager.class);
        PortGroupZkManager.PortGroupConfig portGroupConfig =
                new PortGroupZkManager.PortGroupConfig();
        portGroupID = portGroupMgr.create(portGroupConfig);
    }

    @Test
    public void testMissingPortID() {
        assertThat("The cache returns null if the portID is missing from ZK.",
                portCache.get(UUID.randomUUID()), nullValue());
    }

    @Test
    public void testExistingPortID() throws StateAccessException,
            SerializationException {
        PortConfig config = new PortDirectory.BridgePortConfig(bridgeID);
        config.outboundFilter = UUID.randomUUID();
        config.portGroupIDs = new HashSet<UUID>();
        config.portGroupIDs.add(portGroupID);
        portID = portMgr.create(config);
        PortConfig zkConfig = portMgr.get(portID);
        assertThat("The config in ZK should have the expected outboundFilter.",
                zkConfig.outboundFilter, equalTo(config.outboundFilter));
        assertThat("The config in ZK should have the expected portGroups.",
                zkConfig.portGroupIDs, equalTo(config.portGroupIDs));
        PortConfig cachedConfig = portCache.get(portID);
        assertThat("The config in the cache should be identical to ZK's.",
                cachedConfig, equalTo(zkConfig));
    }

    private PortDirectory.BridgePortConfig getNewConfig(int greKey) {
        PortDirectory.BridgePortConfig config =
                new PortDirectory.BridgePortConfig(bridgeID);
        config.tunnelKey = greKey;
        config.inboundFilter = UUID.randomUUID();
        config.outboundFilter = UUID.randomUUID();
        config.portGroupIDs = new HashSet<UUID>();
        config.portGroupIDs.add(portGroupID);
        return config;
    }

   /* @Test
    public void testExpiration() throws StateAccessException {
        testExistingPortID();
        assertThat("The cache should contain the portID as key",
                portCache.hasKey(portID));
        // Advance the clock to allow expiration to occur.
        reactor.incrementTime(
                PortConfigCache.expiryMillis, TimeUnit.MILLISECONDS);
        // Modify the entry in ZK to trigger removal of the key.
        portMgr.update(portID, getNewConfig());
        assertThat("The cache should no longer contain the portID as key",
                !portCache.hasKey(portID));
    } */

   /* @Test
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
        portMgr.update(portID, getNewConfig());
        assertThat("The cache should still contain the portID as key",
                portCache.hasKey(portID));
        // Unpin the value
        portCache.unPin(portID);
        // Advance the clock to allow expiration to occur.
        reactor.incrementTime(
                PortConfigCache.expiryMillis, TimeUnit.MILLISECONDS);
        // Modify the entry in ZK to trigger removal of the key.
        portMgr.update(portID, getNewConfig());
        assertThat("The cache should no longer contain the portID as key",
                !portCache.hasKey(portID));
    } */

    @Test
    public void testConfigChanges() throws StateAccessException,
            SerializationException {
        testExistingPortID();
        assertThat("The cache should contain the portID as key",
                portCache.hasKey(portID));
        PortConfig cachedConfig = portCache.get(portID);
        PortConfig config = getNewConfig(cachedConfig.tunnelKey);
        assertThat("The cached config should not equal the one we specified.",
                cachedConfig, not(equalTo(config)));
        // Now update ZK.
        portMgr.update(portID, config);
        PortConfig zkConfig = portMgr.get(portID);
        assertThat("ZK's config should be equal to the one we specified.",
                zkConfig, equalTo(config));
        cachedConfig = portCache.get(portID);
        assertThat("Now the cached config should equal the one we specified.",
                cachedConfig, equalTo(config));
    }
}
