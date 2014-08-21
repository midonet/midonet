/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.brain.services.vxgw;

import java.util.Collection;
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
import org.midonet.brain.southbound.midonet.MidoVxLanPeer;
import org.midonet.brain.southbound.vtep.VtepBroker;
import org.midonet.brain.southbound.vtep.VtepDataClient;
import org.midonet.brain.southbound.vtep.VtepDataClientProvider;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.data.Bridge;
import org.midonet.cluster.data.TunnelZone;
import org.midonet.cluster.data.VTEP;
import org.midonet.cluster.data.VtepBinding;
import org.midonet.cluster.data.host.Host;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZookeeperConnectionWatcher;
import org.midonet.packets.IPv4Addr;

public class VxLanGatewayServiceTest {

    /*
     * Vtep parameters
     */
    private static final IPv4Addr vtepMgmtIp = IPv4Addr.apply("192.169.0.20");
    private static final int vtepMgmntPort = 6632;
    private static final int vni = 42;

    private static final IPv4Addr vtepMgmtIp2 = IPv4Addr.apply("192.169.0.21");
    private static final int vtepMgmntPort2 = 6633;
    /*
     * Host parameters
     */
    private IPv4Addr tunnelZoneHostIp = IPv4Addr.apply("192.169.0.100");

    /*
     * Mocked components for the vtep data client
     */

    @Mocked
    private VtepDataClient vtepClient;
    @Mocked
    private VtepDataClientProvider vtepDataClientProvider;
    @Mocked
    private MidoBrainConfig config;

    private VTEP vtep = null;

    private UUID tzId = null;
    private UUID hostId = null;
    private Host host = null;

    /*
     * Midonet data client
     */
    private DataClient dataClient = null;
    private ZookeeperConnectionWatcher zkConnWatcher;

    private UUID makeUnboundBridge(String name) throws SerializationException,
                                                       StateAccessException {
        Bridge bridge = new Bridge();
        bridge.setName(name);
        return dataClient.bridgesCreate(bridge);
    }

    private UUID makeBoundBridge(String name, String vtepPort, short vlan)
        throws SerializationException, StateAccessException {
        UUID bridgeId = makeUnboundBridge(name);
        dataClient.vtepAddBinding(vtepMgmtIp, vtepPort, vlan, bridgeId);
        dataClient.bridgeCreateVxLanPort(bridgeId, vtepMgmtIp, vtepMgmntPort,
                                         vni, vtepMgmtIp, tzId);
        return bridgeId;
    }

    @Before
    public void before() throws Exception {
        HierarchicalConfiguration config = new HierarchicalConfiguration();
        BrainTestUtils.fillTestConfig(config);
        Injector injector = Guice.createInjector(
            BrainTestUtils.modules(config));

        Directory directory = injector.getInstance(Directory.class);
        BrainTestUtils.setupZkTestDirectory(directory);

        dataClient = injector.getInstance(DataClient.class);
        zkConnWatcher = new ZookeeperConnectionWatcher();

        Host host = new Host();
        host.setName("TestHost");
        UUID hostId = dataClient.hostsCreate(UUID.randomUUID(), host);

        TunnelZone tz = new TunnelZone();
        tz.setName("TestTz");
        tzId = dataClient.tunnelZonesCreate(tz);
        TunnelZone.HostConfig zoneHost = new TunnelZone.HostConfig(hostId);
        zoneHost.setIp(tunnelZoneHostIp);
        dataClient.tunnelZonesAddMembership(tzId, zoneHost);

        VTEP vtep = new VTEP();
        vtep.setId(vtepMgmtIp);
        vtep.setMgmtPort(vtepMgmntPort);
        vtep.setTunnelZone(tzId);
        dataClient.vtepCreate(vtep);
    }

