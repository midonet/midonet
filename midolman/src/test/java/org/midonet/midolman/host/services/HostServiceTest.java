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
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Provides;
import com.google.inject.Singleton;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.zookeeper.CreateMode;
import org.junit.Test;

import org.midonet.cluster.data.storage.OwnershipType;
import org.midonet.cluster.data.storage.StorageWithOwnership;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.services.MidonetBackend;
import org.midonet.cluster.storage.MidonetBackendConfig;
import org.midonet.cluster.storage.MidonetBackendConfigProvider;
import org.midonet.cluster.storage.MidonetTestBackend;
import org.midonet.cluster.util.UUIDUtil;
import org.midonet.config.ConfigProvider;
import org.midonet.midolman.Setup;
import org.midonet.midolman.cluster.MidolmanModule;
import org.midonet.midolman.cluster.serialization.SerializationModule;
import org.midonet.midolman.config.MidolmanConfig;
import org.midonet.midolman.host.config.HostConfig;
import org.midonet.midolman.host.guice.HostConfigProvider;
import org.midonet.midolman.host.scanner.InterfaceScanner;
import org.midonet.midolman.host.state.HostDirectory;
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

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

public class HostServiceTest {
    private ZkManager zkManager;
    private HostZkManager hostZkManager;
    private StorageWithOwnership store;

    private UUID hostId;
    private String hostName;
    private String versionPath;
    private String alivePath;
    private HierarchicalConfiguration configuration;
    private Injector injector;

    public class TestModule extends AbstractModule {
        String basePath = "/midolman";

