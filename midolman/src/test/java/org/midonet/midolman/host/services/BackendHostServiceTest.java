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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BackendHostServiceTest extends HostServiceTest {

    static byte MAX_ATTEMPTS = 10;
    static int WAIT_MILLIS = 50;

    public BackendHostServiceTest() {
        super(true);
    }

    @Test
    public void createsNewZkHost() throws Throwable {
        HostService hostService = startService();
        assertHostState();
        stopService(hostService);
        assertEquals(callbacks.shutdownCalls, 0);
    }

    @Test
    public void recoversZkHostIfAlive() throws Throwable {
        HostService hostService = startService();
        stopService(hostService);
        hostService = startService();
        assertHostState();
        stopService(hostService);
        assertEquals(callbacks.shutdownCalls, 0);
    }


    @Test
    public void hostServiceDoesNotOverwriteExistingHost() throws Throwable {
        startAndStopService();

        Topology.Host oldHost = get(hostId);
        Topology.Host newHost = oldHost.toBuilder()
            .addTunnelZoneIds(UUIDUtil.toProto(UUID.randomUUID()))
            .build();
        store.update(newHost);

        startAndStopService();

        Topology.Host host = get(hostId);

        assertEquals(host.getTunnelZoneIds(0), newHost.getTunnelZoneIds(0));
        assertEquals(callbacks.shutdownCalls, 0);
    }

    @Test
    public void cleanupServiceAfterStop() throws Throwable {
        startAndStopService();

        assertEquals(exists(hostId), true);
        assertEquals(getOwners(hostId).isEmpty(), true);
        assertEquals(callbacks.shutdownCalls, 0);
    }

    @Test(expected = HostService.HostIdAlreadyInUseException.class)
    public void hostServiceFailsForDifferentHostname() throws Throwable {
        Topology.Host host = Topology.Host.newBuilder()
            .setId(UUIDUtil.toProto(hostId))
            .setName("unknown")
            .build();
        store.create(host);

        startService();
    }

    @Test
    public void hostServiceReacquiresOwnershipWhenHostDeleted() throws Throwable {
        startService();
        store.delete(Topology.Host.class, hostId, hostId);
        eventuallyAssertHostState();
        assertEquals(callbacks.shutdownCalls, 0);
    }

    @Test
    public void hostServiceReacquiresOwnershipWhenOwnerDeleted() throws Throwable {
        startService();
        store.deleteOwner(Topology.Host.class, hostId, hostId);
        eventuallyAssertHostState();
        assertEquals(callbacks.shutdownCalls, 0);
    }

    @Test
    public void hostServiceShutsdownAgentIfDifferentHost() throws Throwable {
        startService();

        Topology.Host host = get(hostId).toBuilder().setName("unknown").build();
        List<PersistenceOp> ops = new ArrayList<>();
        ops.add(new DeleteWithOwnerOp(Topology.Host.class, hostId,
                                      hostId.toString()));
        ops.add(new CreateWithOwnerOp(host, hostId.toString()));
        store.multi(JavaConverters.asScalaBufferConverter(ops).asScala());

        eventuallyAssertShutdown();
    }

    private void assertHostState() throws Exception {
        assertEquals(exists(hostId), true);
        assertEquals(get(hostId).getName(), hostName);
        assertEquals(getOwners(hostId).contains(hostId.toString()), true);
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

    private void eventuallyAssertShutdown() throws Exception {
        for (byte attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try { assertTrue(callbacks.shutdownCalls > 0); return; }
            catch (Throwable e) { Thread.sleep(WAIT_MILLIS); }
        }
        throw new Exception("The host did not shut down");
    }

    static <T> T await(Future<T> future) throws Exception {
        return Await.result(future, Duration.apply(5, TimeUnit.SECONDS));
    }
}
