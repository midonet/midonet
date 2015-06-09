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
package org.midonet.midolman.host.services;

import java.net.InetAddress;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigValueFactory;

import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryNTimes;
import org.apache.curator.test.TestingServer;
import org.apache.zookeeper.CreateMode;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;

import org.midonet.cluster.data.storage.KeyType;
import org.midonet.cluster.data.storage.StateStorage;
import org.midonet.cluster.data.storage.Storage;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.services.MidonetBackend;
import org.midonet.cluster.services.MidonetBackendService;
import org.midonet.cluster.storage.MidonetBackendConfig;
import org.midonet.conf.HostIdGenerator;
import org.midonet.conf.MidoTestConfigurator;
import org.midonet.midolman.Setup;
import org.midonet.midolman.cluster.serialization.SerializationModule;
import org.midonet.midolman.cluster.zookeeper.ZookeeperConnectionModule;
import org.midonet.midolman.config.MidolmanConfig;
import org.midonet.midolman.host.scanner.InterfaceScanner;
import org.midonet.midolman.host.state.HostZkManager;
import org.midonet.midolman.host.updater.DefaultInterfaceDataUpdater;
import org.midonet.midolman.host.updater.InterfaceDataUpdater;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.MockDirectory;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.ZkManager;
import org.midonet.midolman.util.mock.MockInterfaceScanner;
import org.midonet.midolman.version.DataWriteVersion;
import org.midonet.util.eventloop.Reactor;

public abstract class HostServiceTest {

    Injector injector;

    ZkManager zkManager;
    HostZkManager hostZkManager;
    Storage store;
    StateStorage stateStore;

    UUID hostId;
    String hostName;
    String versionPath;
    String alivePath;
    String basePath = "/midolman";

    private final boolean backendEnabled;
    private final Config config;
    static TestingServer server = null;

    RetryPolicy retryPolicy = new RetryNTimes(2, 1000);
    int cnxnTimeoutMs = 2 * 1000;
    int sessionTimeoutMs = 10 * 1000;

    static class TestableHostService extends HostService {
        public int shutdownCalls = 0;

        @Inject
        public TestableHostService(MidolmanConfig config,
                                   MidonetBackendConfig backendConfig,
                                   MidonetBackend backend,
                                   InterfaceScanner scanner,
                                   InterfaceDataUpdater interfaceDataUpdater,
                                   HostZkManager hostZkManager,
                                   ZkManager zkManager,
                                   Reactor reactor) {
            super(config, backendConfig, backend, scanner, interfaceDataUpdater,
                  hostZkManager, zkManager, reactor);
        }

        @Override
        public void shutdown() {
            shutdownCalls++;
        }
    }

    private class TestModule extends AbstractModule {

        private final CuratorFramework curator;

        public TestModule(CuratorFramework curator) {
            this.curator = curator;
        }

        @Override
        protected void configure() {
            bind(InterfaceDataUpdater.class)
                .to(DefaultInterfaceDataUpdater.class);
            bind(InterfaceScanner.class)
                .to(MockInterfaceScanner.class)
                .asEagerSingleton();
            bind(MidolmanConfig.class)
                .toInstance(new MidolmanConfig(config, ConfigFactory.empty()));
            bind(MidonetBackendConfig.class)
                .toInstance(
                    new MidonetBackendConfig(config.withFallback(
                        MidoTestConfigurator.forAgents())));
            bind(CuratorFramework.class)
                .toInstance(curator);
            bind(MidonetBackend.class)
                .to(MidonetBackendService.class).asEagerSingleton();
            bind(Reactor.class)
                .toProvider(ZookeeperConnectionModule.ZookeeperReactorProvider.class)
                .asEagerSingleton();
            bind(HostService.class)
                .to(TestableHostService.class).asEagerSingleton();
        }

        @Provides @Singleton
        public Directory provideDirectory(PathBuilder paths) {
            Directory directory = new MockDirectory();
            try {
                directory.add(paths.getBasePath(), null, CreateMode.PERSISTENT);
                Setup.ensureZkDirectoryStructureExists(directory, basePath);
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
        public HostZkManager provideHostZkManager(ZkManager zkManager,
                                                  PathBuilder paths,
                                                  Serializer serializer) {
            return new HostZkManager(zkManager, paths, serializer);
        }
    }

    public HostServiceTest(boolean backendEnabled) throws Exception {
        this.backendEnabled = backendEnabled;
        config =  MidoTestConfigurator.forAgents()
            .withValue("zookeeper.root_key",
                       ConfigValueFactory.fromAnyRef(basePath))
            .withValue("zookeeper.use_new_stack",
                       ConfigValueFactory.fromAnyRef(backendEnabled))
            .withValue("agent.host.wait_time_gen_id",
                       ConfigValueFactory.fromAnyRef(0))
            .withValue("agent.host.retries_gen_id",
                       ConfigValueFactory.fromAnyRef(0));
    }

    @Before
    public void setup() throws Exception {
        HostIdGenerator.useTemporaryHostId();
        hostId = HostIdGenerator.getHostId();

        CuratorFramework curator = CuratorFrameworkFactory
            .newClient(server.getConnectString(), sessionTimeoutMs,
                       cnxnTimeoutMs, retryPolicy);

        injector = Guice.createInjector(new SerializationModule(),
                                        new TestModule(curator));

        zkManager = injector.getInstance(ZkManager.class);
        hostZkManager = injector.getInstance(HostZkManager.class);
        store = injector.getInstance(MidonetBackend.class).store();
        stateStore = injector.getInstance(MidonetBackend.class).stateStore();
        versionPath = injector.getInstance(PathBuilder.class)
                              .getHostVersionPath(hostId,
                                                  DataWriteVersion.CURRENT);
        alivePath = injector.getInstance(PathBuilder.class)
                            .getHostPath(hostId) + "/alive";
        hostName = InetAddress.getLocalHost().getHostName();

        if (backendEnabled) {
            curator.start();
            if (!curator.blockUntilConnected(1000, TimeUnit.SECONDS)) {
                throw new Exception("Curator did not connect to the test ZK "
                                    + "server");
            }

            store.registerClass(Topology.Host.class);
            stateStore.registerKey(Topology.Host.class,
                                   MidonetBackend.AliveKey(),
                                   KeyType.SingleFirstWriteWins());
            stateStore.registerKey(Topology.Host.class,
                                   MidonetBackend.HostKey(),
                                   KeyType.SingleFirstWriteWins());
            store.build();
        }
    }

    @After
    public void teardown() throws Exception {
        if (backendEnabled) {
            injector.getInstance(CuratorFramework.class).close();
        }
    }

    @BeforeClass
    public static void init() throws Exception {
        server = new TestingServer(true);
    }

    @AfterClass
    public static void cleanup() throws Exception {
        server.stop();
    }

    private TestableHostService makeHostService() {
        return injector.getInstance(TestableHostService.class);
    }

    MockInterfaceScanner getInterfaceScanner() {
        return (MockInterfaceScanner) injector.getInstance(InterfaceScanner.class);
    }

    TestableHostService startService() throws Throwable {
        TestableHostService hostService = makeHostService();
        try {
            hostService.startAsync().awaitRunning();
        } catch (RuntimeException e) {
            throw e.getCause();
        }
        return hostService;
    }

    void stopService(HostService hostService) throws Throwable {
        hostService.stopAsync().awaitTerminated();
    }

    TestableHostService startAndStopService() throws Throwable {
        TestableHostService hostService = startService();
        stopService(hostService);
        return hostService;
    }
}
