/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.brain.services.vxgw;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.Random;
import java.util.UUID;

import com.google.inject.Guice;
import com.google.inject.Injector;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.junit.Before;
import org.junit.Test;

import rx.Observable;
import rx.Subscription;

import org.midonet.brain.BrainTestUtils;
import org.midonet.brain.configuration.MidoBrainConfig;
import org.midonet.brain.southbound.vtep.VtepBroker;
import org.midonet.brain.southbound.vtep.VtepConstants;
import org.midonet.brain.southbound.vtep.VtepDataClient;
import org.midonet.brain.southbound.vtep.VtepDataClientFactory;
import org.midonet.brain.southbound.vtep.VtepException;
import org.midonet.brain.southbound.vtep.VtepMAC;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.data.Bridge;
import org.midonet.cluster.data.TunnelZone;
import org.midonet.cluster.data.VTEP;
import org.midonet.cluster.data.VtepBinding;
import org.midonet.cluster.data.host.Host;
import org.midonet.midolman.host.state.HostZkManager;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZookeeperConnectionWatcher;
import org.midonet.packets.IPv4Addr;
import org.midonet.util.functors.Callback;

import mockit.Expectations;
import mockit.Mocked;

import static org.junit.Assert.assertEquals;

public class VxLanFloodingProxyTest {
    private static final byte[] VTEP_MGMT_IP = { (byte)192, (byte)168 };
    private static final byte[] HOST_IP = { (byte)10, (byte)2 };
    private static final int VTEP_MGMT_PORT = 6632;
    private static final int VTEP_VNI = 10001;

    private DataClient midoClient;
    private ZookeeperConnectionWatcher zkConnWatcher;
    private HostZkManager hostZkManager;

    private static final class MockRandom extends Random {
        @Override
        public int nextInt(int n) {
            return n - 1;
        }
    }

    private static final Subscription emptySubscription =
        Observable.empty().subscribe();

    @Mocked
    private VtepDataClient vtepClient;
    @Mocked
    private VtepDataClientFactory vtepDataClientFactory;
    @Mocked
    private MidoBrainConfig brainConfig;

    private VxLanGatewayService vxgwService;

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
        hostZkManager = injector.getInstance(HostZkManager.class);

