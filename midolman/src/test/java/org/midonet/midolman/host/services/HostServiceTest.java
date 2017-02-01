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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.typesafe.config.ConfigFactory;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigValueFactory;
import org.apache.zookeeper.CreateMode;
import org.junit.Test;

import org.midonet.conf.MidoTestConfigurator;
import org.midonet.conf.HostIdGenerator;
import org.midonet.cluster.data.storage.OwnershipType;
import org.midonet.cluster.data.storage.StorageWithOwnership;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.storage.MidonetBackendConfig;
import org.midonet.cluster.util.UUIDUtil;
import org.midonet.midolman.Setup;
import org.midonet.midolman.cluster.serialization.SerializationModule;
import org.midonet.midolman.config.MidolmanConfig;
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

    private UUID hostId;
    private String hostName;
    private String versionPath;
    private String alivePath;
    private Injector injector;

    String basePath = "/midolman";
    private Config config = MidoTestConfigurator.forAgents().
            withValue("zookeeper.root_key", ConfigValueFactory.fromAnyRef(basePath)).
            withValue("agent.host.retries_gen_id", ConfigValueFactory.fromAnyRef(0));

    public class TestModule extends AbstractModule {

        @Override
        protected void configure() {
            bind(PathBuilder.class).toInstance(new PathBuilder(config.getString("zookeeper.root_key")));
            bind(InterfaceDataUpdater.class).to(DefaultInterfaceDataUpdater.class);
            bind(InterfaceScanner.class).to(MockInterfaceScanner.class);
            bind(MidolmanConfig.class).toInstance(new MidolmanConfig(config, ConfigFactory.empty()));
            bind(MidonetBackendConfig.class).toInstance(
                    new MidonetBackendConfig(config.withFallback(MidoTestConfigurator.forAgents())));
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
        HostIdGenerator.useTemporaryHostId();
        hostId = HostIdGenerator.getHostId();

        injector = Guice.createInjector(new SerializationModule(),
                                        new TestModule());

        zkManager = injector.getInstance(ZkManager.class);
        hostZkManager = injector.getInstance(HostZkManager.class);
        versionPath = injector.getInstance(PathBuilder.class)
                        .getHostVersionPath(hostId, DataWriteVersion.CURRENT);
        alivePath = injector.getInstance(PathBuilder.class)
                        .getHostPath(hostId) + "/alive";
        hostName = InetAddress.getLocalHost().getHostName();
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
    public void recoversZkHostIfAliveInLegacyStore() throws Throwable {
        setup(false);
        HostService hostService = startService();
        stopService(hostService);
        hostService = startService();
        assertLegacyHostState();
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
    public void removeInterfacesUponSessionChange() throws Throwable {
        setup(false);

        // Create fake interface simulating a previous crash
        hostZkManager.createHost(hostId, new HostDirectory.Metadata());
        HostDirectory.Interface iface = new HostDirectory.Interface();
        iface.setName("test_interface");
        hostZkManager.createInterface(hostId, iface);

        Set<String> ifaces = hostZkManager.getInterfaces(hostId);
        assertThat(ifaces.size(), is(1));
        assertThat(ifaces.iterator().next(), is("test_interface"));

        // When starting the service (so the zk session changes)
        HostService host = startService();
        // Then the interface is removed
        assertThat(hostZkManager.getInterfaces(hostId).size(), is(0));
        stopService(host);
    }

    private void assertLegacyHostState() throws Exception {
        assertThat(hostZkManager.exists(hostId), is(true));
        assertThat(hostZkManager.isAlive(hostId), is(true));
        assertThat(hostZkManager.get(hostId).getName(), is(hostName));
        assertThat(zkManager.exists(versionPath), is(true));
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
