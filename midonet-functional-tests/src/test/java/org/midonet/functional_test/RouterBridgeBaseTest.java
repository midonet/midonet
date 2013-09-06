/*
 * Copyright 2012 Midokura Europe SARL
 */

package org.midonet.functional_test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import akka.util.Duration;
import org.junit.AfterClass;
import org.junit.BeforeClass;

import org.midonet.client.dto.DtoRoute;
import org.midonet.client.resource.Bridge;
import org.midonet.client.resource.BridgePort;
import org.midonet.client.resource.HostInterfacePort;
import org.midonet.client.resource.Router;
import org.midonet.client.resource.RouterPort;
import org.midonet.functional_test.utils.TapWrapper;
import org.midonet.midolman.topology.LocalPortActive;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.midonet.functional_test.FunctionalTestsHelper.removeTapWrapper;

public abstract class RouterBridgeBaseTest extends TestBase {
    static final String TENANT_NAME = "rb-tenant";
    Router router1;
    RouterPort routerUplink;
    EndPoint rtrUplinkEndpoint;
    RouterPort routerDownlink;
    Bridge bridge1;
    static final int numBridgePorts = 5;
    BridgePort bridgeUplink;
    List<BridgePort> bports = new ArrayList<BridgePort>();
    List<EndPoint> vmEndpoints = new ArrayList<EndPoint>();
    List<TapWrapper> taps = new ArrayList<TapWrapper>();
    List<HostInterfacePort> portBindings = new ArrayList<HostInterfacePort>();
    static final IPv4Addr floatingIP0 = IPv4Addr.fromString("112.0.0.10");
    static final IPv4Addr floatingIP1 = IPv4Addr.fromString("112.0.0.20");

    @Override
    public void setup() {
        // Create a router with an uplink.
        router1 = apiClient.addRouter().name("rtr1")
            .tenantId(TENANT_NAME).create();
        IPv4Addr gwIP = IPv4Addr.fromString("172.16.0.2");
        TapWrapper rtrUplinkTap = new TapWrapper("routerUplink");
        routerUplink = router1.addExteriorRouterPort()
            .portAddress("172.16.0.1")
            .networkAddress("172.16.0.0").networkLength(24).create();
        router1.addRoute().dstNetworkAddr("0.0.0.0").dstNetworkLength(0)
            .srcNetworkAddr("0.0.0.0").srcNetworkLength(0)
            .nextHopPort(routerUplink.getId()).type(DtoRoute.Normal)
            .weight(100).create();
        rtrUplinkEndpoint = new EndPoint(gwIP, MAC.random(),
                IPv4Addr.fromString(routerUplink.getPortAddress()),
            MAC.fromString(routerUplink.getPortMac()),
            rtrUplinkTap);
        portBindings.add(
            thisHost.addHostInterfacePort()
                .interfaceName(rtrUplinkTap.getName())
                .portId(routerUplink.getId()).create()
        );
        LocalPortActive activeMsg = probe.expectMsgClass(
            Duration.create(10, TimeUnit.SECONDS), LocalPortActive.class);
        assertTrue(activeMsg.active());
        assertEquals(routerUplink.getId(), activeMsg.portID());

        // Create the bridge and link it to the router.
        bridge1 = apiClient.addBridge().name("br1")
            .tenantId(TENANT_NAME).create();
        routerDownlink = router1.addInteriorRouterPort()
                .networkAddress("10.0.0.0").networkLength(24)
                .portAddress("10.0.0.1").create();
        bridgeUplink = bridge1.addInteriorPort().create();
        bridgeUplink.link(routerDownlink.getId());

        // Add ports to the bridge.
        for (int i = 0; i < numBridgePorts; i++) {
            bports.add(bridge1.addExteriorPort().create());
            vmEndpoints.add(new EndPoint(
                    IPv4Addr.fromString("10.0.0.1" + i),
                    MAC.fromString("02:aa:bb:cc:dd:d" + i),
                    IPv4Addr.fromString(routerDownlink.getPortAddress()),
                    MAC.fromString(routerDownlink.getPortMac()),
                    new TapWrapper("invalTap" + i)));
            taps.add(vmEndpoints.get(i).tap);
            portBindings.add(
                thisHost.addHostInterfacePort()
                    .interfaceName(vmEndpoints.get(i).tap.getName())
                    .portId(bports.get(i).getId()).create()
            );
            activeMsg = probe.expectMsgClass(
                Duration.create(10, TimeUnit.SECONDS), LocalPortActive.class);
            assertTrue(activeMsg.active());
            assertEquals(bports.get(i).getId(), activeMsg.portID());
        }

    }

    @Override
    public void teardown() {
        if (rtrUplinkEndpoint != null) {
            removeTapWrapper(rtrUplinkEndpoint.tap);
            rtrUplinkEndpoint = null;
        }
        for (EndPoint ep : vmEndpoints)
            removeTapWrapper(ep.tap);

        if (null != routerDownlink) {
            routerDownlink.unlink();
            routerDownlink = null;
        }
        for (HostInterfacePort binding : portBindings)
            binding.delete();
        if (null != router1) {
            router1.delete();
            router1 = null;
        }
        if (null != bridge1) {
            bridge1.delete();
            bridge1 = null;
        }
    }
}
