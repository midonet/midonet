/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.brain.services.vxgw;

import java.util.UUID;

import com.google.inject.Guice;
import com.google.inject.Injector;
import mockit.Expectations;
import mockit.Mocked;
import org.apache.commons.configuration.HierarchicalConfiguration;
import org.junit.Before;
import org.junit.Test;

import org.midonet.brain.BrainTestUtils;
import org.midonet.brain.southbound.midonet.MidoVxLanPeer;
import org.midonet.brain.southbound.vtep.VtepBroker;
import org.midonet.brain.southbound.vtep.VtepDataClientProvider;
import org.midonet.brain.southbound.vtep.VtepDataClient;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.data.host.Host;
import org.midonet.cluster.data.Bridge;
import org.midonet.cluster.data.TunnelZone;
import org.midonet.cluster.data.VTEP;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.ZookeeperConnectionWatcher;
import org.midonet.packets.IPv4Addr;

public class VxLanGatewayServiceTest {

    /*
     * Vtep parameters
     */
    private static final IPv4Addr vtepMgmtIp = IPv4Addr.apply("192.169.0.20");
    private static final int vtepMgmntPort = 6632;
    private static final int bridgePortVNI = 42;

    /*
     * Host parameters
     */
    private IPv4Addr tunnelZoneHostIp = IPv4Addr.apply("192.169.0.100");

    /*
     * Mocked components for the vtep data client
     */

    @Mocked
    private VtepDataClient vtepDataClient;
    @Mocked
    private VtepDataClientProvider vtepClientProvider;

    private VTEP vtep = null;

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

    private UUID makeBoundBridge(String name) throws SerializationException,
                                                StateAccessException {
        UUID bridgeId = makeUnboundBridge(name);
        dataClient.bridgeCreateVxLanPort(bridgeId, vtepMgmtIp, vtepMgmntPort,
                                         bridgePortVNI);
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

        host = new Host();
        host.setName("TestHost");
        hostId = dataClient.hostsCreate(UUID.randomUUID(), host);

        TunnelZone tz = new TunnelZone();
        tz.setName("TestTz");
        UUID tzId = dataClient.tunnelZonesCreate(tz);
        TunnelZone.HostConfig zoneHost = new TunnelZone.HostConfig(hostId);
        zoneHost.setIp(tunnelZoneHostIp);
        dataClient.tunnelZonesAddMembership(tzId, zoneHost);

        vtep = new VTEP();
        vtep.setId(vtepMgmtIp);
        vtep.setMgmtPort(vtepMgmntPort);
        vtep.setTunnelZone(tzId);
        dataClient.vtepCreate(vtep);
    }

    /**
     * Check the suscription life cycle inside the gateway service.
     */
    @Test
    public void testBasicLifecycle(@Mocked final VxLanGwBroker vxGwBroker,
                                   @Mocked final VtepBroker vtepBroker,
                                   @Mocked final MidoVxLanPeer midoPeer) {

        new Expectations() {{
            // Per vtep
            vtepClientProvider.get(); result = vtepDataClient; times = 1;
            VtepBroker vB = new VtepBroker(vtepDataClient); times = 1;
            MidoVxLanPeer mP = new MidoVxLanPeer(dataClient); times = 1;
            new VxLanGwBroker(vB, mP); result = vxGwBroker; times = 1;
            vxGwBroker.start(); times = 1;
            vtepDataClient.connect(vtepMgmtIp, vtepMgmntPort); times = 1;

            // Shutdown
            vxGwBroker.shutdown(); times = 1;
            vtepDataClient.disconnect(); times = 1;
        }};

        VxLanGatewayService gwsrv = new VxLanGatewayService(dataClient,
                                                            vtepClientProvider,
                                                            zkConnWatcher);
        gwsrv.startAndWait();
        gwsrv.stopAndWait();
    }

    /**
     * Test the addition of a bridge
     */
    @Test
    public void testBridgeAddition(@Mocked final VxLanGwBroker vxGwBroker,
                                   @Mocked final VtepBroker vtepBroker,
                                   @Mocked final MidoVxLanPeer midoPeer)
        throws Exception {

        new Expectations() {{
            // Per vtep
            vtepClientProvider.get(); result = vtepDataClient; times = 1;
            VtepBroker vB = new VtepBroker(vtepDataClient); times = 1;
            MidoVxLanPeer mP = new MidoVxLanPeer(dataClient); times = 1;
            new VxLanGwBroker(vB, mP); result = vxGwBroker; times = 1;
            vxGwBroker.start(); times = 1;
            vtepDataClient.connect(vtepMgmtIp, vtepMgmntPort); times = 1;

            // Bridge addition
            mP.watch((UUID)withNotNull()); result = true; times = 1;
            vB.advertiseMacs(); times = 1;

            // Shutdown
            vxGwBroker.shutdown(); times = 1;
            vtepDataClient.disconnect(); times = 1;
        }};

        VxLanGatewayService gwsrv = new VxLanGatewayService(dataClient,
                                                            vtepClientProvider,
                                                            zkConnWatcher);
        gwsrv.startAndWait();

        // add a new bridge with a binding
        UUID bridgeId = makeBoundBridge("bridge1");

        gwsrv.stopAndWait();
    }

    /**
     * Test the update of a bridge
     */
    @Test
    public void testBridgeUpdate(@Mocked final VxLanGwBroker vxGwBroker,
                                 @Mocked final VtepBroker vtepBroker,
                                 @Mocked MidoVxLanPeer midoPeer)
        throws Exception {

        new Expectations() {{
            // Per vtep
            vtepClientProvider.get(); result = vtepDataClient; times = 1;
            VtepBroker vB = new VtepBroker(vtepDataClient); times = 1;
            MidoVxLanPeer mP = new MidoVxLanPeer(dataClient); times = 1;
            new VxLanGwBroker(vB, mP); result = vxGwBroker; times = 1;
            vxGwBroker.start(); times = 1;
            vtepDataClient.connect(vtepMgmtIp, vtepMgmntPort); times = 1;

            // Bridge update (vxlanport addition)
            mP.watch((UUID)withNotNull()); result = true; times = 1;
            vB.advertiseMacs(); times = 1;

            // Shutdown
            vxGwBroker.shutdown(); times = 1;
            vtepDataClient.disconnect(); times = 1;
        }};

        VxLanGatewayService gwsrv = new VxLanGatewayService(dataClient,
                                                            vtepClientProvider,
                                                            zkConnWatcher);
        gwsrv.startAndWait();

        // add a new bridge without a binding
        UUID bridgeId = makeUnboundBridge("bridge1");

        // add a binding to the bridge
        dataClient.bridgeCreateVxLanPort(bridgeId, vtepMgmtIp, vtepMgmntPort,
                                         bridgePortVNI);

        // remove binding from the bridge
        dataClient.bridgeDeleteVxLanPort(bridgeId);

        gwsrv.stopAndWait();
    }
}