        vxgwService = new VxLanGatewayService(
            midoClient, vtepDataClientFactory, zkConnWatcher, brainConfig,
            new MockRandom());
    }

    /**
     * This tests checks that there is no flooding proxy cleared or set in a
     * scenario with 1 tunnel zone, 1 VTEP, 1 host, and 0 bridges. All entities
     * exist before the starting the VXGW service.
     *
     * The VXGW should not clear or set a flooding proxy because there are no
     * logical switches, and the existing host is not alive.
     */
    @Test
    public void testNoBridges(@Mocked final VtepBroker vtepBroker)
        throws Exception {

        new Expectations() {{
            vtepDataClientFactory.connect(getVtepAddress(1), VTEP_MGMT_PORT,
                                           (UUID)any);
            result = vtepClient; times = 1;

            VtepBroker vB = new VtepBroker(vtepClient); times = 1;
            vtepClient.onConnected(
                (Callback<VtepDataClient, VtepException>)any);
            result = emptySubscription; times = 1;
            vB.observableUpdates(); result = Observable.empty(); times = 1;
            vtepClient.getTunnelIp(); times = 1;

            vtepClient.disconnect((UUID)any, true);
            times = 1;
        }};

        createHost(1);
        UUID tzId = createTunnelZone(1);
        createVtep(1, tzId);

        vxgwService.startAsync().awaitRunning();
        assertEquals(vxgwService.getFloodingProxy(tzId), null);
        vxgwService.stopAsync().awaitTerminated();
    }

    /**
     * This test checks that there is no flooding proxy set in a scenario with
     * 1 tunnel zone, 1 VTEP, 1 host and 1 bridge. Because of the double
     * notification of a bridge creation, the VXGW will clear the flooding proxy
     * once. All entities exist before the starting the VXGW service.
     *
     * The VXGW should not set a flooding proxy, because there are no member
     * hosts in the tunnel zone, and the existing host is not alive.
     */
    @Test
    public void testNoTunnelZoneMembership(@Mocked final VtepBroker vtepBroker)
        throws Exception {

        createHost(1);
        UUID tzId = createTunnelZone(1);
        IPv4Addr vtepId = createVtep(1, tzId);
        final UUID bridgeId = createBridge(1, vtepId, getVtepAddress(1), tzId);

        new Expectations() {{
            vtepDataClientFactory.connect(getVtepAddress(1), VTEP_MGMT_PORT,
                                           (UUID)any);
            result = vtepClient; times = 1;

            VtepBroker vB = new VtepBroker(vtepClient); times = 1;
            vtepClient.onConnected(
                (Callback<VtepDataClient, VtepException>)any);
            result = emptySubscription; times = 1;
            vB.observableUpdates(); result = Observable.empty(); times = 1;
            vtepClient.getTunnelIp(); times = 1;

            vB.ensureLogicalSwitchExists(
                VtepConstants.bridgeIdToLogicalSwitchName(bridgeId), anyInt);
            times = 1;
            vB.renewBindings((org.opendaylight.ovsdb.lib.notation.UUID)any,
                             (Collection<VtepBinding>)any); times = 1;

            vB.advertiseMacs(); times = 1;

            vtepClient.disconnect((UUID)any, true);
            times = 1;
        }};

        vxgwService.startAsync().awaitRunning();
        assertEquals(vxgwService.getFloodingProxy(tzId), null);
        vxgwService.stopAsync().awaitTerminated();
    }

    /**
     * This test checks that there is no flooding proxy set in a scenario with
     * 1 tunnel zone, 1 VTEP, 1 host and 1 bridge, and where the host is a
     * a member of the tunnel zone. Because of the double notification of a
     * bridge creation, the VXGW will clear the flooding proxy once. All
     * entities exist before the starting the VXGW service.
     *
     * The VXGW should not set a flooding proxy the existing host is not alive.
     */
    @Test
    public void testExistingBridgeHostNotAlive(
        @Mocked final VtepBroker vtepBroker) throws Exception {

        UUID hostId = createHost(1);
        UUID tzId = createTunnelZone(1);
        addTunnelZoneMember(tzId, hostId);
        IPv4Addr vtepId = createVtep(1, tzId);
        final UUID bridgeId = createBridge(1, vtepId, getVtepAddress(1), tzId);

        new Expectations() {{
            vtepDataClientFactory.connect(getVtepAddress(1), VTEP_MGMT_PORT,
                                           (UUID)any);
            result = vtepClient; times = 1;

            VtepBroker vB = new VtepBroker(vtepClient); times = 1;
            vtepClient.onConnected(
                (Callback<VtepDataClient, VtepException>)any);
            result = emptySubscription; times = 1;
            vB.observableUpdates(); result = Observable.empty(); times = 1;
            vtepClient.getTunnelIp(); times = 1;

            vB.ensureLogicalSwitchExists(
                VtepConstants.bridgeIdToLogicalSwitchName(bridgeId), anyInt);
            times = 1;
            vB.renewBindings((org.opendaylight.ovsdb.lib.notation.UUID)any,
                             (Collection<VtepBinding>)any); times = 1;

            vB.advertiseMacs(); times = 1;

            vtepClient.disconnect((UUID)any, true);
            times = 1;
        }};

        vxgwService.startAsync().awaitRunning();
        assertEquals(vxgwService.getFloodingProxy(tzId), null);
        vxgwService.stopAsync().awaitTerminated();
    }

    /**
     * This test checks that there is no flooding proxy set in a scenario with
     * 1 tunnel zone, 1 VTEP, 1 host and 1 bridge, and where the host is a
     * a member of the tunnel zone. Because of the double notification of a
     * bridge creation, the VXGW will clear the flooding proxy once. All
     * entities exist before the starting the VXGW service.
     *
     * The VXGW should not set a flooding proxy the existing host is not alive.
     */
    @Test
    public void testExistingBridgeHostAlive(
        @Mocked final VtepBroker vtepBroker) throws Exception {

        UUID hostId = createHost(1);
        hostZkManager.makeAlive(hostId);
        UUID tzId = createTunnelZone(1);
        addTunnelZoneMember(tzId, hostId);
        final IPv4Addr vtepId = createVtep(1, tzId);
        final UUID bridgeId = createBridge(1, vtepId, getVtepAddress(1), tzId);

        new Expectations() {{
            vtepDataClientFactory.connect(getVtepAddress(1), VTEP_MGMT_PORT,
                                           (UUID)any);
            result = vtepClient; times = 1;

            VtepBroker vB = new VtepBroker(vtepClient); times = 1;
            vtepClient.onConnected(
                (Callback<VtepDataClient, VtepException>)any);
            result = emptySubscription; times = 1;
            vB.observableUpdates(); result = Observable.empty(); times = 1;
            vtepClient.getTunnelIp(); times = 1;

            vB.ensureLogicalSwitchExists(
                VtepConstants.bridgeIdToLogicalSwitchName(bridgeId), anyInt);
            times = 1;
            vB.renewBindings((org.opendaylight.ovsdb.lib.notation.UUID)any,
                             (Collection<VtepBinding>)any); times = 1;

            vB.advertiseMacs(); times = 1;

            // Set flooding proxy to host 1.
            vB.apply(new MacLocation(
                VtepMAC.UNKNOWN_DST, null,
                VtepConstants.bridgeIdToLogicalSwitchName(bridgeId),
                IPv4Addr.fromBytes(getHostAddress(1).getAddress())));
            times = 1;

            vtepClient.disconnect((UUID)any, true);
            times = 1;
        }};

        vxgwService.startAsync().awaitRunning();
        assertEquals(vxgwService.getFloodingProxy(tzId), hostId);
        vxgwService.stopAsync().awaitTerminated();
    }

    /**
     * This test checks that there is no flooding proxy set in a scenario with
     * 1 tunnel zone, 1 VTEP, 1 host and 1 bridge. Because of the double
     * notification of a bridge creation, the VXGW will clear the flooding proxy
     * once. All entities exist before the starting the VXGW service, but the
     * host is added to the tunnel zone membership after the VXGW service
     * started.
     *
     * The VXGW should not set a flooding proxy, because the existing host is
     * not alive.
     */
    @Test
    public void testTunnelZoneMembershipNotifyHostNotAlive(
        @Mocked final VtepBroker vtepBroker) throws Exception {

        UUID hostId = createHost(1);
        UUID tzId = createTunnelZone(1);
        IPv4Addr vtepId = createVtep(1, tzId);
        final UUID bridgeId = createBridge(1, vtepId, getVtepAddress(1), tzId);

        new Expectations() {{
            vtepDataClientFactory.connect(getVtepAddress(1), VTEP_MGMT_PORT,
                                           (UUID)any);
            result = vtepClient; times = 1;

            VtepBroker vB = new VtepBroker(vtepClient); times = 1;
            vtepClient.onConnected(
                (Callback<VtepDataClient, VtepException>)any);
            result = emptySubscription; times = 1;
            vB.observableUpdates(); result = Observable.empty(); times = 1;
            vtepClient.getTunnelIp(); times = 1;

            vB.ensureLogicalSwitchExists(
                VtepConstants.bridgeIdToLogicalSwitchName(bridgeId), anyInt);
            times = 1;
            vB.renewBindings((org.opendaylight.ovsdb.lib.notation.UUID)any,
                             (Collection<VtepBinding>)any); times = 1;

            vB.advertiseMacs(); times = 1;

            vtepClient.disconnect((UUID)any, true);
            times = 1;
        }};

        vxgwService.startAsync().awaitRunning();
        assertEquals(vxgwService.getFloodingProxy(tzId), null);
        addTunnelZoneMember(tzId, hostId);
        assertEquals(vxgwService.getFloodingProxy(tzId), null);
        vxgwService.stopAsync().awaitTerminated();
    }

    /**
     * This test checks that there is a flooding proxy set in a scenario with
     * 1 tunnel zone, 1 VTEP, 1 host and 1 bridge. Because of the double
     * notification of a bridge creation, the VXGW will clear the flooding proxy
     * once. All entities exist before the starting the VXGW service, but the
     * host is added to the tunnel zone membership after the VXGW service
     * started.
     *
     * The VXGW should set the host as a flooding proxy.
     */
    @Test
    public void testTunnelZoneMembershipNotify(
        @Mocked final VtepBroker vtepBroker) throws Exception {

        UUID hostId = createHost(1);
        hostZkManager.makeAlive(hostId);
        UUID tzId = createTunnelZone(1);
        IPv4Addr vtepId = createVtep(1, tzId);
        final UUID bridgeId = createBridge(1, vtepId, getVtepAddress(1), tzId);

        new Expectations() {{
            vtepDataClientFactory.connect(getVtepAddress(1), VTEP_MGMT_PORT,
                                           (UUID)any);
            result = vtepClient; times = 1;

            VtepBroker vB = new VtepBroker(vtepClient); times = 1;
            vtepClient.onConnected(
                (Callback<VtepDataClient, VtepException>)any);
            result = emptySubscription; times = 1;
            vB.observableUpdates(); result = Observable.empty(); times = 1;
            vtepClient.getTunnelIp(); times = 1;

            vB.ensureLogicalSwitchExists(
                VtepConstants.bridgeIdToLogicalSwitchName(bridgeId), anyInt);
            times = 1;
            vB.renewBindings((org.opendaylight.ovsdb.lib.notation.UUID)any,
                             (Collection<VtepBinding>)any); times = 1;

            vB.advertiseMacs(); times = 1;

            // Set flooding proxy to host 1.
            vB.apply(new MacLocation(
                VtepMAC.UNKNOWN_DST, null,
                VtepConstants.bridgeIdToLogicalSwitchName(bridgeId),
                IPv4Addr.fromBytes(getHostAddress(1).getAddress())));
            times = 1;

            vtepClient.disconnect((UUID)any, true);
            times = 1;
        }};

        vxgwService.startAsync().awaitRunning();
        assertEquals(vxgwService.getFloodingProxy(tzId), null);
        addTunnelZoneMember(tzId, hostId);
        assertEquals(vxgwService.getFloodingProxy(tzId), hostId);
        vxgwService.stopAsync().awaitTerminated();
    }

    /**
     * This test checks that there is a flooding proxy set in a scenario with
     * 1 tunnel zone, 1 VTEP, 1 host and 1 bridge. Because of the double
     * notification of a bridge creation, the VXGW will clear the flooding proxy
     * once. All entities exist before the starting the VXGW service, but the
     * host is set as alive after the VXGW service started.
     *
     * The VXGW service should only set a flooding proxy after the host is
     * alive. When the host is no longer alive, the VXGW service should remove
     * the flooding proxy.
     */
    @Test
    public void testHostAliveNotify(
        @Mocked final VtepBroker vtepBroker) throws Exception {

        UUID hostId = createHost(1);
        UUID tzId = createTunnelZone(1);
        IPv4Addr vtepId = createVtep(1, tzId);
        final UUID bridgeId = createBridge(1, vtepId, getVtepAddress(1), tzId);
        addTunnelZoneMember(tzId, hostId);

        new Expectations() {{
            vtepDataClientFactory.connect(getVtepAddress(1), VTEP_MGMT_PORT,
                                           (UUID)any);
            result = vtepClient; times = 1;

            VtepBroker vB = new VtepBroker(vtepClient); times = 1;
            vtepClient.onConnected(
                (Callback<VtepDataClient, VtepException>)any);
            result = emptySubscription; times = 1;
            vB.observableUpdates(); result = Observable.empty(); times = 1;
            vtepClient.getTunnelIp(); times = 1;

            vB.ensureLogicalSwitchExists(
                VtepConstants.bridgeIdToLogicalSwitchName(bridgeId), anyInt);
            times = 1;
            vB.renewBindings((org.opendaylight.ovsdb.lib.notation.UUID)any,
                             (Collection<VtepBinding>)any); times = 1;

            vB.advertiseMacs(); times = 1;

            // Set flooding proxy to host 1.
            vB.apply(new MacLocation(
                VtepMAC.UNKNOWN_DST, null,
                VtepConstants.bridgeIdToLogicalSwitchName(bridgeId),
                IPv4Addr.fromBytes(getHostAddress(1).getAddress())));
            times = 1;
            // Clear flooding proxy.
            vB.apply(new MacLocation(
                VtepMAC.UNKNOWN_DST, null,
                VtepConstants.bridgeIdToLogicalSwitchName(bridgeId), null));
            times = 1;
            // Set flooding proxy to host 1.
            vB.apply(new MacLocation(
                VtepMAC.UNKNOWN_DST, null,
                VtepConstants.bridgeIdToLogicalSwitchName(bridgeId),
                IPv4Addr.fromBytes(getHostAddress(1).getAddress())));
            times = 1;

            vtepClient.disconnect((UUID)any, true);
            times = 1;
        }};

        vxgwService.startAsync().awaitRunning();
        assertEquals(vxgwService.getFloodingProxy(tzId), null);
        hostZkManager.makeAlive(hostId);
        assertEquals(vxgwService.getFloodingProxy(tzId), hostId);
        hostZkManager.makeNotAlive(hostId);
        assertEquals(vxgwService.getFloodingProxy(tzId), null);
        hostZkManager.makeAlive(hostId);
        assertEquals(vxgwService.getFloodingProxy(tzId), hostId);
        vxgwService.stopAsync().awaitTerminated();
    }

    /**
     * This test checks that there is no flooding proxy set in a scenario with
     * 1 tunnel zone, 1 VTEP, 1 host and 1 bridge. All entities exist before
     * the starting the VXGW service. The host is a tunnel zone member and is
     * alive.
     *
     * The VXGW service should recompute the flooding proxy when the flooding
     * proxy weight has changed for a host. If the flooding proxy has not
     * changed no action is taken. However, if the flooding proxy has a weight
     * of zero (0), the host cannot longer be a flooding proxy.
     */
    @Test
    public void testHostFloodingProxyChangedNotify(
        @Mocked final VtepBroker vtepBroker) throws Exception {

        UUID hostId = createHost(1);
        UUID tzId = createTunnelZone(1);
        IPv4Addr vtepId = createVtep(1, tzId);
        final UUID bridgeId = createBridge(1, vtepId, getVtepAddress(1), tzId);
        hostZkManager.makeAlive(hostId);
        addTunnelZoneMember(tzId, hostId);

        new Expectations() {{
            vtepDataClientFactory.connect(getVtepAddress(1), VTEP_MGMT_PORT,
                                           (UUID)any);
            result = vtepClient; times = 1;

            VtepBroker vB = new VtepBroker(vtepClient); times = 1;
            vtepClient.onConnected(
                (Callback<VtepDataClient, VtepException>)any);
            result = emptySubscription; times = 1;
            vB.observableUpdates(); result = Observable.empty(); times = 1;
            vtepClient.getTunnelIp(); times = 1;

            vB.ensureLogicalSwitchExists(
                VtepConstants.bridgeIdToLogicalSwitchName(bridgeId), anyInt);
            times = 1;
            vB.renewBindings((org.opendaylight.ovsdb.lib.notation.UUID)any,
                             (Collection<VtepBinding>)any); times = 1;

            vB.advertiseMacs(); times = 1;

            // Set flooding proxy to host 1.
            vB.apply(new MacLocation(
                VtepMAC.UNKNOWN_DST, null,
                VtepConstants.bridgeIdToLogicalSwitchName(bridgeId),
                IPv4Addr.fromBytes(getHostAddress(1).getAddress())));
            times = 1;
            // Clear flooding proxy.
            vB.apply(new MacLocation(
                VtepMAC.UNKNOWN_DST, null,
                VtepConstants.bridgeIdToLogicalSwitchName(bridgeId), null));
            times = 1;

            vtepClient.disconnect((UUID)any, true);
            times = 1;
        }};

        vxgwService.startAsync().awaitRunning();
        assertEquals(vxgwService.getFloodingProxy(tzId), hostId);
        midoClient.hostsSetFloodingProxyWeight(hostId, 2);
        assertEquals(vxgwService.getFloodingProxy(tzId), hostId);
        midoClient.hostsSetFloodingProxyWeight(hostId, 0);
        assertEquals(vxgwService.getFloodingProxy(tzId), null);
        vxgwService.stopAsync().awaitTerminated();
    }

    /**
     * This test checks that there is a flooding proxy set in a scenario with
     * 1 tunnel zone, 1 VTEP, 2 hosts and 1 bridge. Because of the double
     * notification of a bridge creation, the VXGW will clear the flooding proxy
     * once. All entities exist before the starting the VXGW service, but the
     * hosts are set as alive after the VXGW service started.
     *
     * Both host have the default flooding proxy weight (1). The first alive
     * host should be selected as flooding proxy, and the flooding proxy should
     * change only when the host goes offline.
     */
    @Test
    public void testTwoHostsAliveNotify(
        @Mocked final VtepBroker vtepBroker) throws Exception {

        UUID hostId1 = createHost(1);
        UUID hostId2 = createHost(2);
        UUID tzId = createTunnelZone(1);
        IPv4Addr vtepId = createVtep(1, tzId);
        final UUID bridgeId = createBridge(1, vtepId, getVtepAddress(1), tzId);
        addTunnelZoneMember(tzId, hostId1);
        addTunnelZoneMember(tzId, hostId2);

        new Expectations() {{
            vtepDataClientFactory.connect(getVtepAddress(1), VTEP_MGMT_PORT,
                                           (UUID)any);
            result = vtepClient; times = 1;

            VtepBroker vB = new VtepBroker(vtepClient); times = 1;
            vtepClient.onConnected(
                (Callback<VtepDataClient, VtepException>)any);
            result = emptySubscription; times = 1;
            vB.observableUpdates(); result = Observable.empty(); times = 1;
            vtepClient.getTunnelIp(); times = 1;

            vB.ensureLogicalSwitchExists(
                VtepConstants.bridgeIdToLogicalSwitchName(bridgeId), anyInt);
            times = 1;
            vB.renewBindings((org.opendaylight.ovsdb.lib.notation.UUID)any,
                             (Collection<VtepBinding>)any); times = 1;

            vB.advertiseMacs(); times = 1;

            // Set flooding proxy to host 1.
            vB.apply(new MacLocation(
                VtepMAC.UNKNOWN_DST, null,
                VtepConstants.bridgeIdToLogicalSwitchName(bridgeId),
                IPv4Addr.fromBytes(getHostAddress(1).getAddress())));
            times = 1;
            // Set flooding proxy to host 2.
            vB.apply(new MacLocation(
                VtepMAC.UNKNOWN_DST, null,
                VtepConstants.bridgeIdToLogicalSwitchName(bridgeId),
                IPv4Addr.fromBytes(getHostAddress(2).getAddress())));
            times = 1;

            vtepClient.disconnect((UUID)any, true);
            times = 1;
        }};

        vxgwService.startAsync().awaitRunning();
        assertEquals(vxgwService.getFloodingProxy(tzId), null);
        hostZkManager.makeAlive(hostId1);
        assertEquals(vxgwService.getFloodingProxy(tzId), hostId1);
        hostZkManager.makeAlive(hostId2);
        assertEquals(vxgwService.getFloodingProxy(tzId), hostId1);
        hostZkManager.makeNotAlive(hostId1);
        assertEquals(vxgwService.getFloodingProxy(tzId), hostId2);
        hostZkManager.makeAlive(hostId1);
        assertEquals(vxgwService.getFloodingProxy(tzId), hostId2);
        vxgwService.stopAsync().awaitTerminated();
    }

    /**
     * This test checks that there is a flooding proxy set in a scenario with
     * 1 tunnel zone, 1 VTEP, 2 hosts and 1 bridge. All entities exist before
     * the starting the VXGW service, but the hosts are set as alive after the
     * VXGW service started.
     *
     * Initially, both host have the default flooding proxy weight (1).
     * The second host increases its flooding proxy weight and it should be
     * selected as a flooding proxy. Then, the second host sets its flooding
     * proxy weight to zero, and the first host should be selected as a flooding
     * proxy.
     */
    @Test
    public void testTwoHostsFloodingProxyWeightNotify(
        @Mocked final VtepBroker vtepBroker) throws Exception {

        UUID hostId1 = createHost(1);
        UUID hostId2 = createHost(2);
        UUID tzId = createTunnelZone(1);
        IPv4Addr vtepId = createVtep(1, tzId);
        final UUID bridgeId = createBridge(1, vtepId, getVtepAddress(1), tzId);
        addTunnelZoneMember(tzId, hostId1);
        addTunnelZoneMember(tzId, hostId2);

        new Expectations() {{
            vtepDataClientFactory.connect(getVtepAddress(1), VTEP_MGMT_PORT,
                                           (UUID)any);
            result = vtepClient; times = 1;

            VtepBroker vB = new VtepBroker(vtepClient); times = 1;
            vtepClient.onConnected(
                (Callback<VtepDataClient, VtepException>)any);
            result = emptySubscription; times = 1;
            vB.observableUpdates(); result = Observable.empty(); times = 1;
            vtepClient.getTunnelIp(); times = 1;

            vB.ensureLogicalSwitchExists(
                VtepConstants.bridgeIdToLogicalSwitchName(bridgeId), anyInt);
            times = 1;
            vB.renewBindings((org.opendaylight.ovsdb.lib.notation.UUID)any,
                             (Collection<VtepBinding>)any); times = 1;

            vB.advertiseMacs(); times = 1;

            // Set flooding proxy to host 1.
            vB.apply(new MacLocation(
                VtepMAC.UNKNOWN_DST, null,
                VtepConstants.bridgeIdToLogicalSwitchName(bridgeId),
                IPv4Addr.fromBytes(getHostAddress(1).getAddress())));
            times = 1;
            // Set flooding proxy to host 2.
            vB.apply(new MacLocation(
                VtepMAC.UNKNOWN_DST, null,
                VtepConstants.bridgeIdToLogicalSwitchName(bridgeId),
                IPv4Addr.fromBytes(getHostAddress(2).getAddress())));
            times = 1;
            // Set flooding proxy to host 1.
            vB.apply(new MacLocation(
                VtepMAC.UNKNOWN_DST, null,
                VtepConstants.bridgeIdToLogicalSwitchName(bridgeId),
                IPv4Addr.fromBytes(getHostAddress(1).getAddress())));
            times = 1;

            vtepClient.disconnect((UUID)any, true);
            times = 1;
        }};

        vxgwService.startAsync().awaitRunning();
        assertEquals(vxgwService.getFloodingProxy(tzId), null);
        hostZkManager.makeAlive(hostId1);
        assertEquals(vxgwService.getFloodingProxy(tzId), hostId1);
        hostZkManager.makeAlive(hostId2);
        assertEquals(vxgwService.getFloodingProxy(tzId), hostId1);
        midoClient.hostsSetFloodingProxyWeight(hostId2, 2);
        assertEquals(vxgwService.getFloodingProxy(tzId), hostId2);
        midoClient.hostsSetFloodingProxyWeight(hostId2, 0);
        assertEquals(vxgwService.getFloodingProxy(tzId), hostId1);
        vxgwService.stopAsync().awaitTerminated();
    }

    /**
     * This test checks that there is a flooding proxy set in a scenario with
     * 1 tunnel zone, 1 VTEP, 1 hosts and 1 bridge. The host is created, added
     * to the tunnel zone and set as alive after the VXGW service started.
     *
     * The host should be set as a flooding proxy after set as live and a
     * member of the tunnel zone.
     */
    @Test
    public void testHostLifecycle(@Mocked final VtepBroker vtepBroker)
        throws Exception {

        UUID tzId = createTunnelZone(1);
        IPv4Addr vtepId = createVtep(1, tzId);
        final UUID bridgeId = createBridge(1, vtepId, getVtepAddress(1), tzId);

        new Expectations() {{
            vtepDataClientFactory.connect(getVtepAddress(1), VTEP_MGMT_PORT,
                                           (UUID)any);
            result = vtepClient; times = 1;

            VtepBroker vB = new VtepBroker(vtepClient); times = 1;
            vtepClient.onConnected(
                (Callback<VtepDataClient, VtepException>)any);
            result = emptySubscription; times = 1;
            vB.observableUpdates(); result = Observable.empty(); times = 1;
            vtepClient.getTunnelIp(); times = 1;

            vB.ensureLogicalSwitchExists(
                VtepConstants.bridgeIdToLogicalSwitchName(bridgeId), anyInt);
            times = 1;
            vB.renewBindings((org.opendaylight.ovsdb.lib.notation.UUID)any,
                             (Collection<VtepBinding>)any); times = 1;

            vB.advertiseMacs(); times = 1;

            // Set flooding proxy to host 1.
            vB.apply(new MacLocation(
                VtepMAC.UNKNOWN_DST, null,
                VtepConstants.bridgeIdToLogicalSwitchName(bridgeId),
                IPv4Addr.fromBytes(getHostAddress(1).getAddress())));
            times = 1;
            // Clear flooding proxy.
            vB.apply(new MacLocation(
                VtepMAC.UNKNOWN_DST, null,
                VtepConstants.bridgeIdToLogicalSwitchName(bridgeId), null));
            times = 1;

            vtepClient.disconnect((UUID)any, true);
            times = 1;
        }};

        vxgwService.startAsync().awaitRunning();
        assertEquals(vxgwService.getFloodingProxy(tzId), null);
        UUID hostId = createHost(1);
        assertEquals(vxgwService.getFloodingProxy(tzId), null);
        hostZkManager.makeAlive(hostId);
        assertEquals(vxgwService.getFloodingProxy(tzId), null);
        addTunnelZoneMember(tzId, hostId);
        assertEquals(vxgwService.getFloodingProxy(tzId), hostId);
        midoClient.tunnelZonesDeleteMembership(tzId, hostId);
        assertEquals(vxgwService.getFloodingProxy(tzId), null);
        hostZkManager.makeNotAlive(hostId);
        assertEquals(vxgwService.getFloodingProxy(tzId), null);
        midoClient.hostsDelete(hostId);
        assertEquals(vxgwService.getFloodingProxy(tzId), null);
        vxgwService.stopAsync().awaitTerminated();
    }

    /**
     * This test checks that there is a flooding proxy set in a scenario with
     * 1 tunnel zone, 1 VTEP, 1 hosts and 1 bridge. The bridge is created after
     * the VXGW service started.
     *
     * The tunnel zone state should always have the host set as the current
     * flooding proxy. However, the VTEP is configured with the flooding proxy
     * only when the bridge is created, and the configuration is removed
     * when the bridge is deleted.
     */
    @Test
    public void testBridgeLifecycle(@Mocked final VtepBroker vtepBroker)
        throws Exception {

        UUID hostId = createHost(1);
        UUID tzId = createTunnelZone(1);
        final UUID bridgeId = UUID.randomUUID();
        IPv4Addr vtepId = createVtep(1, tzId);
        hostZkManager.makeAlive(hostId);
        addTunnelZoneMember(tzId, hostId);

        new Expectations() {{
            vtepDataClientFactory.connect(getVtepAddress(1), VTEP_MGMT_PORT,
                                           (UUID)any);
            result = vtepClient; times = 1;

            VtepBroker vB = new VtepBroker(vtepClient); times = 1;
            vtepClient.onConnected(
                (Callback<VtepDataClient, VtepException>)any);
            result = emptySubscription; times = 1;
            vB.observableUpdates(); result = Observable.empty(); times = 1;
            vtepClient.getTunnelIp(); times = 1;

            vB.ensureLogicalSwitchExists(anyString, anyInt); times = 1;
            vB.renewBindings((org.opendaylight.ovsdb.lib.notation.UUID)any,
                             (Collection<VtepBinding>)any); times = 1;

            vB.advertiseMacs(); times = 1;

            // Set flooding proxy to host 1.
            vB.apply(new MacLocation(
                VtepMAC.UNKNOWN_DST, null,
                VtepConstants.bridgeIdToLogicalSwitchName(bridgeId),
                IPv4Addr.fromBytes(getHostAddress(1).getAddress())));
            times = 1;
            // Clear flooding proxy.
            vB.apply(new MacLocation(
                VtepMAC.UNKNOWN_DST, null,
                VtepConstants.bridgeIdToLogicalSwitchName(bridgeId), null));
            times = 1;

            vtepClient.disconnect((UUID)any, true);
            times = 1;
        }};

        vxgwService.startAsync().awaitRunning();
        assertEquals(vxgwService.getFloodingProxy(tzId), hostId);
        createBridge(bridgeId, 1);
        assertEquals(vxgwService.getFloodingProxy(tzId), hostId);
        midoClient.bridgeCreateVxLanPort(
            bridgeId, vtepId, VTEP_MGMT_PORT, VTEP_VNI, getVtepAddress(1),
            tzId);
        assertEquals(vxgwService.getFloodingProxy(tzId), hostId);
        midoClient.bridgeDeleteVxLanPort(bridgeId);
        assertEquals(vxgwService.getFloodingProxy(tzId), hostId);
        midoClient.bridgesDelete(bridgeId);
        assertEquals(vxgwService.getFloodingProxy(tzId), hostId);
        vxgwService.stopAsync().awaitTerminated();
    }

    /**
     * This test checks that there is a flooding proxy set in a scenario with
     * 1 tunnel zone, 1 VTEP, 1 hosts and 1 bridge. The tunnel zone and VTEP
     * are created aftre the VXGW service has started.
     *
     * The tunnel zone state should always have the host set as the current
     * flooding proxy. However, the VTEP is configured with the flooding proxy
     * only when the bridge creates a VXLAN port on the VTEP.
     */
    @Test
    public void testVtepLifecycle(@Mocked final VtepBroker vtepBroker)
        throws Exception {

        UUID hostId = createHost(1);
        UUID tzId = createTunnelZone(1);
        final UUID bridgeId = createBridge(1);
        hostZkManager.makeAlive(hostId);
        addTunnelZoneMember(tzId, hostId);

        new Expectations() {{
            vtepDataClientFactory.connect(getVtepAddress(1), VTEP_MGMT_PORT,
                                           (UUID)any);
            result = vtepClient; times = 1;

            VtepBroker vB = new VtepBroker(vtepClient); times = 1;
            vtepClient.onConnected(
                (Callback<VtepDataClient, VtepException>)any);
            result = emptySubscription; times = 1;
            vB.observableUpdates(); result = Observable.empty(); times = 1;
            vtepClient.getTunnelIp(); times = 1;

            vB.ensureLogicalSwitchExists(
                VtepConstants.bridgeIdToLogicalSwitchName(bridgeId), anyInt);
            times = 1;
            vB.renewBindings((org.opendaylight.ovsdb.lib.notation.UUID)any,
                             (Collection<VtepBinding>)any); times = 1;

            vB.advertiseMacs(); times = 1;

            // Set flooding proxy to host 1.
            vB.apply(new MacLocation(
                VtepMAC.UNKNOWN_DST, null,
                VtepConstants.bridgeIdToLogicalSwitchName(bridgeId),
                IPv4Addr.fromBytes(getHostAddress(1).getAddress())));
            times = 1;
            // Clear flooding proxy.
            vB.apply(new MacLocation(
                VtepMAC.UNKNOWN_DST, null,
                VtepConstants.bridgeIdToLogicalSwitchName(bridgeId), null));
            times = 1;

            vtepClient.disconnect((UUID)any, true);
            times = 1;
        }};

        vxgwService.startAsync().awaitRunning();
        assertEquals(vxgwService.getFloodingProxy(tzId), hostId);
        IPv4Addr vtepId = createVtep(1, tzId);
        assertEquals(vxgwService.getFloodingProxy(tzId), hostId);
        midoClient.bridgeCreateVxLanPort(
            bridgeId, vtepId, VTEP_MGMT_PORT, VTEP_VNI, getVtepAddress(1),
            tzId);
        assertEquals(vxgwService.getFloodingProxy(tzId), hostId);
        midoClient.bridgeDeleteVxLanPort(bridgeId);
        assertEquals(vxgwService.getFloodingProxy(tzId), hostId);
        midoClient.vtepDelete(vtepId);
        assertEquals(vxgwService.getFloodingProxy(tzId), hostId);
        vxgwService.stopAsync().awaitTerminated();
    }

    /**
     * This test checks that there is a flooding proxy set in a scenario with
     * 1 tunnel zone, 1 VTEP, 1 hosts and 1 bridge. The tunnel zone and VTEP
     * are created aftre the VXGW service has started.
     *
     * The tunnel zone state should always have the host set as the current
     * flooding proxy. However, the VTEP is configured with the flooding proxy
     * only when the bridge is created, and the configuration is removed
     * when the bridge is deleted.
     */
    @Test
    public void testTunnelZoneVtepLifecycle(@Mocked final VtepBroker vtepBroker)
        throws Exception {

        UUID hostId = createHost(1);
        final UUID bridgeId = createBridge(1);
        hostZkManager.makeAlive(hostId);

        new Expectations() {{
            vtepDataClientFactory.connect(getVtepAddress(1), VTEP_MGMT_PORT,
                                           (UUID)any);
            result = vtepClient; times = 1;

            VtepBroker vB = new VtepBroker(vtepClient); times = 1;
            vtepClient.onConnected(
                (Callback<VtepDataClient, VtepException>)any);
            result = emptySubscription; times = 1;
            vB.observableUpdates(); result = Observable.empty(); times = 1;
            vtepClient.getTunnelIp(); times = 1;

            vB.ensureLogicalSwitchExists(
                VtepConstants.bridgeIdToLogicalSwitchName(bridgeId), anyInt);
            times = 1;
            vB.renewBindings((org.opendaylight.ovsdb.lib.notation.UUID)any,
                             (Collection<VtepBinding>)any); times = 1;

            vB.advertiseMacs(); times = 1;

            // Set flooding proxy to host 1.
            vB.apply(new MacLocation(
                VtepMAC.UNKNOWN_DST, null,
                VtepConstants.bridgeIdToLogicalSwitchName(bridgeId),
                IPv4Addr.fromBytes(getHostAddress(1).getAddress())));
            times = 1;
            // Clear flooding proxy.
            vB.apply(new MacLocation(
                VtepMAC.UNKNOWN_DST, null,
                VtepConstants.bridgeIdToLogicalSwitchName(bridgeId), null));
            times = 1;

            vtepClient.disconnect((UUID)any, true);
            times = 1;
        }};

        vxgwService.startAsync().awaitRunning();
        UUID tzId = createTunnelZone(1);
        assertEquals(vxgwService.getFloodingProxy(tzId), null);
        addTunnelZoneMember(tzId, hostId);
        assertEquals(vxgwService.getFloodingProxy(tzId), hostId);
        IPv4Addr vtepId = createVtep(1, tzId);
        assertEquals(vxgwService.getFloodingProxy(tzId), hostId);
        midoClient.bridgeCreateVxLanPort(
            bridgeId, vtepId, VTEP_MGMT_PORT, VTEP_VNI, getVtepAddress(1),
            tzId);
        assertEquals(vxgwService.getFloodingProxy(tzId), hostId);
        midoClient.bridgeDeleteVxLanPort(bridgeId);
        assertEquals(vxgwService.getFloodingProxy(tzId), hostId);
        midoClient.vtepDelete(vtepId);
        assertEquals(vxgwService.getFloodingProxy(tzId), hostId);
        midoClient.tunnelZonesDeleteMembership(tzId, hostId);
        assertEquals(vxgwService.getFloodingProxy(tzId), null);
        midoClient.tunnelZonesDelete(tzId);
        vxgwService.stopAsync().awaitTerminated();
    }

    /**
     * This test checks that the flooding proxy is updated correctly when
     * a bridge changes the tunnel zone, in a scenario with 2 tunnel zones,
     * 2 VTEPs, 2 host and 2 bridges.
     *
     * The flooding proxy is a per-tunnel-zone property, but a VTEP must
     * configure a flooding proxy for each logical switch.
     */
    @Test
    public void testBridgeChangeVxLanPort(@Mocked final VtepBroker vtepBroker)
        throws Exception {

        UUID hostId1 = createHost(1);
        UUID hostId2 = createHost(2);
        UUID tzId1 = createTunnelZone(1);
        UUID tzId2 = createTunnelZone(2);
        IPv4Addr vtepId1 = createVtep(1, tzId1);
        IPv4Addr vtepId2 = createVtep(2, tzId2);
        final UUID bridgeId = createBridge(1);

        hostZkManager.makeAlive(hostId1);
        hostZkManager.makeAlive(hostId2);
        addTunnelZoneMember(tzId1, hostId1);
        addTunnelZoneMember(tzId2, hostId2);

        new Expectations() {{
            vtepDataClientFactory.connect((IPv4Addr)any, VTEP_MGMT_PORT,
                                           (UUID)any);
            result = vtepClient; times = 1;

            VtepBroker vB1 = new VtepBroker(vtepClient); times = 1;
            vtepClient.onConnected(
                (Callback<VtepDataClient, VtepException>)any);
            result = emptySubscription; times = 1;
            vB1.observableUpdates(); result = Observable.empty(); times = 1;
            vtepClient.getTunnelIp(); times = 1;

            vtepDataClientFactory.connect((IPv4Addr)any, VTEP_MGMT_PORT,
                                           (UUID)any);
            result = vtepClient; times = 1;

            VtepBroker vB2 = new VtepBroker(vtepClient); times = 1;
            vtepClient.onConnected(
                (Callback<VtepDataClient, VtepException>)any);
            result = emptySubscription; times = 1;
            vB2.observableUpdates(); result = Observable.empty(); times = 1;
            vtepClient.getTunnelIp(); times = 1;

            vB1.ensureLogicalSwitchExists(
                VtepConstants.bridgeIdToLogicalSwitchName(bridgeId), anyInt);
            times = 1;
            vB1.renewBindings((org.opendaylight.ovsdb.lib.notation.UUID)any,
                             (Collection<VtepBinding>)any); times = 1;

            vB1.advertiseMacs(); times = 1;

            // On bridge updated (VXLAN port): assign bridge to tunnel zone 1
            vB1.apply(new MacLocation(
                VtepMAC.UNKNOWN_DST, null,
                VtepConstants.bridgeIdToLogicalSwitchName(bridgeId),
                IPv4Addr.fromBytes(getHostAddress(1).getAddress())));
            times = 1;
            // On bridge updated (VXLAN port): cleared bridge from tunnel zone
            vB1.apply(new MacLocation(
                VtepMAC.UNKNOWN_DST, null,
                VtepConstants.bridgeIdToLogicalSwitchName(bridgeId), null));
            times = 1;

            vB2.ensureLogicalSwitchExists(
                VtepConstants.bridgeIdToLogicalSwitchName(bridgeId), anyInt);
            times = 1;
            vB2.renewBindings((org.opendaylight.ovsdb.lib.notation.UUID)any,
                              (Collection<VtepBinding>)any); times = 1;

            vB2.advertiseMacs(); times = 1;

            // On bridge updated (VXLAN port): assign bridge to tunnel zone 2
            vB2.apply(new MacLocation(
                VtepMAC.UNKNOWN_DST, null,
                VtepConstants.bridgeIdToLogicalSwitchName(bridgeId),
                IPv4Addr.fromBytes(getHostAddress(2).getAddress())));
            times = 1;
            // On bridge updated (VXLAN port): cleared bridge from tunnel zone
            vB2.apply(new MacLocation(
                VtepMAC.UNKNOWN_DST, null,
                VtepConstants.bridgeIdToLogicalSwitchName(bridgeId), null));
            times = 1;

            vtepClient.disconnect((UUID)any, true);
            times = 2;
        }};


        vxgwService.startAsync().awaitRunning();
        assertEquals(vxgwService.getFloodingProxy(tzId1), hostId1);
        assertEquals(vxgwService.getFloodingProxy(tzId2), hostId2);
        midoClient.bridgeCreateVxLanPort(
            bridgeId, vtepId1, VTEP_MGMT_PORT, VTEP_VNI, getVtepAddress(1),
            tzId1);
        midoClient.bridgeDeleteVxLanPort(bridgeId);
        midoClient.bridgeCreateVxLanPort(
            bridgeId, vtepId2, VTEP_MGMT_PORT, VTEP_VNI, getVtepAddress(2),
            tzId2);
        midoClient.bridgeDeleteVxLanPort(bridgeId);
        vxgwService.stopAsync().awaitTerminated();
    }

    /**
     * This test checks the flooding proxy in a scenario with four hosts
     * than initially have different flooding proxy weights and change their
     * alive status.
     *
     * The test is conducted in a scenario with 1 tunnel zone, 1 VTEP and 1
     * bridge.
     *
     * The flooding proxy should change to the alive host with the maximum
     * flooding proxy weight.
     */
    @Test
    public void testMultipleHostsWithHostAlive(
        @Mocked final VtepBroker vtepBroker) throws Exception {
        UUID hostId1 = createHost(1);
        UUID hostId2 = createHost(2);
        UUID hostId3 = createHost(3);
        UUID hostId4 = createHost(4);
        UUID tzId = createTunnelZone(1);
        IPv4Addr vtepId = createVtep(1, tzId);
        final UUID bridgeId = createBridge(1, vtepId, getVtepAddress(1), tzId);

        hostZkManager.makeAlive(hostId1);
        hostZkManager.makeAlive(hostId2);
        hostZkManager.makeAlive(hostId3);
        hostZkManager.makeAlive(hostId4);
        addTunnelZoneMember(tzId, hostId1);
        addTunnelZoneMember(tzId, hostId2);
        addTunnelZoneMember(tzId, hostId3);
        addTunnelZoneMember(tzId, hostId4);
        midoClient.hostsSetFloodingProxyWeight(hostId1, 10);
        midoClient.hostsSetFloodingProxyWeight(hostId2, 20);
        midoClient.hostsSetFloodingProxyWeight(hostId3, 30);
        midoClient.hostsSetFloodingProxyWeight(hostId4, 40);

        new Expectations() {{
            vtepDataClientFactory.connect(getVtepAddress(1), VTEP_MGMT_PORT,
                                           (UUID)any);
            result = vtepClient; times = 1;

            VtepBroker vB = new VtepBroker(vtepClient); times = 1;
            vtepClient.onConnected(
                (Callback<VtepDataClient, VtepException>)any);
            result = emptySubscription; times = 1;
            vB.observableUpdates(); result = Observable.empty(); times = 1;
            vtepClient.getTunnelIp(); times = 1;

            vB.ensureLogicalSwitchExists(
                VtepConstants.bridgeIdToLogicalSwitchName(bridgeId), anyInt);
            times = 1;
            vB.renewBindings((org.opendaylight.ovsdb.lib.notation.UUID)any,
                              (Collection<VtepBinding>)any); times = 1;

            vB.advertiseMacs(); times = 1;

            // Set flooding proxy to host 4.
            vB.apply(new MacLocation(
                VtepMAC.UNKNOWN_DST, null,
                VtepConstants.bridgeIdToLogicalSwitchName(bridgeId),
                IPv4Addr.fromBytes(getHostAddress(4).getAddress())));
            times = 1;
            // Set flooding proxy to host 3.
            vB.apply(new MacLocation(
                VtepMAC.UNKNOWN_DST, null,
                VtepConstants.bridgeIdToLogicalSwitchName(bridgeId),
                IPv4Addr.fromBytes(getHostAddress(3).getAddress())));
            times = 1;
            // Set flooding proxy to host 4.
            vB.apply(new MacLocation(
                VtepMAC.UNKNOWN_DST, null,
                VtepConstants.bridgeIdToLogicalSwitchName(bridgeId),
                IPv4Addr.fromBytes(getHostAddress(4).getAddress())));
            times = 1;
            // Set flooding proxy to host 1.
            vB.apply(new MacLocation(
                VtepMAC.UNKNOWN_DST, null,
                VtepConstants.bridgeIdToLogicalSwitchName(bridgeId),
                IPv4Addr.fromBytes(getHostAddress(1).getAddress())));
            times = 1;
            // Set flooding proxy to host 3.
            vB.apply(new MacLocation(
                VtepMAC.UNKNOWN_DST, null,
                VtepConstants.bridgeIdToLogicalSwitchName(bridgeId),
                IPv4Addr.fromBytes(getHostAddress(3).getAddress())));
            times = 1;

            vtepClient.disconnect((UUID)any, true);
            times = 1;
        }};

        vxgwService.startAsync().awaitRunning();
        assertEquals(vxgwService.getFloodingProxy(tzId), hostId4);
        hostZkManager.makeNotAlive(hostId4);
        assertEquals(vxgwService.getFloodingProxy(tzId), hostId3);
        hostZkManager.makeAlive(hostId4);
        assertEquals(vxgwService.getFloodingProxy(tzId), hostId4);
        hostZkManager.makeNotAlive(hostId2);
        assertEquals(vxgwService.getFloodingProxy(tzId), hostId4);
        hostZkManager.makeNotAlive(hostId3);
        assertEquals(vxgwService.getFloodingProxy(tzId), hostId4);
        hostZkManager.makeNotAlive(hostId4);
        assertEquals(vxgwService.getFloodingProxy(tzId), hostId1);
        hostZkManager.makeAlive(hostId3);
        assertEquals(vxgwService.getFloodingProxy(tzId), hostId3);
        vxgwService.stopAsync().awaitTerminated();
    }

    /**
     * This test checks the flooding proxy in a scenario with four hosts
     * that initially have different flooding proxy weights and change the
     * tunnel zone membership.
     *
     * The test is conducted in a scenario with 1 tunnel zone, 1 VTEP and 1
     * bridge.
     *
     * The flooding proxy should change to the alive host with the maximum
     * flooding proxy weight.
     */
    @Test
    public void testMultipleHostsWithTunnelZoneMembership(
        @Mocked final VtepBroker vtepBroker) throws Exception {
        UUID hostId1 = createHost(1);
        UUID hostId2 = createHost(2);
        UUID hostId3 = createHost(3);
        UUID hostId4 = createHost(4);
        UUID tzId = createTunnelZone(1);
        IPv4Addr vtepId = createVtep(1, tzId);
        final UUID bridgeId = createBridge(1, vtepId, getVtepAddress(1), tzId);

        hostZkManager.makeAlive(hostId1);
        hostZkManager.makeAlive(hostId2);
        hostZkManager.makeAlive(hostId3);
        hostZkManager.makeAlive(hostId4);
        addTunnelZoneMember(tzId, hostId1);
        midoClient.hostsSetFloodingProxyWeight(hostId1, 10);
        midoClient.hostsSetFloodingProxyWeight(hostId2, 20);
        midoClient.hostsSetFloodingProxyWeight(hostId3, 30);
        midoClient.hostsSetFloodingProxyWeight(hostId4, 40);

        new Expectations() {{
            vtepDataClientFactory.connect(getVtepAddress(1), VTEP_MGMT_PORT,
                                           (UUID)any);
            result = vtepClient; times = 1;

            VtepBroker vB = new VtepBroker(vtepClient); times = 1;
            vtepClient.onConnected(
                (Callback<VtepDataClient, VtepException>)any);
            result = emptySubscription; times = 1;
            vB.observableUpdates(); result = Observable.empty(); times = 1;
            vtepClient.getTunnelIp(); times = 1;

            vB.ensureLogicalSwitchExists(
                VtepConstants.bridgeIdToLogicalSwitchName(bridgeId), anyInt);
            times = 1;
            vB.renewBindings((org.opendaylight.ovsdb.lib.notation.UUID)any,
                             (Collection<VtepBinding>)any); times = 1;

            vB.advertiseMacs(); times = 1;

            // Set flooding proxy to host 1.
            vB.apply(new MacLocation(
                VtepMAC.UNKNOWN_DST, null,
                VtepConstants.bridgeIdToLogicalSwitchName(bridgeId),
                IPv4Addr.fromBytes(getHostAddress(1).getAddress())));
            times = 1;
            // Set flooding proxy to host 2.
            vB.apply(new MacLocation(
                VtepMAC.UNKNOWN_DST, null,
                VtepConstants.bridgeIdToLogicalSwitchName(bridgeId),
                IPv4Addr.fromBytes(getHostAddress(2).getAddress())));
            times = 1;
            // Set flooding proxy to host 3.
            vB.apply(new MacLocation(
                VtepMAC.UNKNOWN_DST, null,
                VtepConstants.bridgeIdToLogicalSwitchName(bridgeId),
                IPv4Addr.fromBytes(getHostAddress(3).getAddress())));
            times = 1;
            // Set flooding proxy to host 4.
            vB.apply(new MacLocation(
                VtepMAC.UNKNOWN_DST, null,
                VtepConstants.bridgeIdToLogicalSwitchName(bridgeId),
                IPv4Addr.fromBytes(getHostAddress(4).getAddress())));
            times = 1;
            // Set flooding proxy to host 1.
            vB.apply(new MacLocation(
                VtepMAC.UNKNOWN_DST, null,
                VtepConstants.bridgeIdToLogicalSwitchName(bridgeId),
                IPv4Addr.fromBytes(getHostAddress(1).getAddress())));
            times = 1;

            vtepClient.disconnect((UUID)any, true);
            times = 1;
        }};

        vxgwService.startAsync().awaitRunning();
        assertEquals(vxgwService.getFloodingProxy(tzId), hostId1);
        addTunnelZoneMember(tzId, hostId2);
        assertEquals(vxgwService.getFloodingProxy(tzId), hostId2);
        addTunnelZoneMember(tzId, hostId3);
        assertEquals(vxgwService.getFloodingProxy(tzId), hostId3);
        addTunnelZoneMember(tzId, hostId4);
        assertEquals(vxgwService.getFloodingProxy(tzId), hostId4);
        midoClient.tunnelZonesDeleteMembership(tzId, hostId3);
        assertEquals(vxgwService.getFloodingProxy(tzId), hostId4);
        midoClient.tunnelZonesDeleteMembership(tzId, hostId2);
        assertEquals(vxgwService.getFloodingProxy(tzId), hostId4);
        midoClient.tunnelZonesDeleteMembership(tzId, hostId4);
        assertEquals(vxgwService.getFloodingProxy(tzId), hostId1);
        vxgwService.stopAsync().awaitTerminated();
    }

    public UUID createTunnelZone(int index)
        throws StateAccessException, SerializationException {

        // Create the tunnel zone.
        TunnelZone tzone = new TunnelZone();
        tzone.setName("TunnelZone-" + index);
        tzone.setType(TunnelZone.Type.vxlan);
        return midoClient.tunnelZonesCreate(tzone);
    }

    public void addTunnelZoneMember(UUID zoneId, UUID hostId)
        throws StateAccessException, SerializationException {
        Host host = midoClient.hostsGet(hostId);

        TunnelZone.HostConfig hostConfig = new TunnelZone.HostConfig(hostId);
        hostConfig.setIp(IPv4Addr.apply(
            host.getAddresses()[0].getAddress()));

        midoClient.tunnelZonesAddMembership(zoneId, hostConfig);
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

    public UUID createHost(int index)
        throws StateAccessException, SerializationException,
               UnknownHostException {

        Host host = new Host();
        host.setName("Host-" + index);
        host.setAddresses(new InetAddress[] { getHostAddress(index) });
        return midoClient.hostsCreate(UUID.randomUUID(), host);
    }

    public UUID createBridge(int index)
        throws SerializationException, StateAccessException {
        Bridge bridge = new Bridge();
        bridge.setName("Bridge-" + index);
        return midoClient.bridgesCreate(bridge);
    }

    public UUID createBridge(UUID id, int index)
        throws SerializationException, StateAccessException {
        Bridge bridge = new Bridge(id);
        bridge.setName("Bridge-" + index);
        return midoClient.bridgesCreate(bridge);
    }

    public UUID createBridge(
        int index, IPv4Addr mgmtIp, IPv4Addr tunnelIp, UUID tzId)
        throws StateAccessException, SerializationException {
        UUID bridgeId = createBridge(index);
        midoClient.bridgeCreateVxLanPort(
            bridgeId, mgmtIp, VTEP_MGMT_PORT, VTEP_VNI, tunnelIp, tzId);
        return bridgeId;
    }

    public static IPv4Addr getVtepAddress(int value) {
        return IPv4Addr.apply(new byte[] {
            VTEP_MGMT_IP[0],
            VTEP_MGMT_IP[1],
            (byte)((value >> 8) & 0xFF),
            (byte)(value & 0xFF)
        });
    }

    public static InetAddress getHostAddress(int value)
        throws UnknownHostException {
        return InetAddress.getByAddress(new byte[] {
            HOST_IP[0],
            HOST_IP[1],
            (byte)((value >> 8) & 0xFF),
            (byte)(value & 0xFF)
        });
    }
}