        @Override
        protected void configure() {
            bind(PathBuilder.class).toInstance(new PathBuilder(basePath));
            bind(InterfaceDataUpdater.class)
                .to(DefaultInterfaceDataUpdater.class);
            bind(InterfaceScanner.class).to(MockInterfaceScanner.class);
            bind(HostConfigProvider.class).asEagerSingleton();
            bind(ConfigProvider.class)
                .toInstance(ConfigProvider.providerForIniConfig(configuration));
            bind(HostConfig.class).toProvider(HostConfigProvider.class);
            bind(MidonetBackendConfig.class)
                .toProvider(MidonetBackendConfigProvider.class);
            bind(MidolmanConfig.class)
                .toProvider(MidolmanModule.MidolmanConfigProvider.class);
            bind(MidonetBackend.class)
                .to(MidonetTestBackend.class).asEagerSingleton();
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

    public void setup(Boolean backendEnabled) throws Exception {
        hostId = UUID.randomUUID();
        configuration = new HierarchicalConfiguration();
        configuration.addNodes(HostConfig.GROUP_NAME,
                Arrays.asList(new HierarchicalConfiguration.Node
                        ("host_uuid", hostId.toString())
                ));
        configuration.addNodes(HostConfig.GROUP_NAME,
                Arrays.asList(new HierarchicalConfiguration.Node
                        ("retries_gen_id", "0")
                ));
        configuration.setProperty("midonet-backend.enabled", backendEnabled);

        injector = Guice.createInjector(new SerializationModule(),
                                        new TestModule());

        zkManager = injector.getInstance(ZkManager.class);
        hostZkManager = injector.getInstance(HostZkManager.class);
        store = injector.getInstance(MidonetBackend.class).ownershipStore();
        versionPath = injector.getInstance(PathBuilder.class)
                        .getHostVersionPath(hostId, DataWriteVersion.CURRENT);
        alivePath = injector.getInstance(PathBuilder.class)
                        .getHostPath(hostId) + "/alive";
        hostName = InetAddress.getLocalHost().getHostName();

        store.registerClass(Topology.Host.class, OwnershipType.Exclusive());
        store.build();
    }

    private HostService makeHostService() {
        return injector.getInstance(HostService.class);
    }

    @Test
    public void createsNewZkHostInLegacyStore() throws Throwable {
        setup(false);
        HostService hostService = startService();
        assertLegacyHostState();
        stopService(hostService);
    }

    @Test
    public void createsNewZkHostInBackendStore() throws Throwable {
        setup(true);
        HostService hostService = startService();
        assertBackendHostState();
        stopService(hostService);
    }

    @Test
    public void recoversZkHostIfAliveInLegacyStore() throws Throwable {
        setup(false);
        HostService hostService = startService();
        stopService(hostService);
        hostService = startService();
        assertLegacyHostState();
        stopService(hostService);
    }

    @Test
    public void recoversZkHostIfAliveInBackendStore() throws Throwable {
        setup(true);
        HostService hostService = startService();
        stopService(hostService);
        hostService = startService();
        assertBackendHostState();
        stopService(hostService);
    }

    @Test
    public void recoversZkHostIfNotAliveInLegacyStore() throws Throwable {
        setup(false);
        startAndStopService();
        zkManager.deleteEphemeral(alivePath);
        HostService hostService = startService();
        assertLegacyHostState();
        stopService(hostService);
    }

    @Test
    public void recoversZkHostVersionInLegacyStore() throws Throwable {
        setup(false);
        startAndStopService();
        zkManager.deleteEphemeral(versionPath);
        HostService hostService = startService();
        assertLegacyHostState();
        stopService(hostService);
    }

    @Test
    public void recoversZkHostAndUpdatesMetadataInLegacyStore() throws Throwable {
        setup(false);
        startAndStopService();

        updateMetadata();
        zkManager.deleteEphemeral(alivePath);

        HostService hostService = startService();
        assertLegacyHostState();
        stopService(hostService);
    }

    @Test(expected = HostService.HostIdAlreadyInUseException.class)
    public void recoversZkHostLoopsIfMetadataIsDifferentInLegacyStore()
        throws Throwable {
        setup(false);
        startAndStopService();

        updateMetadata();

        startAndStopService();
    }

    @Test
    public void hostServiceDoesNotOverwriteExistingHostInBackendStore()
        throws Throwable {
        setup(true);
        startAndStopService();

        Topology.Host oldHost = await(store.get(Topology.Host.class, hostId));
        Topology.Host newHost = oldHost.toBuilder()
            .addTunnelZoneIds(UUIDUtil.toProto(UUID.randomUUID()))
            .build();
        store.update(newHost);

        startAndStopService();

        Topology.Host host = await(store.get(Topology.Host.class, hostId));

        assertThat(host.getTunnelZoneIds(0), is(newHost.getTunnelZoneIds(0)));
    }

    @Test
    public void cleanupServiceAfterStopInBackendStore() throws Throwable {
        setup(true);
        startAndStopService();

        assertThat((Boolean) await(store.exists(Topology.Host.class, hostId)),
                   is(true));
        assertThat(await(store.getOwners(Topology.Host.class, hostId))
                       .isEmpty(), is(true));
    }

    private void assertLegacyHostState() throws Exception {
        assertThat(hostZkManager.exists(hostId), is(true));
        assertThat(hostZkManager.isAlive(hostId), is(true));
        assertThat(hostZkManager.get(hostId).getName(), is(hostName));
        assertThat(zkManager.exists(versionPath), is(true));
    }

    private void assertBackendHostState() throws Exception {
        assertThat((Boolean)await(store.exists(Topology.Host.class, hostId)),
                   is(true));
        assertThat(await(store.get(Topology.Host.class, hostId)).getName(),
                   is(hostName));
        assertThat(await(store.getOwners(Topology.Host.class, hostId))
                       .contains(hostId.toString()), is(true));
    }

    private HostService startService() throws Throwable {
        HostService hostService = makeHostService();
        try {
            hostService.startAsync().awaitRunning();
        } catch (RuntimeException e) {
            throw e.getCause();
        }
        return hostService;
    }

    private void stopService(HostService hostService) throws Throwable {
        hostService.stopAsync().awaitTerminated();
    }

    private void startAndStopService() throws Throwable {
        stopService(startService());
    }

    private void updateMetadata() throws Exception {
        HostDirectory.Metadata metadata = new HostDirectory.Metadata();
        metadata.setName("name");
        hostZkManager.updateMetadata(hostId, metadata);
    }

    private static <T> T await(Future<T> future) throws Exception {
        return Await.result(future, Duration.apply(5, TimeUnit.SECONDS));
    }
}
