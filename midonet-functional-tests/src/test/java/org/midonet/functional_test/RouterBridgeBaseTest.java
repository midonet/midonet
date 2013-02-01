/*
 * Copyright 2012 Midokura Europe SARL
 */

package org.midonet.functional_test;

import java.util.ArrayList;
import java.util.List;

import org.junit.AfterClass;
import org.junit.BeforeClass;

import org.midonet.client.dto.DtoRoute;
import org.midonet.client.resource.Bridge;
import org.midonet.client.resource.BridgePort;
import org.midonet.client.resource.Router;
import org.midonet.client.resource.RouterPort;
import org.midonet.functional_test.utils.TapWrapper;
import org.midonet.packets.IntIPv4;
import org.midonet.packets.MAC;


import static org.midonet.functional_test.FunctionalTestsHelper.removeTapWrapper;

public abstract class RouterBridgeBaseTest extends TestBase {
    static Router router1;
    static RouterPort routerUplink;
    static EndPoint rtrUplinkEndpoint;
    static RouterPort routerDownlink;
    static Bridge bridge1;
    static final int numBridgePorts = 5;
    static BridgePort bridgeUplink;
    static List<BridgePort> bports = new ArrayList<BridgePort>();
    static List<EndPoint> vmEndpoints = new ArrayList<EndPoint>();
    static IntIPv4 floatingIP0 = IntIPv4.fromString("112.0.0.10");
    static IntIPv4 floatingIP1 = IntIPv4.fromString("112.0.0.20");

    @Override
    public void setup() {
        // Create a router with an uplink.
        router1 = apiClient.addRouter().name("rtr1").create();
        IntIPv4 gwIP = IntIPv4.fromString("172.16.0.2");
        TapWrapper rtrUplinkTap = new TapWrapper("routerUplink");
        routerUplink = router1.addExteriorRouterPort()
            .portAddress("172.16.0.1")
            .networkAddress("172.16.0.0").networkLength(24).create();
        router1.addRoute().dstNetworkAddr("0.0.0.0").dstNetworkLength(0)
            .nextHopPort(routerUplink.getId()).type(DtoRoute.Normal)
            .weight(100).create();
        rtrUplinkEndpoint = new EndPoint(gwIP, MAC.random(),
            IntIPv4.fromString(routerUplink.getPortAddress()),
            MAC.fromString(routerUplink.getPortMac()),
            rtrUplinkTap);
        //ovsBridge1.addSystemPort(
        //        routerUplink.port.getId(),
        //        rtrUplinkTap.getName());

        // Create the bridge and link it to the router.
        bridge1 = apiClient.addBridge().name("br1").create();
        routerDownlink = router1.addInteriorRouterPort()
                .networkAddress("10.0.0.0").networkLength(24)
                .portAddress("10.0.0.1").create();
        bridgeUplink = bridge1.addInteriorPort().create();
        bridgeUplink.link(routerUplink.getId());

        // Add ports to the bridge.
        for (int i = 0; i < numBridgePorts; i++) {
            bports.add(bridge1.addExteriorPort().create());
            vmEndpoints.add(new EndPoint(
                    IntIPv4.fromString("10.0.0.1" + i),
                    MAC.fromString("02:aa:bb:cc:dd:d" + i),
                    IntIPv4.fromString(routerDownlink.getPortAddress()),
                    MAC.fromString(routerDownlink.getPortMac()),
                    new TapWrapper("invalTap" + i)));
            //ovsBridge1.addSystemPort(
            //        bports.get(i).getId(),
            //        vmEndpoints.get(i).tap.getName());
        }

    }

    @Override
    public void teardown() {
        removeTapWrapper(rtrUplinkEndpoint.tap);
        for (EndPoint ep : vmEndpoints)
            removeTapWrapper(ep.tap);

        if (null != routerDownlink)
            routerDownlink.unlink();
    }
}
