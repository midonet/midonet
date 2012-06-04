/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnectionImpl;
import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midonet.functional_test.mocks.MidolmanMgmt;
import com.midokura.midonet.functional_test.mocks.MockMidolmanMgmt;
import com.midokura.midonet.functional_test.topology.Bgp;
import com.midokura.midonet.functional_test.topology.MaterializedRouterPort;
import com.midokura.midonet.functional_test.topology.OvsBridge;
import com.midokura.midonet.functional_test.topology.Route;
import com.midokura.midonet.functional_test.topology.Router;
import com.midokura.midonet.functional_test.topology.TapWrapper;
import com.midokura.midonet.functional_test.topology.Tenant;
import com.midokura.midonet.functional_test.utils.MidolmanLauncher;
import com.midokura.midonet.functional_test.vm.HypervisorType;
import com.midokura.midonet.functional_test.vm.VMController;
import com.midokura.midonet.functional_test.vm.libvirt.LibvirtHandler;
import com.midokura.tools.timed.Timed;
import com.midokura.util.lock.LockHelper;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.destroyVM;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.fixQuaggaFolderPermissions;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.removeBridge;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.removeTapWrapper;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.removeTenant;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.stopMidolman;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.stopMidolmanMgmt;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.waitFor;

/**
 * @author Mihai Claudiu Toader <mtoader@midkura.com>
 *         Date: 11/28/11
 */
public class BgpTest {

    private final static Logger log = LoggerFactory.getLogger(BgpTest.class);

    Tenant tenant;
    Router router;
    TapWrapper bgpPort;
    MidolmanMgmt mgmt;
    MidolmanLauncher midolman;
    OvsBridge ovsBridge;

    VMController bgpPeerVm;

    OpenvSwitchDatabaseConnection ovsdb;

    String bgpPortPortName = "bgpPeerPort";

    String peerAdvertisedNetworkAddr = "10.173.0.0";
    int peerAdvertisedNetworkLength = 24;

    static LockHelper.Lock lock;

    @BeforeClass
    public static void checkLock() {
        lock = LockHelper.lock(FunctionalTestsHelper.LOCK_NAME);
    }

    @AfterClass
    public static void releaseLock() {
        lock.release();
    }

    @Before
    public void setUp() throws InterruptedException, IOException {

        fixQuaggaFolderPermissions();

        ovsdb = new OpenvSwitchDatabaseConnectionImpl("Open_vSwitch",
                                                      "127.0.0.1", 12344);

        if (ovsdb.hasBridge("smoke-br"))
            ovsdb.delBridge("smoke-br");

        ovsBridge = new OvsBridge(ovsdb, "smoke-br");

        mgmt = new MockMidolmanMgmt(false);
        midolman = MidolmanLauncher.start(MidolmanLauncher.ConfigType.With_Bgp,
                                          "BgpTest");

        tenant = new Tenant.Builder(mgmt).setName("tenant-bgp").build();

        router = tenant.addRouter().setName("rtr1").build();

        IntIPv4 ip1 = IntIPv4.fromString("10.10.173.1");
        IntIPv4 ip2 = IntIPv4.fromString("10.10.173.2");
        MaterializedRouterPort p1 = router.addGwPort().setLocalLink(ip1, ip2).build();
        bgpPort = new TapWrapper(bgpPortPortName);
        ovsBridge.addSystemPort(p1.port.getId(), bgpPort.getName());

        bgpPort.closeFd();

        Bgp bgp = p1.addBgp()
                    .setLocalAs(543)
                    .setPeer(345, ip2.toString())
                    .build();

        bgp.addAdvertisedRoute("14.128.23.0", 27);

        LibvirtHandler libvirtHandler = LibvirtHandler.forHypervisor
            (HypervisorType.Qemu);

        libvirtHandler.setTemplate("basic_template_x86_64");

        // the bgp machine builder will create a vm bound to a local tap and
        // assign the following address 10.10.173.2/24
        // it will also configure the Quagga daemons using the information
        // provided here
        // it will also set the advertised route to 10.173.0.0/24
        log.debug("=== Creating bpgPeerVm ===");
        bgpPeerVm = libvirtHandler
            .newBgpDomain()
            .setDomainName("bgpPeer")
            .setHostName("bgppeer")
            .setNetworkDevice(bgpPort.getName())
            .setLocalAS(345)
            .setPeerAS(543)
            .build();
        log.debug("=== bpgPeerVm: {} ===", bgpPeerVm);

        // TODO: What are we waiting for here ?
        Thread.sleep(1000);
    }

    @After
    public void tearDown() {

        destroyVM(bgpPeerVm);

        removeTapWrapper(bgpPort);
        removeBridge(ovsBridge);
        stopMidolman(midolman);
        removeTenant(tenant);
        stopMidolmanMgmt(mgmt);
    }

    @Test
    public void testBgpConfiguration() throws Exception {

        assertNoPeerAdvertisedRouteIsRegistered(router.getRoutes());

        bgpPeerVm.startup();

        waitFor("The new route is advertised to the router",
                TimeUnit.SECONDS.toMillis(100),
                TimeUnit.SECONDS.toMillis(1),
                new Timed.Execution<Boolean>() {
                    @Override
                    public void _runOnce() throws Exception {
                        setResult(
                            checkPeerAdRouteIsRegistered(router.getRoutes()));
                        setCompleted(getResult());
                    }
                });
    }

    private boolean checkPeerAdRouteIsRegistered(Route[] routes) {

        int matchingRoutes = 0;
        for (Route route : routes) {

            if (route.getDstNetworkAddr().equals(peerAdvertisedNetworkAddr) &&
                route.getDstNetworkLength() ==
                    peerAdvertisedNetworkLength) {
                matchingRoutes++;
            }
        }

        return matchingRoutes == 1;
    }

    private Map<UUID, Route> assertNoPeerAdvertisedRouteIsRegistered(Route[]
                                                                         routes) {

        Map<UUID, Route> routesMap = new HashMap<UUID, Route>();

        for (Route route : routes) {
            routesMap.put(route.getId(), route);

            assertThat(route.getDstNetworkAddr(),
                       not(equalTo(peerAdvertisedNetworkAddr)));
            assertThat(route.getDstNetworkLength(),
                       not(equalTo(peerAdvertisedNetworkLength)));
        }

        return routesMap;
    }
}