    /**
     * Check the life cycle inside the gateway service.
     */
    @Test
    public void testBasicLifecycle(@Mocked VtepBroker vtepBroker) {

        new Expectations() {{
            // Per vtep
            vtepDataClientProvider.get(); result = vtepClient; times = 1;
            VtepBroker vB = new VtepBroker(vtepClient); times = 1;
            MidoVxLanPeer mP = new MidoVxLanPeer(dataClient); times = 1;
            vB.observableUpdates(); result = Observable.empty(); times = 1;
            vtepClient.connect(vtepMgmtIp, vtepMgmntPort); times = 1;
            vtepClient.getTunnelIp(); times = 1;

            vB.pruneUnwantedLogicalSwitches(new HashSet<UUID>());

            // Shutdown
            vtepClient.disconnect(); times = 1;
        }};

        VxLanGatewayService gwsrv = new VxLanGatewayService(
            dataClient, vtepDataClientProvider, zkConnWatcher, config);
        gwsrv.startAsync().awaitRunning();
        gwsrv.stopAsync().awaitTerminated();
    }

    /**
     * Test the addition of a bridge
     */
    @Test
    public void testBridgeAddition(@Mocked final VtepBroker vtepBroker,
                                   @Mocked final MidoVxLanPeer midoPeer)
        throws Exception {

        final org.opendaylight.ovsdb.lib.notation.UUID lsUuid =
            new org.opendaylight.ovsdb.lib.notation.UUID("meh");

        final VtepBinding binding =
            new VtepBinding("vtepPort", (short)666, UUID.randomUUID());

        new Expectations() {{

            // Per vtep
            vtepDataClientProvider.get(); result = vtepClient; times = 1;
            VtepBroker vB = new VtepBroker(vtepClient); times = 1;
            MidoVxLanPeer mP = new MidoVxLanPeer(dataClient); times = 1;
            mP.observableUpdates(); result = Observable.empty(); times = 1;
            vB.observableUpdates(); result = Observable.empty(); times = 1;
            vtepClient.connect(vtepMgmtIp, vtepMgmntPort); times = 1;

            mP.subscribeToFloodingProxy(
                (Observable<TunnelZoneState.FloodingProxyEvent>)any);

            vtepClient.getTunnelIp(); times = 1;

            vB.pruneUnwantedLogicalSwitches(new HashSet<UUID>());
            mP.knowsBridgeId((UUID)any);
            result = false; times = 1;

            // Consolidation of the vtep, name unknown until makeBoundBridge
            vB.ensureLogicalSwitchExists(anyString, vni);
            result = lsUuid; times = 1;
            vB.renewBindings(lsUuid, (Collection<VtepBinding>)any);
            times = 1;

            // Bridge addition
            mP.watch((UUID)withNotNull()); result = true; times = 1;

            // Syncup macs from the VTEP and MN flooding proxy
            vB.advertiseMacs(); times = 1;
            mP.advertiseFloodingProxy((UUID)any); times = 1;

            // Shutdown
            vtepClient.disconnect(); times = 1;
            mP.stop();
        }};

        VxLanGatewayService gwsrv = new VxLanGatewayService(
            dataClient, vtepDataClientProvider, zkConnWatcher, config);
        gwsrv.startAsync().awaitRunning();

        // add a new bridge with a binding
        makeBoundBridge("bridge1", "vtepPort", (short)666);

        gwsrv.stopAsync().awaitTerminated();
    }

