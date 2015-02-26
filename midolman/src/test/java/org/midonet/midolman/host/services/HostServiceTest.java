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
import org.junit.Before;
import org.junit.Test;

import org.midonet.cluster.data.storage.InMemoryStorage;
import org.midonet.cluster.data.storage.OwnershipType;
import org.midonet.cluster.data.storage.StorageWithOwnership;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.util.UUIDUtil;
import org.midonet.config.ConfigProvider;
import org.midonet.midolman.Setup;
import org.midonet.midolman.config.MidolmanConfig;
import org.midonet.midolman.guice.MidolmanModule;
import org.midonet.midolman.guice.serialization.SerializationModule;
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
    private StorageWithOwnership storage;

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
            bind(InterfaceDataUpdater.class).to(DefaultInterfaceDataUpdater.class);
            bind(InterfaceScanner.class).to(MockInterfaceScanner.class);
            bind(HostConfigProvider.class).asEagerSingleton();
            bind(ConfigProvider.class)
                .toInstance(ConfigProvider.providerForIniConfig(configuration));
            bind(HostConfig.class).toProvider(HostConfigProvider.class);
            bind(MidolmanConfig.class)
                .toProvider(MidolmanModule.MidolmanConfigProvider.class);
            bind(StorageWithOwnership.class)
                .to(InMemoryStorage.class)
                .asEagerSingleton();
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

    @Before
    public void setup() throws Exception {
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
        configuration.setProperty("midolman.cluster_storage_enabled", true);

        injector = Guice.createInjector(new SerializationModule(),
                                        new TestModule());

        zkManager = injector.getInstance(ZkManager.class);
        hostZkManager = injector.getInstance(HostZkManager.class);
        storage = injector.getInstance(StorageWithOwnership.class);
        versionPath = injector.getInstance(PathBuilder.class)
                        .getHostVersionPath(hostId, DataWriteVersion.CURRENT);
        alivePath = injector.getInstance(PathBuilder.class)
                        .getHostPath(hostId) + "/alive";
        hostName = InetAddress.getLocalHost().getHostName();

        storage.registerClass(Topology.Host.class, OwnershipType.Exclusive());
        storage.build();
    }

    private HostService makeHostService() {
        return injector.getInstance(HostService.class);
    }

    @Test
    public void createsNewZkHost() throws Throwable {
        HostService hostService = startService();
        assertHostState();
        stopService(hostService);
    }

    @Test
    public void recoversZkHostIfAlive() throws Throwable {
        HostService hostService = startService();
        stopService(hostService);
        hostService = startService();
        assertHostState();
        stopService(hostService);
    }

    @Test
    public void recoversZkHostIfNotAlive() throws Throwable {
        // This only tests the legacy storage. Cluster storage is automatically
        // cleaned-up when stopping the service.
        startAndStopService();

        zkManager.deleteEphemeral(alivePath);

        HostService hostService = startService();
        assertHostState();
        stopService(hostService);
    }

    @Test
    public void recoversZkHostVersion() throws Throwable {
        // This only tests the legacy storage.
        startAndStopService();

        zkManager.deleteEphemeral(versionPath);

        HostService hostService = startService();
        assertHostState();
        stopService(hostService);
    }

    @Test
    public void recoversZkHostAndUpdatesMetadata() throws Throwable {
        startAndStopService();

        updateMetadata();
        zkManager.deleteEphemeral(alivePath);

        HostService hostService = startService();
        assertHostState();
        stopService(hostService);
    }

    @Test(expected = HostService.HostIdAlreadyInUseException.class)
    public void recoversZkHostLoopsIfMetadataIsDifferent() throws Throwable {
        startAndStopService();

        updateMetadata();

        startAndStopService();
    }

    @Test
    public void hostServiceDoesNotOverwriteExistingHost() throws Throwable {
        startAndStopService();

        Topology.Host oldHost = await(storage.get(Topology.Host.class, hostId));
        Topology.Host newHost = oldHost.toBuilder()
            .addTunnelZoneIds(UUIDUtil.toProto(UUID.randomUUID()))
            .build();
        storage.update(newHost);

        startAndStopService();

        Topology.Host host = await(storage.get(Topology.Host.class, hostId));

        assertThat(host.getTunnelZoneIds(0), is(newHost.getTunnelZoneIds(0)));
    }

    @Test
    public void cleanupServiceAfterStop() throws Throwable {
        startAndStopService();

        assertThat((Boolean) await(storage.exists(Topology.Host.class, hostId)),
                   is(true));
        assertThat(await(storage.getOwners(Topology.Host.class, hostId))
                       .isEmpty(), is(true));
    }

    private void assertHostState() throws Exception {
        assertThat(hostZkManager.exists(hostId), is(true));
        assertThat(hostZkManager.isAlive(hostId), is(true));
        assertThat(hostZkManager.get(hostId).getName(), is(hostName));
        assertThat(zkManager.exists(versionPath), is(true));

        assertThat((Boolean)await(storage.exists(Topology.Host.class, hostId)),
                   is(true));
        assertThat(await(storage.get(Topology.Host.class, hostId)).getName(),
                   is(hostName));
        assertThat(await(storage.getOwners(Topology.Host.class, hostId))
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
