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

import com.google.inject.Inject;
import com.typesafe.config.ConfigFactory;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigValueFactory;
import org.apache.zookeeper.CreateMode;
import org.junit.Before;

import org.midonet.conf.MidoTestConfigurator;
import org.midonet.conf.HostIdGenerator;
import org.midonet.cluster.data.storage.OwnershipType;
import org.midonet.cluster.data.storage.StorageWithOwnership;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.services.MidonetBackend;
import org.midonet.cluster.storage.MidonetBackendConfig;
import org.midonet.cluster.storage.MidonetTestBackend;
import org.midonet.midolman.Setup;
import org.midonet.midolman.cluster.serialization.SerializationModule;
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

public abstract class HostServiceTest {

    private final boolean backendEnabled;
    private Injector injector;

    ZkManager zkManager;
    HostZkManager hostZkManager;
    StorageWithOwnership store;

    UUID hostId;
    String hostName;
    String versionPath;
    String alivePath;
    String basePath = "/midolman";

    private Config config = MidoTestConfigurator.forAgents()
        .withValue("zookeeper.root_key", ConfigValueFactory.fromAnyRef(basePath))
        .withValue("agent.host.wait_time_gen_id", ConfigValueFactory.fromAnyRef(0))
        .withValue("agent.host.retries_gen_id", ConfigValueFactory.fromAnyRef(0));

    static class TestableHostService extends HostService {
        public int shutdownCalls = 0;

        @Inject
        public TestableHostService(MidolmanConfig config,
                                   MidonetBackendConfig backendConfig,
                                   MidonetBackend backend,
                                   InterfaceScanner scanner,
                                   InterfaceDataUpdater interfaceDataUpdater,
                                   HostZkManager hostZkManager,
                                   ZkManager zkManager) {
            super(config, backendConfig, backend, scanner, interfaceDataUpdater,
                  hostZkManager, zkManager);
        }

        @Override
        public void shutdown() {
            shutdownCalls++;
        }
    }

    private class TestModule extends AbstractModule {

        @Override
        protected void configure() {
            bind(InterfaceDataUpdater.class)
                .to(DefaultInterfaceDataUpdater.class);
            bind(InterfaceScanner.class)
                .to(MockInterfaceScanner.class)
                .asEagerSingleton();
            bind(MidolmanConfig.class)
                .toInstance(new MidolmanConfig(config, ConfigFactory.empty()));
            bind(MidonetBackend.class)
                .to(MidonetTestBackend.class).asEagerSingleton();
            bind(MidonetBackendConfig.class)
                .toInstance(
                    new MidonetBackendConfig(config.withFallback(
                        MidoTestConfigurator.forAgents())));
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

    public HostServiceTest(boolean backendEnabled) {
        this.backendEnabled = backendEnabled;
    }

    @Before
    public void setup() throws Exception {
        HostIdGenerator.useTemporaryHostId();
        hostId = HostIdGenerator.getHostId();
        config = config.withValue("zookeeper.use_new_stack",
            ConfigValueFactory.fromAnyRef(backendEnabled));

        injector = Guice.createInjector(new SerializationModule(),
                                        new TestModule());

        zkManager = injector.getInstance(ZkManager.class);
        hostZkManager = injector.getInstance(HostZkManager.class);
        store = injector.getInstance(MidonetBackend.class).ownershipStore();
        versionPath = injector.getInstance(PathBuilder.class)
                              .getHostVersionPath(hostId,
                                                  DataWriteVersion.CURRENT);
        alivePath = injector.getInstance(PathBuilder.class)
                            .getHostPath(hostId) + "/alive";
        hostName = InetAddress.getLocalHost().getHostName();

        store.registerClass(Topology.Host.class, OwnershipType.Exclusive());
        store.build();
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