    /**
     * Test the addition of a bridge before the service was started, it should
     * be detected normally.
     */
    @Test
    public void testEarlyBridgeAddition(@Mocked final VtepBroker vtepBroker,
                                        @Mocked final MidoVxLanPeer midoPeer)
        throws Exception {

        final org.opendaylight.ovsdb.lib.notation.UUID lsUuid =
            new org.opendaylight.ovsdb.lib.notation.UUID("meh");

        final Set<UUID> preexistingBridgeIds = new HashSet<>();

        new Expectations() {{
            // Per vtep
            vtepDataClientProvider.get(); result = vtepClient; times = 1;
            VtepBroker vB = new VtepBroker(vtepClient); times = 1;
            MidoVxLanPeer mP = new MidoVxLanPeer(dataClient); times = 1;
            mP.observableUpdates(); result = Observable.empty(); times = 1;
            vB.observableUpdates(); result = Observable.empty(); times = 1;
            vtepClient.connect(vtepMgmtIp, vtepMgmntPort); times = 1;

            mP.subscribeToFloodingProxy(
                (Observable<TunnelZoneState.FloodingProxyEvent>)any);

            vtepClient.getTunnelIp(); times = 1;

            vB.pruneUnwantedLogicalSwitches(preexistingBridgeIds);
            mP.knowsBridgeId((UUID)any);
            result = false; times = 1;

            // Consolidation of the vtep, name unknown until makeBoundBridge
            vB.ensureLogicalSwitchExists(anyString, vni);
            result = lsUuid; times = 1;
            vB.renewBindings(lsUuid, (Collection<VtepBinding>)any);
            times = 1;

            // Bridge addition
            mP.watch((UUID)withNotNull()); result = true; times = 1;
            vB.advertiseMacs(); times = 1;
            mP.advertiseFloodingProxy((UUID)any); times = 1;

            mP.knowsBridgeId((UUID)any);
            result = true; times = 1;

            // Bridge addition
            mP.watch((UUID)withNotNull()); result = false; times = 1;
            // Watch returns false, bc. it does know the bridge, so it won't
            // advertise

            // Shutdown
            vtepClient.disconnect(); times = 1;
            mP.stop();
        }};

        // add a new bridge with a binding before starting the service
        UUID id = makeBoundBridge("bridge1", "vtepPort", (short)666);
        preexistingBridgeIds.add(id);

        VxLanGatewayService gwsrv = new VxLanGatewayService(
            dataClient, vtepDataClientProvider, zkConnWatcher, config);
        gwsrv.startAsync().awaitRunning();
        gwsrv.stopAsync().awaitTerminated();
    }

    /**
     * Test the update of a bridge
     */
    @Test
    public void testBridgeUpdate(@Mocked final VtepBroker vtepBroker,
                                 @Mocked final MidoVxLanPeer midoPeer)
        throws Exception {

        final org.opendaylight.ovsdb.lib.notation.UUID lsUuid =
            new org.opendaylight.ovsdb.lib.notation.UUID("meh");

        new Expectations() {{
            // Per vtep
            vtepDataClientProvider.get(); result = vtepClient; times = 1;
            VtepBroker vB = new VtepBroker(vtepClient); times = 1;
            MidoVxLanPeer mP = new MidoVxLanPeer(dataClient); times = 1;
            mP.observableUpdates(); result = Observable.empty(); times = 1;
            vB.observableUpdates(); result = Observable.empty(); times = 1;
            vtepClient.connect(vtepMgmtIp, vtepMgmntPort); times = 1;

            mP.subscribeToFloodingProxy(
                (Observable<TunnelZoneState.FloodingProxyEvent>)any);

            vtepClient.getTunnelIp(); times = 1;

            vB.pruneUnwantedLogicalSwitches(new HashSet<UUID>());
            mP.knowsBridgeId((UUID)any);
            result = false; times = 1;

            // Consolidation of the vtep, name unknown until makeBoundBridge
            vB.ensureLogicalSwitchExists(anyString, vni);
            result = lsUuid; times = 1;
            vB.renewBindings(lsUuid, (Collection<VtepBinding>)any);
            times = 1;

            // Bridge update (vxlanport addition)
            mP.watch((UUID)withNotNull()); result = true; times = 1;
            vB.advertiseMacs(); times = 1;
            mP.advertiseFloodingProxy((UUID)any); times = 1;

            // Shutdown
            vtepClient.disconnect(); times = 1;
            mP.stop();
        }};

        VxLanGatewayService gwsrv = new VxLanGatewayService(
            dataClient, vtepDataClientProvider, zkConnWatcher, config);
        gwsrv.startAsync().awaitRunning();

        // add a new bridge without a binding
        UUID bridgeId = makeUnboundBridge("bridge1");

        // add a binding to the bridge
        dataClient.bridgeCreateVxLanPort(bridgeId, vtepMgmtIp, vtepMgmntPort,
                                         vni, vtepMgmtIp, tzId);

        // remove binding from the bridge
        dataClient.bridgeDeleteVxLanPort(bridgeId);

        gwsrv.stopAsync().awaitTerminated();
    }

