/*
 * Copyright 2016 Midokura SARL
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

import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;

import com.codahale.metrics.MetricRegistry;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.protobuf.TextFormat;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigValueFactory;

import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.api.transaction.CuratorTransactionFinal;
import org.apache.curator.retry.RetryNTimes;
import org.apache.curator.test.TestingServer;
import org.apache.zookeeper.CreateMode;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import rx.Observable;

import org.midonet.cluster.data.storage.KeyType;
import org.midonet.cluster.data.storage.SingleValueKey;
import org.midonet.cluster.data.storage.StateKey;
import org.midonet.cluster.data.storage.StateStorage;
import org.midonet.cluster.data.storage.Storage;
import org.midonet.cluster.models.State;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.services.MidonetBackend;
import org.midonet.cluster.services.MidonetBackendService;
import org.midonet.cluster.storage.MidonetBackendConfig;
import org.midonet.cluster.util.UUIDUtil;
import org.midonet.conf.HostIdGenerator;
import org.midonet.conf.MidoNodeConfigurator;
import org.midonet.conf.MidoTestConfigurator;
import org.midonet.midolman.cluster.zookeeper.ZookeeperConnectionModule;
import org.midonet.midolman.config.MidolmanConfig;
import org.midonet.midolman.host.interfaces.InterfaceDescription;
import org.midonet.midolman.host.scanner.InterfaceScanner;
import org.midonet.midolman.util.mock.MockInterfaceScanner;
import org.midonet.util.eventloop.Reactor;
import org.midonet.util.reactivex.RichObservable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class HostServiceTest {
    final static byte MAX_ATTEMPTS = 100;
    final static int WAIT_MILLIS = 500;
    final static String basePath = MidoNodeConfigurator.defaultZkRootKey();

    Injector injector;
    Storage store;
    StateStorage stateStore;
    MidonetBackendConfig backendConfig;
    UUID hostId;
    String hostName;

    private final Config config;
    static TestingServer server = null;

    RetryPolicy retryPolicy = new RetryNTimes(2, 1000);
    int cnxnTimeoutMs = 2 * 1000;
    int sessionTimeoutMs = 10 * 1000;


    static class TestableHostService extends HostService {
        public int shutdownCalls = 0;

        public TestableHostService(MidolmanConfig config,
                                   MidonetBackendConfig backendConfig,
                                   MidonetBackend backend,
                                   InterfaceScanner scanner,
                                   UUID hostId,
                                   Reactor reactor) {
            super(config, backendConfig, backend, scanner, hostId, reactor);
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
            bind(InterfaceScanner.class)
                .to(MockInterfaceScanner.class)
                .asEagerSingleton();
            MidonetBackendConfig backendConfig = new MidonetBackendConfig(
                config.withFallback(MidoTestConfigurator.forAgents()));
            bind(MidonetBackendConfig.class)
                .toInstance(backendConfig);
            MidonetBackendService backend =
                new MidonetBackendService(backendConfig, curator, curator,
                                          new MetricRegistry(),
                                          scala.Option.apply(null),
                                          scala.Option.apply(null));
            bind(MidonetBackend.class).toInstance(backend);
            bind(Reactor.class)
                .toProvider(ZookeeperConnectionModule.ZookeeperReactorProvider.class)
                .asEagerSingleton();
        }
    }

    public HostServiceTest() throws Exception {
        config =  MidoTestConfigurator.forAgents()
            .withValue("zookeeper.root_key",
                       ConfigValueFactory.fromAnyRef(basePath))
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

        injector = Guice.createInjector(new TestModule(curator));

        store = injector.getInstance(MidonetBackend.class).store();
        stateStore = injector.getInstance(MidonetBackend.class).stateStore();
        hostName = InetAddress.getLocalHost().getHostName();
        backendConfig = injector.getInstance(MidonetBackendConfig.class);

        curator.start();
        if (!curator.blockUntilConnected(1000, TimeUnit.SECONDS)) {
            throw new Exception("Curator did not connect to the test ZK "
                                + "server");
        }

        store.registerClass(Topology.Host.class);
        stateStore.registerKey(Topology.Host.class,
                               MidonetBackend.AliveKey(),
                               KeyType.SingleLastWriteWins());
        stateStore.registerKey(Topology.Host.class,
                               MidonetBackend.HostKey(),
                               KeyType.SingleLastWriteWins());
        store.build();
    }

    @After
    public void teardown() throws Exception {
        injector.getInstance(MidonetBackend.class).curator().close();
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
        return new TestableHostService(
            new MidolmanConfig(config, ConfigFactory.empty()),
            backendConfig,
            injector.getInstance(MidonetBackend.class),
            getInterfaceScanner(),
            hostId,
            injector.getInstance(Reactor.class));
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

    @Test
    public void createsNewZkHost() throws Throwable {
        TestableHostService hostService = startService();
        assertHostState();
        stopService(hostService);
        assertEquals(hostService.shutdownCalls, 0);
    }

    @Test
    public void recoversZkHostIfAlive() throws Throwable {
        TestableHostService hostService = startService();
        stopService(hostService);
        hostService = startService();
        assertHostState();
        stopService(hostService);
        assertEquals(hostService.shutdownCalls, 0);
    }

    @Test
    public void hostServiceDoesNotOverwriteExistingHost() throws Throwable {
        TestableHostService hostService = startAndStopService();

        Topology.Host oldHost = get(hostId);
        Topology.Host newHost = oldHost.toBuilder()
            .addTunnelZoneIds(UUIDUtil.toProto(UUID.randomUUID()))
            .build();
        store.update(newHost);

        startAndStopService();

        Topology.Host host = get(hostId);

        assertEquals(host.getTunnelZoneIds(0), newHost.getTunnelZoneIds(0));
        assertEquals(hostService.shutdownCalls, 0);
    }

    @Test
    public void cleanupServiceAfterStop() throws Throwable {
        TestableHostService hostService = startAndStopService();

        assertEquals(exists(hostId), true);
        assertFalse(isAlive(hostId));
        assertNull(getHostState(hostId));
        assertEquals(hostService.shutdownCalls, 0);
    }

    @Test(expected = HostService.HostIdAlreadyInUseException.class)
    public void hostServiceFailsForDifferentHostname() throws Throwable {
        Topology.Host host = Topology.Host.newBuilder()
            .setId(UUIDUtil.toProto(hostId))
            .setName("other")
            .build();
        store.create(host);

        startService();
    }

    @Test
    public void hostServiceReacquiresOwnershipWhenHostDeleted() throws Throwable {
        TestableHostService hostService = startService();
        store.delete(Topology.Host.class, hostId);
        eventuallyAssertHostState();
        stopService(hostService);
        assertEquals(hostService.shutdownCalls, 0);
    }

    @Test
    public void hostServiceReacquiresOwnershipWhenAliveDeleted() throws Throwable {
        TestableHostService hostService = startService();
        getCurator().delete().forPath(getAlivePath(hostId));
        eventuallyAssertHostState();
        stopService(hostService);
        assertEquals(hostService.shutdownCalls, 0);
    }

    @Test
    public void hostServiceAcquiresOwnershipAfterFailure() throws Throwable {
        TestableHostService hostService = startService();

        CuratorFramework curator = getNewCurator();
        byte[] currentState = curator.getData().forPath(getStatePath(hostId));

        stopService(hostService);

        CuratorTransactionFinal txn =
            (CuratorTransactionFinal) curator.inTransaction();
        txn = txn.create().withMode(CreateMode.EPHEMERAL)
            .forPath(getAlivePath(hostId)).and();
        txn = txn.create().withMode(CreateMode.EPHEMERAL)
            .forPath(getStatePath(hostId), currentState).and();
        txn.commit();

        hostService = startService();
        assertHostState();
        stopService(hostService);

        curator.close();
    }

    @Test
    public void hostServiceShutsdownAgentIfDifferentHostname() throws Throwable {
        TestableHostService hostService = startService();

        CuratorFramework curator = getNewCurator();

        Topology.Host host = Topology.Host.newBuilder()
            .setId(UUIDUtil.toProto(hostId))
            .setName("different-hostname")
            .build();

        CuratorTransactionFinal txn =
            (CuratorTransactionFinal) curator.inTransaction();
        txn = txn.delete().forPath(getAlivePath(hostId)).and();
        txn = txn.create().withMode(CreateMode.EPHEMERAL)
                          .forPath(getAlivePath(hostId)).and();
        txn = txn.delete().forPath(getHostPath(hostId)).and();
        txn = txn.create().withMode(CreateMode.PERSISTENT)
                          .forPath(getHostPath(hostId),
                                   host.toString().getBytes()).and();
        txn.commit();

        eventuallyAssertShutdown(hostService);

        curator.close();
    }

    @Test
    public void hostServiceShutsdownAgentIfDifferentInterfaceMacs() throws Throwable {
        TestableHostService hostService = startService();

        CuratorFramework curator = getNewCurator();

        State.HostState hostState = State.HostState.newBuilder()
            .setHostId(UUIDUtil.toProto(hostId))
            .addInterfaces(State.HostState.Interface.newBuilder()
                .setName("if")
                .setMac("00:01:02:03:04:05")
                .setType(State.HostState.Interface.Type.PHYSICAL))
            .build();

        CuratorTransactionFinal txn =
            (CuratorTransactionFinal) curator.inTransaction();
        txn = txn.delete().forPath(getAlivePath(hostId)).and();
        txn = txn.create().withMode(CreateMode.EPHEMERAL)
            .forPath(getAlivePath(hostId)).and();
        txn = txn.delete().forPath(getStatePath(hostId)).and();
        txn = txn.create().withMode(CreateMode.EPHEMERAL)
            .forPath(getStatePath(hostId),
                     hostState.toString().getBytes()).and();
        txn.commit();

        eventuallyAssertShutdown(hostService);

        curator.close();
    }

    @Test
    public void hostServiceUpdatesHostInterfaces() throws Throwable {
        TestableHostService hostService = startService();
        State.HostState hostState;

        hostState = getHostState(hostId);
        assertNotNull(hostState);
        assertTrue(hostState.hasHostId());
        assertEquals(UUIDUtil.fromProto(hostState.getHostId()), hostId);
        assertEquals(hostState.getInterfacesCount(), 0);

        MockInterfaceScanner scanner = getInterfaceScanner();
        scanner.addInterface(new InterfaceDescription("eth0", 1));

        hostState = getHostState(hostId);
        assertNotNull(hostState);
        assertTrue(hostState.hasHostId());
        assertEquals(UUIDUtil.fromProto(hostState.getHostId()), hostId);
        assertEquals(hostState.getInterfacesCount(), 1);
        assertEquals(hostState.getInterfaces(0).getName(), "eth0");

        scanner.removeInterface("eth0");

        hostState = getHostState(hostId);
        assertNotNull(hostState);
        assertTrue(hostState.hasHostId());
        assertEquals(UUIDUtil.fromProto(hostState.getHostId()), hostId);
        assertEquals(hostState.getInterfacesCount(), 0);

        stopService(hostService);
    }

    @Test
    public void hostServiceDoesNotUpdateHostInterfacesWhenStopped()
        throws Throwable {
        startAndStopService();

        assertNull(getHostState(hostId));

        MockInterfaceScanner scanner = getInterfaceScanner();
        scanner.addInterface(new InterfaceDescription("eth0", 1));

        assertNull(getHostState(hostId));
    }

    private void assertHostState() throws Exception {
        assertTrue(exists(hostId));
        assertEquals(get(hostId).getName(), hostName);
        assertTrue(isAlive(hostId));
    }

    private boolean exists(UUID hostId) throws Exception {
        return (boolean)await(store.exists(Topology.Host.class, hostId));
    }

    private Topology.Host get(UUID hostId) throws Exception {
        return await(store.get(Topology.Host.class, hostId));
    }

    private Boolean isAlive(UUID hostId) throws Exception {
        StateKey key = await(stateStore.getKey(Topology.Host.class, hostId,
                                               MidonetBackend.AliveKey()));
        return !key.isEmpty();
    }

    private State.HostState getHostState(UUID hostId) throws Exception {
        StateKey key = await(stateStore.getKey(Topology.Host.class, hostId,
                                               MidonetBackend.HostKey()));

        if (key.isEmpty()) return null;

        String value = ((SingleValueKey)key).value().get();

        State.HostState.Builder builder = State.HostState.newBuilder();
        TextFormat.merge(value, builder);
        return builder.build();
    }

    private void eventuallyAssertHostState() throws Exception {
        for (byte attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try { assertHostState(); return; }
            catch (Throwable e) { Thread.sleep(WAIT_MILLIS); }
        }
        throw new Exception("Eventually host did not exist");
    }

    private void eventuallyAssertShutdown(TestableHostService hostService)
        throws Exception {
        for (byte attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try { assertTrue(hostService.shutdownCalls > 0); return; }
            catch (Throwable e) { Thread.sleep(WAIT_MILLIS); }
        }
        throw new Exception("The host did not shut down");
    }

    static <T> T await(Future<T> future) throws Exception {
        return Await.result(future, Duration.apply(5, TimeUnit.SECONDS));
    }

    static <T> T await(Observable<T> observable) throws Exception {
        return await(new RichObservable<>(observable).asFuture());
    }

    public CuratorFramework getCurator() {
        return injector.getInstance(MidonetBackend.class).curator();
    }

    public CuratorFramework getNewCurator() throws Exception {
        CuratorFramework curator =  CuratorFrameworkFactory
            .newClient(server.getConnectString(), sessionTimeoutMs,
                       cnxnTimeoutMs, retryPolicy);
        curator.start();
        if (!curator.blockUntilConnected(1000, TimeUnit.SECONDS)) {
            throw new Exception("Curator did not connect to the test ZK "
                                + "server");
        }
        return curator;
    }

    public String getHostPath(UUID hostId) {
        return backendConfig.rootKey() + "/zoom/0/models/Host/" + hostId;
    }

    public String getAlivePath(UUID hostId) {
        return backendConfig.rootKey() + "/zoom/0/state/"
               + stateStore.namespace() + "/Host/" + hostId + "/alive";
    }

    public String getStatePath(UUID hostId) {
        return backendConfig.rootKey() + "/zoom/0/state/"
               + stateStore.namespace() + "/Host/" + hostId + "/host";
    }
}
