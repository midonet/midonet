package org.midonet.midolman.host.services;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import scala.collection.JavaConverters;
import scala.collection.Set;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;

import org.junit.Test;

import org.midonet.cluster.data.storage.CreateWithOwnerOp;
import org.midonet.cluster.data.storage.DeleteWithOwnerOp;
import org.midonet.cluster.data.storage.PersistenceOp;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.util.UUIDUtil;
import org.midonet.midolman.host.interfaces.InterfaceDescription;
import org.midonet.midolman.host.scanner.InterfaceScanner;
import org.midonet.midolman.util.mock.MockInterfaceScanner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BackendHostServiceTest extends HostServiceTest {

    static byte MAX_ATTEMPTS = 100;
    static int WAIT_MILLIS = 500;

    public BackendHostServiceTest() {
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
        assertEquals(getOwners(hostId).isEmpty(), true);
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
        store.delete(Topology.Host.class, hostId, hostId);
        eventuallyAssertHostState();
        assertEquals(hostService.shutdownCalls, 0);
    }

    @Test
    public void hostServiceReacquiresOwnershipWhenOwnerDeleted() throws Throwable {
        TestableHostService hostService = startService();
        store.deleteOwner(Topology.Host.class, hostId, hostId);
        eventuallyAssertHostState();
        assertEquals(hostService.shutdownCalls, 0);
    }

    @Test
    public void hostServiceShutsdownAgentIfDifferentHost() throws Throwable {
        TestableHostService hostService = startService();

        Topology.Host host = get(hostId).toBuilder().setName("other").build();
        List<PersistenceOp> ops = new ArrayList<>();
        ops.add(new DeleteWithOwnerOp(Topology.Host.class, hostId,
                                      hostId.toString()));
        ops.add(new CreateWithOwnerOp(host, hostId.toString()));
        store.multi(JavaConverters.asScalaBufferConverter(ops).asScala());

        eventuallyAssertShutdown(hostService);
    }

    @Test
    public void hostServiceUpdatesHostInterfaces() throws Throwable {
        TestableHostService hostService = startService();

        assertTrue(get(hostId).getInterfacesList().isEmpty());

        MockInterfaceScanner scanner = getInterfaceScanner();
        scanner.addInterface(new InterfaceDescription("eth0"));

        assertEquals(get(hostId).getInterfacesList().size(), 1);

        scanner.removeInterface("eth0");

        assertTrue(get(hostId).getInterfacesList().isEmpty());

        stopService(hostService);
    }

    @Test
    public void hostServiceDoesNotUpdateHostInterfacesWhenStopped()
        throws Throwable {
        startAndStopService();

        assertTrue(get(hostId).getInterfacesList().isEmpty());

        MockInterfaceScanner scanner = getInterfaceScanner();
        scanner.addInterface(new InterfaceDescription("eth0"));

        assertTrue(get(hostId).getInterfacesList().isEmpty());
    }

    private void assertHostState() throws Exception {
        assertTrue(exists(hostId));
        assertEquals(get(hostId).getName(), hostName);
        assertTrue(getOwners(hostId).contains(hostId.toString()));
    }

    private boolean exists(UUID hostId) throws Exception {
        return (boolean)await(store.exists(Topology.Host.class, hostId));
    }

    private Topology.Host get(UUID hostId) throws Exception {
        return await(store.get(Topology.Host.class, hostId));
    }

    private Set<String> getOwners(UUID hostId) throws Exception {
        return await(store.getOwners(Topology.Host.class, hostId));
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
}
