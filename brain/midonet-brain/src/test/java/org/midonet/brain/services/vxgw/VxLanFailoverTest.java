/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.brain.services.vxgw;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import com.google.inject.Guice;
import com.google.inject.Injector;

import mockit.Expectations;
import mockit.Mocked;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.junit.Before;
import org.junit.Test;

import rx.Observable;

import org.midonet.brain.BrainTestUtils;
import org.midonet.brain.configuration.MidoBrainConfig;
import org.midonet.brain.southbound.vtep.VtepBroker;
import org.midonet.brain.southbound.vtep.VtepDataClient;
import org.midonet.brain.southbound.vtep.VtepDataClientProvider;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.data.TunnelZone;
import org.midonet.cluster.data.VTEP;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZookeeperConnectionWatcher;
import org.midonet.packets.IPv4Addr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VxLanFailoverTest {
    private static final byte[] VTEP_MGMT_IP = { (byte)192, (byte)168 };
    private static final int VTEP_MGMT_PORT = 6632;

    private DataClient midoClient;
    private ZookeeperConnectionWatcher zkConnWatcher;

    @Mocked
    private VtepDataClient vtepClient;
    @Mocked
    private VtepDataClientProvider vtepDataClientProvider;
    @Mocked
    private MidoBrainConfig config;

    @Before
    public void setup() throws Exception {
        HierarchicalConfiguration config = new HierarchicalConfiguration();
        BrainTestUtils.fillTestConfig(config);
        Injector injector = Guice.createInjector(
            BrainTestUtils.modules(config));

        Directory directory = injector.getInstance(Directory.class);
        BrainTestUtils.setupZkTestDirectory(directory);

        midoClient = injector.getInstance(DataClient.class);
        zkConnWatcher = new ZookeeperConnectionWatcher();
    }

    @Test
    public void testFailoverOneVtep(@Mocked final VtepBroker vtepBroker)
        throws Exception {

        UUID tzId = createTunnelZone(1);
        IPv4Addr vtepId = createVtep(1, tzId);

        new Expectations() {{
            vtepDataClientProvider.get(); result = vtepClient; times = 1;

            VtepBroker vB1 = new VtepBroker(vtepClient); times = 1;
            vB1.observableUpdates(); result = Observable.empty(); times = 1;
            vtepClient.connect(getVtepAddress(1), VTEP_MGMT_PORT); times = 1;

            vB1.pruneUnwantedLogicalSwitches(new HashSet<UUID>());

            vtepClient.disconnect(); times = 1;

            vtepDataClientProvider.get(); result = vtepClient; times = 1;

            VtepBroker vB2 = new VtepBroker(vtepClient); times = 1;
            vB2.observableUpdates(); result = Observable.empty(); times = 1;
            vtepClient.connect(getVtepAddress(1), VTEP_MGMT_PORT); times = 1;

            vB2.pruneUnwantedLogicalSwitches(new HashSet<UUID>());

            vtepClient.disconnect(); times = 1;
        }};

        VxLanGatewayService srv1 = createService();
        VxLanGatewayService srv2 = createService();

        // Service 1 should be the owner of the VTEP.
        srv1.startAsync().awaitRunning();
        assertEquals(srv1.getOwnedVteps().size(), 1);
        assertTrue(srv1.getOwnedVteps().contains(vtepId));

        // Service 2 should not be the owner of the VTEP.
        srv2.startAsync().awaitRunning();
        assertEquals(srv2.getOwnedVteps().size(), 0);

        // Service 2 should be the owner of the VTEP.
        srv1.stopAsync().awaitTerminated();
        assertEquals(srv1.getOwnedVteps().size(), 0);
        assertEquals(srv2.getOwnedVteps().size(), 1);
        assertTrue(srv2.getOwnedVteps().contains(vtepId));

        // Service 2 should not be the owner of the VTEP.
        srv2.stopAsync().awaitTerminated();
        assertEquals(srv2.getOwnedVteps().size(), 0);
    }

    @Test
    public void testFailoverThreeVteps(@Mocked final VtepBroker vtepBroker)
        throws Exception {

        UUID tzId = createTunnelZone(1);
        IPv4Addr vtepId1 = createVtep(1, tzId);
        IPv4Addr vtepId2 = createVtep(2, tzId);
        IPv4Addr vtepId3 = createVtep(3, tzId);

        new Expectations() {{
            vtepDataClientProvider.get(); result = vtepClient; times = 1;

            VtepBroker vB1_1 = new VtepBroker(vtepClient); times = 1;
            vB1_1.observableUpdates(); result = Observable.empty(); times = 1;
            vtepClient.connect((IPv4Addr)any, VTEP_MGMT_PORT); times = 1;

            vB1_1.pruneUnwantedLogicalSwitches(new HashSet<UUID>());

            vtepDataClientProvider.get(); result = vtepClient; times = 1;

            VtepBroker vB1_2 = new VtepBroker(vtepClient); times = 1;
            vB1_2.observableUpdates(); result = Observable.empty(); times = 1;
            vtepClient.connect((IPv4Addr)any, VTEP_MGMT_PORT); times = 1;

            vB1_2.pruneUnwantedLogicalSwitches(new HashSet<UUID>());

            vtepDataClientProvider.get(); result = vtepClient; times = 1;

            VtepBroker vB1_3 = new VtepBroker(vtepClient); times = 1;
            vB1_3.observableUpdates(); result = Observable.empty(); times = 1;
            vtepClient.connect((IPv4Addr)any, VTEP_MGMT_PORT); times = 1;

            vB1_3.pruneUnwantedLogicalSwitches(new HashSet<UUID>());

            vtepClient.disconnect(); times = 1;

            vtepDataClientProvider.get(); result = vtepClient; times = 1;

            VtepBroker vB2_1 = new VtepBroker(vtepClient); times = 1;
            vB2_1.observableUpdates(); result = Observable.empty(); times = 1;
            vtepClient.connect((IPv4Addr)any, VTEP_MGMT_PORT); times = 1;

            vB2_1.pruneUnwantedLogicalSwitches(new HashSet<UUID>());

            vtepClient.disconnect(); times = 1;

            vtepDataClientProvider.get(); result = vtepClient; times = 1;

            VtepBroker vB2_2 = new VtepBroker(vtepClient); times = 1;
            vB2_2.observableUpdates(); result = Observable.empty(); times = 1;
            vtepClient.connect((IPv4Addr)any, VTEP_MGMT_PORT); times = 1;

            vB2_2.pruneUnwantedLogicalSwitches(new HashSet<UUID>());

            vtepClient.disconnect(); times = 1;

            vtepDataClientProvider.get(); result = vtepClient; times = 1;

            VtepBroker vB2_3 = new VtepBroker(vtepClient); times = 1;
            vB2_3.observableUpdates(); result = Observable.empty(); times = 1;
            vtepClient.connect((IPv4Addr)any, VTEP_MGMT_PORT); times = 1;

            vB2_3.pruneUnwantedLogicalSwitches(new HashSet<UUID>());

            vtepClient.disconnect(); times = 3;
        }};

        VxLanGatewayService srv1 = createService();
        VxLanGatewayService srv2 = createService();

        // Service 1 should be the owner of the VTEP.
        srv1.startAsync().awaitRunning();
        assertEquals(srv1.getOwnedVteps().size(), 3);
        assertTrue(srv1.getOwnedVteps().contains(vtepId1));
        assertTrue(srv1.getOwnedVteps().contains(vtepId2));
        assertTrue(srv1.getOwnedVteps().contains(vtepId3));

        // Service 2 should not be the owner of the VTEP.
        srv2.startAsync().awaitRunning();
        assertEquals(srv2.getOwnedVteps().size(), 0);

        // Service 2 should be the owner of the VTEP.
        srv1.stopAsync().awaitTerminated();
        assertEquals(srv1.getOwnedVteps().size(), 0);
        assertEquals(srv2.getOwnedVteps().size(), 3);
        assertTrue(srv2.getOwnedVteps().contains(vtepId1));
        assertTrue(srv2.getOwnedVteps().contains(vtepId2));
        assertTrue(srv2.getOwnedVteps().contains(vtepId3));

        // Service 2 should not be the owner of the VTEP.
        srv2.stopAsync().awaitTerminated();
        assertEquals(srv2.getOwnedVteps().size(), 0);
    }

    @Test
    public void testFailoverConcurrent(@Mocked final VtepBroker vtepBroker)
        throws Exception {

        UUID tzId = createTunnelZone(1);

        new Expectations() {{
            vtepDataClientProvider.get(); result = vtepClient; times = 1;

            VtepBroker vB1 = new VtepBroker(vtepClient); times = 1;
            vB1.observableUpdates(); result = Observable.empty(); times = 1;
            vtepClient.connect(getVtepAddress(1), VTEP_MGMT_PORT); times = 1;

            vB1.pruneUnwantedLogicalSwitches(new HashSet<UUID>());

            vtepDataClientProvider.get(); result = vtepClient; times = 1;

            VtepBroker vB2 = new VtepBroker(vtepClient); times = 1;
            vB2.observableUpdates(); result = Observable.empty(); times = 1;
            vtepClient.connect(getVtepAddress(2), VTEP_MGMT_PORT); times = 1;

            vB2.pruneUnwantedLogicalSwitches(new HashSet<UUID>());

            vtepDataClientProvider.get(); result = vtepClient; times = 1;

            VtepBroker vB3 = new VtepBroker(vtepClient); times = 1;
            vB3.observableUpdates(); result = Observable.empty(); times = 1;
            vtepClient.connect(getVtepAddress(3), VTEP_MGMT_PORT); times = 1;

            vB3.pruneUnwantedLogicalSwitches(new HashSet<UUID>());

            vtepClient.disconnect(); times = 3;
        }};

        VxLanGatewayService srv1 = createService();
        VxLanGatewayService srv2 = createService();
        VxLanGatewayService srv3 = createService();

        srv1.startAsync().awaitRunning();
        srv2.startAsync().awaitRunning();
        srv3.startAsync().awaitRunning();

        IPv4Addr vtepId1 = createVtep(1, tzId);
        assertEquals(getVteps(srv1, srv2, srv3).size(), 1);
        assertTrue(getVteps(srv1, srv2, srv3).contains(vtepId1));
        IPv4Addr vtepId2 = createVtep(2, tzId);
        assertEquals(getVteps(srv1, srv2, srv3).size(), 2);
        assertTrue(getVteps(srv1, srv2, srv3).contains(vtepId1));
        assertTrue(getVteps(srv1, srv2, srv3).contains(vtepId2));
        IPv4Addr vtepId3 = createVtep(3, tzId);
        assertEquals(getVteps(srv1, srv2, srv3).size(), 3);
        assertTrue(getVteps(srv1, srv2, srv3).contains(vtepId1));
        assertTrue(getVteps(srv1, srv2, srv3).contains(vtepId2));
        assertTrue(getVteps(srv1, srv2, srv3).contains(vtepId3));

        midoClient.vtepDelete(vtepId1);
        assertEquals(getVteps(srv1, srv2, srv3).size(), 2);
        assertFalse(getVteps(srv1, srv2, srv3).contains(vtepId1));
        midoClient.vtepDelete(vtepId2);
        assertEquals(getVteps(srv1, srv2, srv3).size(), 1);
        assertFalse(getVteps(srv1, srv2, srv3).contains(vtepId2));
        midoClient.vtepDelete(vtepId3);
        assertTrue(getVteps(srv1, srv2, srv3).isEmpty());

        srv1.stopAsync().awaitTerminated();
        srv2.stopAsync().awaitTerminated();
        srv3.stopAsync().awaitTerminated();
    }

    public VxLanGatewayService createService() {
        return new VxLanGatewayService(midoClient, vtepDataClientProvider,
                                       zkConnWatcher, config);
    }

    public UUID createTunnelZone(int index)
        throws StateAccessException, SerializationException {

        // Create the tunnel zone.
        TunnelZone tzone = new TunnelZone();
        tzone.setName("TunnelZone-" + index);
        tzone.setType(TunnelZone.Type.vtep);
        return midoClient.tunnelZonesCreate(tzone);
    }

    public IPv4Addr createVtep(int index, UUID tzoneId)
        throws StateAccessException, SerializationException {

        // Create the VTEP.
        VTEP vtep = new VTEP();
        vtep.setId(getVtepAddress(index));
        vtep.setMgmtPort(VTEP_MGMT_PORT);
        vtep.setTunnelZone(tzoneId);
        midoClient.vtepCreate(vtep);

        return vtep.getId();
    }

    public static IPv4Addr getVtepAddress(int value) {
        return IPv4Addr.apply(new byte[] {
            VTEP_MGMT_IP[0],
            VTEP_MGMT_IP[1],
            (byte)((value >> 8) & 0xFF),
            (byte)(value & 0xFF)
        });
    }

    public static Set<IPv4Addr> getVteps(VxLanGatewayService... services) {
        Set<IPv4Addr> vteps = new HashSet<>();

        for (VxLanGatewayService service : services) {
            for (IPv4Addr vtep : service.getOwnedVteps()) {
                boolean result = vteps.add(vtep);
                assertTrue(result);
            }
        }

        return vteps;
    }
}