    /**
     * Check the dynamic detection of vteps
     */
    @Test
    public void testVtepAddition(@Mocked final VtepBroker vtepBroker)
        throws Exception {

        // vtep related operations must be done just once
        new Expectations() {{
            // Per vtep
            vtepDataClientProvider.get(); result = vtepClient; times = 1;
            VtepBroker vB = new VtepBroker(vtepClient); times = 1;
            MidoVxLanPeer mP = new MidoVxLanPeer(dataClient); times = 1;
            vB.observableUpdates(); result = Observable.empty(); times = 1;
            vtepClient.connect(vtepMgmtIp, vtepMgmntPort); times = 1;
            vtepClient.getTunnelIp(); times = 1;

            vB.pruneUnwantedLogicalSwitches(new HashSet<UUID>());

            vtepDataClientProvider.get(); result = vtepClient; times = 1;
            VtepBroker vBAlt = new VtepBroker(vtepClient); times = 1;
            MidoVxLanPeer mPAlt = new MidoVxLanPeer(dataClient); times = 1;
            vBAlt.observableUpdates(); result = Observable.empty(); times = 1;
            vtepClient.connect(vtepMgmtIp2, vtepMgmntPort2); times = 1;
            vtepClient.getTunnelIp(); times = 1;

            vB.pruneUnwantedLogicalSwitches(new HashSet<UUID>());

            // Shutdown (one round per vtep)
            vtepClient.disconnect(); times = 1;
            vtepClient.disconnect(); times = 1;
        }};

        VxLanGatewayService gwsrv1 =
            new VxLanGatewayService(
                dataClient, vtepDataClientProvider, zkConnWatcher, config);
        // the initial vtep should be detected
        gwsrv1.startAsync().awaitRunning();

        // this should be also caught by the service
        VTEP vtepAlt = new VTEP();
        vtepAlt.setId(vtepMgmtIp2);
        vtepAlt.setMgmtPort(vtepMgmntPort2);
        vtepAlt.setTunnelZone(tzId);
        dataClient.vtepCreate(vtepAlt);

        gwsrv1.stopAsync().awaitTerminated();
    }

    /**
     * Check the dynamic deletion of vteps
     */
    @Test
    public void testVtepDeletion(@Mocked final VtepBroker vtepBroker)
        throws Exception {

        // vtep related operations must be done just once
        new Expectations() {{
            // Per vtep
            vtepDataClientProvider.get(); result = vtepClient; times = 1;
            VtepBroker vB = new VtepBroker(vtepClient); times = 1;
            MidoVxLanPeer mP = new MidoVxLanPeer(dataClient); times = 1;
            vB.observableUpdates(); result = Observable.empty(); times = 1;
            vtepClient.connect(vtepMgmtIp, vtepMgmntPort); times = 1;
            vtepClient.getTunnelIp(); times = 1;

            vB.pruneUnwantedLogicalSwitches(new HashSet<UUID>());

            // Shutdown (one round per vtep)
            vtepClient.disconnect(); times = 1;
        }};

        VxLanGatewayService gwsrv1 =
            new VxLanGatewayService(
                dataClient, vtepDataClientProvider, zkConnWatcher, config);
        // the initial vtep should be detected
        gwsrv1.startAsync().awaitRunning();

        // the removal of the vtep should also be detected
        dataClient.vtepDelete(vtepMgmtIp);

        gwsrv1.stopAsync().awaitTerminated();
    }

}
