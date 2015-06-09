package org.midonet.midolman.host.services;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;

import com.google.protobuf.TextFormat;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.api.transaction.CuratorTransactionFinal;
import org.apache.zookeeper.CreateMode;
import org.junit.Test;

import rx.Observable;

import org.midonet.cluster.data.storage.SingleValueKey;
import org.midonet.cluster.data.storage.StateKey;
import org.midonet.cluster.models.State;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.services.MidonetBackend;
import org.midonet.cluster.util.UUIDUtil;
import org.midonet.midolman.host.interfaces.InterfaceDescription;
import org.midonet.midolman.util.mock.MockInterfaceScanner;
import org.midonet.util.reactivex.RichObservable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class BackendHostServiceTest extends HostServiceTest {

    static byte MAX_ATTEMPTS = 100;
    static int WAIT_MILLIS = 500;

    public BackendHostServiceTest() throws Exception {
        super(true);
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
    public void hostServiceShutsdownAgentIfDifferentHost() throws Throwable {
        TestableHostService hostService = startService();

        CuratorFramework curator =  CuratorFrameworkFactory
            .newClient(server.getConnectString(), sessionTimeoutMs,
                       cnxnTimeoutMs, retryPolicy);
        curator.start();
        if (!curator.blockUntilConnected(1000, TimeUnit.SECONDS)) {
            throw new Exception("Curator did not connect to the test ZK "
                                + "server");
        }

        CuratorTransactionFinal txn =
            (CuratorTransactionFinal) curator.inTransaction();
        txn = txn.delete().forPath(getAlivePath(hostId)).and();
        txn = txn.create().withMode(CreateMode.EPHEMERAL)
                          .forPath(getAlivePath(hostId)).and();
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
        scanner.addInterface(new InterfaceDescription("eth0"));

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
        scanner.addInterface(new InterfaceDescription("eth0"));

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
        return injector.getInstance(CuratorFramework.class);
    }

    public String getAlivePath(UUID hostId) {
        return basePath + "/zoom/1/state/Host/" + hostId + "/alive";
    }
}
