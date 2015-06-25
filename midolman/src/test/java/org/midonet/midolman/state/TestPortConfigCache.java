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

import java.util.HashSet;
import java.util.UUID;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Provides;
import com.google.inject.Singleton;

import org.apache.zookeeper.CreateMode;
import org.junit.Before;
import org.junit.Test;

import org.midonet.midolman.Setup;
import org.midonet.midolman.cluster.serialization.SerializationModule;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.zkManagers.BridgeZkManager;
import org.midonet.midolman.state.zkManagers.ChainZkManager;
import org.midonet.midolman.state.zkManagers.ChainZkManager.ChainConfig;
import org.midonet.midolman.state.zkManagers.PortGroupZkManager;
import org.midonet.midolman.state.zkManagers.PortZkManager;
import org.midonet.util.eventloop.MockReactor;
import org.midonet.util.eventloop.Reactor;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.core.IsNull.nullValue;

public class TestPortConfigCache {
    private MockReactor reactor;
    private PortZkManager portMgr;
    private ChainZkManager chainZkManager;
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
            return new ZkManager(directory, basePath);
        }

        @Provides @Singleton
        public PortZkManager providePortZkManager(ZkManager zkManager,
                                                  PathBuilder paths,
                                                  Serializer serializer) {
            return new PortZkManager(zkManager, paths, serializer);
        }

        @Provides @Singleton
        public ChainZkManager provideChainZkManager(ZkManager zkManager,
                                                    PathBuilder paths,
                                                    Serializer serializer) {
            return new ChainZkManager(zkManager, paths, serializer);
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
                new SerializationModule()
        );
        portMgr = injector.getInstance(PortZkManager.class);
        chainZkManager = injector.getInstance(ChainZkManager.class);
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
                portCache.getSync(UUID.randomUUID()), nullValue());
    }

    @Test
    public void testExistingPortID() throws StateAccessException,
            SerializationException {
        PortConfig config = new PortDirectory.BridgePortConfig(bridgeID);
        ChainConfig chainConfig = new ChainConfig("random");
        UUID chainId = chainZkManager.create(chainConfig);
        config.outboundFilter = chainId;
        config.portGroupIDs = new HashSet<UUID>();
        config.portGroupIDs.add(portGroupID);
        portID = portMgr.create(config);
        PortConfig zkConfig = portMgr.get(portID);
        assertThat("The config in ZK should have the expected outboundFilter.",
                zkConfig.outboundFilter, equalTo(config.outboundFilter));
        assertThat("The config in ZK should have the expected portGroups.",
                zkConfig.portGroupIDs, equalTo(config.portGroupIDs));
        PortConfig cachedConfig = portCache.getSync(portID);
        assertThat("The config in the cache should be identical to ZK's.",
                cachedConfig, equalTo(zkConfig));
    }

    private PortDirectory.BridgePortConfig getNewConfig(int greKey)
            throws SerializationException, StateAccessException {
        PortDirectory.BridgePortConfig config =
                new PortDirectory.BridgePortConfig(bridgeID);
        ChainConfig inChainConfig = new ChainConfig("IN");
        ChainConfig outChainConfig = new ChainConfig("OUT");
        UUID inChainId = chainZkManager.create(inChainConfig);
        UUID outChainId = chainZkManager.create(outChainConfig);
        config.tunnelKey = greKey;
        config.inboundFilter = inChainId;
        config.outboundFilter = outChainId;
        config.portGroupIDs = new HashSet<UUID>();
        config.portGroupIDs.add(portGroupID);
        return config;
    }

    @Test
    public void testConfigChanges() throws StateAccessException,
            SerializationException {
        testExistingPortID();
        assertThat("The cache should contain the portID as key",
                portCache.hasKey(portID));
        PortConfig cachedConfig = portCache.getSync(portID);
        PortConfig config = getNewConfig(cachedConfig.tunnelKey);
        assertThat("The cached config should not equal the one we specified.",
                cachedConfig, not(equalTo(config)));
        // Now update ZK.
        portMgr.update(portID, config);
        PortConfig zkConfig = portMgr.get(portID);
        assertThat("ZK's config should be equal to the one we specified.",
                zkConfig, equalTo(config));
        cachedConfig = portCache.getSync(portID);
        assertThat("Now the cached config should equal the one we specified.",
                cachedConfig, equalTo(config));
    }
}
