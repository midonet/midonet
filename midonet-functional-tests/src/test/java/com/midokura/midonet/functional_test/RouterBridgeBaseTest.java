/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.AfterClass;
import org.junit.BeforeClass;

import com.midokura.packets.IntIPv4;
import com.midokura.packets.MAC;
import com.midokura.midonet.functional_test.mocks.MidolmanMgmt;
import com.midokura.midonet.functional_test.mocks.MockMidolmanMgmt;
import com.midokura.midonet.functional_test.topology.Bridge;
import com.midokura.midonet.functional_test.topology.BridgePort;
import com.midokura.midonet.functional_test.topology.InteriorBridgePort;
import com.midokura.midonet.functional_test.topology.InteriorRouterPort;
import com.midokura.midonet.functional_test.topology.ExteriorRouterPort;
import com.midokura.midonet.functional_test.topology.Router;
import com.midokura.midonet.functional_test.utils.TapWrapper;
import com.midokura.midonet.functional_test.topology.Tenant;
import com.midokura.midonet.functional_test.utils.MidolmanLauncher;
import com.midokura.util.lock.LockHelper;


import static com.midokura.midonet.functional_test.FunctionalTestsHelper.*;
import static com.midokura.midonet.functional_test.utils.MidolmanLauncher.ConfigType.Default;
import static com.midokura.util.Waiters.sleepBecause;

public abstract class RouterBridgeBaseTest {
    static MidolmanMgmt mgmt;
    static MidolmanLauncher midolman1;
    static Tenant tenant1;
    static Router router1;
    static ExteriorRouterPort routerUplink;
    static EndPoint rtrUplinkEndpoint;
    static InteriorRouterPort routerDownlink;
    static Bridge bridge1;
    static final int numBridgePorts = 5;
    static InteriorBridgePort bridgeUplink;
    static List<BridgePort> bports = new ArrayList<BridgePort>();
    static List<EndPoint> vmEndpoints = new ArrayList<EndPoint>();
    static IntIPv4 floatingIP0 = IntIPv4.fromString("112.0.0.10");
    static IntIPv4 floatingIP1 = IntIPv4.fromString("112.0.0.20");

    static LockHelper.Lock lock;

    @BeforeClass
    public static void classSetup() throws IOException, InterruptedException {
        lock = LockHelper.lock(FunctionalTestsHelper.LOCK_NAME);

        mgmt = new MockMidolmanMgmt(false);
        midolman1 = MidolmanLauncher.start(Default, "RouterBridgeBaseTest");

        // Create a tenant with a single router and bridge.
        tenant1 = new Tenant.Builder(mgmt).setName("tenant-rbtest").build();

        // Create a router with an uplink.
        router1 = tenant1.addRouter().setName("rtr1").build();
        IntIPv4 gwIP = IntIPv4.fromString("172.16.0.2");
        TapWrapper rtrUplinkTap = new TapWrapper("routerUplink");
        routerUplink = router1.addGwPort()
                .setLocalMac(rtrUplinkTap.getHwAddr())
                .setLocalLink(IntIPv4.fromString("172.16.0.1"), gwIP)
                .addRoute(IntIPv4.fromString("0.0.0.0", 0)).build();
        rtrUplinkEndpoint = new EndPoint(gwIP, MAC.random(),
                routerUplink.getIpAddr(), routerUplink.getMacAddr(),
                rtrUplinkTap);
        //ovsBridge1.addSystemPort(
        //        routerUplink.port.getId(),
        //        rtrUplinkTap.getName());

        // Create the bridge and link it to the router.
        bridge1 = tenant1.addBridge().setName("br1").build();
        routerDownlink = router1.addLinkPort()
                .setNetworkAddress("10.0.0.0")
                .setNetworkLength(24)
                .setPortAddress("10.0.0.1").build();
        bridgeUplink = bridge1.addLinkPort().build();
        routerDownlink.link(bridgeUplink);

        // Add ports to the bridge.
        for (int i = 0; i < numBridgePorts; i++) {
            bports.add(bridge1.addPort().build());
            vmEndpoints.add(new EndPoint(
                    IntIPv4.fromString("10.0.0.1" + i),
                    MAC.fromString("02:aa:bb:cc:dd:d" + i),
                    routerDownlink.getIpAddr(),
                    routerDownlink.getMacAddr(),
                    new TapWrapper("invalTap" + i)));
            //ovsBridge1.addSystemPort(
            //        bports.get(i).getId(),
            //        vmEndpoints.get(i).tap.getName());
        }

        sleepBecause("we need the network to boot up", 10);
    }

    @AfterClass
    public static void classTearDown() {
        removeTapWrapper(rtrUplinkEndpoint.tap);
        for (EndPoint ep : vmEndpoints)
            removeTapWrapper(ep.tap);

        stopMidolman(midolman1);

        if (null != routerDownlink)
            routerDownlink.unlink();
        removeTenant(tenant1);
     //   stopMidolmanMgmt(mgmt);
        lock.release();
    }
}
