/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test;

import static com.midokura.tools.timed.Timed.newTimedExecution;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

import java.io.IOException;
import java.net.NetworkInterface;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnectionImpl;
import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midonet.functional_test.mocks.MidolmanMgmt;
import com.midokura.midonet.functional_test.mocks.MockMidolmanMgmt;
import com.midokura.midonet.functional_test.topology.AdRoute;
import com.midokura.midonet.functional_test.topology.Bgp;
import com.midokura.midonet.functional_test.topology.MidoPort;
import com.midokura.midonet.functional_test.topology.OvsBridge;
import com.midokura.midonet.functional_test.topology.Route;
import com.midokura.midonet.functional_test.topology.Router;
import com.midokura.midonet.functional_test.topology.TapWrapper;
import com.midokura.midonet.functional_test.topology.Tenant;
import com.midokura.midonet.functional_test.vm.HypervisorType;
import com.midokura.midonet.functional_test.vm.VMController;
import com.midokura.midonet.functional_test.vm.libvirt.LibvirtHandler;
import com.midokura.tools.timed.Timed;

/**
 * Author: Toader Mihai Claudiu <mtoader@midkura.com>
 * <p/>
 * Date: 11/28/11
 * Time: 1:34 PM
 */
public class BgpTest extends AbstractSmokeTest {

    private final static Logger log = LoggerFactory.getLogger(BgpTest.class);

    static Tenant tenant;
    static Router router;
    static TapWrapper bgpPort;
    static Bgp bgp;
    static MidolmanMgmt mgmt;
    static OvsBridge ovsBridge;

    static VMController bgpPeerVm;

    static OpenvSwitchDatabaseConnection ovsdb;

    static String bgpPortPortName = "bgpPeerPort";

    static String peerAdvertisedNetworkAddr = "10.173.0.0";
    static int peerAdvertisedNetworkLength = 24;

    static NetworkInterface ethernetDevice;

    @BeforeClass
    public static void setUp() throws InterruptedException, IOException {

        ovsdb = new OpenvSwitchDatabaseConnectionImpl("Open_vSwitch",
                                                      "127.0.0.1", 12344);

        if (ovsdb.hasBridge("smoke-br"))
            ovsdb.delBridge("smoke-br");
        ovsBridge = new OvsBridge(ovsdb, "smoke-br", OvsBridge.L3UUID);

        mgmt = new MockMidolmanMgmt(false);

        tenant = new Tenant.Builder(mgmt).setName("tenant1").build();

        router = tenant.addRouter().setName("rtr1").build();

        IntIPv4 ip1 = IntIPv4.fromString("10.10.173.1");
        IntIPv4 ip2 = IntIPv4.fromString("10.10.173.2");
        MidoPort p1 = router.addGwPort().setLocalLink(ip1, ip2).build();
        bgpPort = new TapWrapper(bgpPortPortName);
        ovsBridge.addSystemPort(p1.port.getId(), bgpPort.getName());

        bgpPort.closeFd();

        Bgp bgp = p1.addBgp()
                .setLocalAs(543)
                .setPeer(345, ip2.toString())
                .build();

        AdRoute advertisedRoute = bgp.addAdvertisedRoute("14.128.23.0", 27);

        LibvirtHandler libvirtHandler = LibvirtHandler.forHypervisor
                                                           (HypervisorType
                                                                .Qemu);

        libvirtHandler.setTemplate("basic_template_x86_64");

        // the bgp machine builder will create a vm bound to a local tap and
        // assign the following address 10.10.173.2/24
        // it will also configure the quagga daemons using the information
        // provided here
        // it will also set the advertized route to 10.173.0.0/24
        bgpPeerVm = libvirtHandler
                        .newBgpDomain()
                        .setDomainName("bgpPeer")
                        .setHostName("bgppeer")
                        .setNetworkDevice(bgpPort.getName())
                        .setLocalAS(345)
                        .setPeerAS(543)
                        .build();


        Thread.sleep(1000);
    }

    @AfterClass
    public static void tearDown() {
        try {
            if ( bgpPeerVm != null ) {
                bgpPeerVm.destroy();
            }

            Thread.sleep(2000);
        } catch (InterruptedException e) {
            //
        }

        removeTapWrapper(bgpPort);

        removeTenant(tenant);
        mgmt.stop();


        if (ovsdb.hasBridge("smoke-br")) {
            ovsdb.delBridge("smoke-br");
        }

        resetZooKeeperState(log);
    }

    @Test
    public void testBgpConfiguration() throws Exception {

        assertNoPeerAdvertisedRouteIsRegistered(router.getRoutes());

        bgpPeerVm.startup();

        Timed.ExecutionResult<Boolean> result =
            newTimedExecution()
                .until(100 * 1000)
                .waiting(500)
                .execute(new Timed.Execution<Boolean>() {
                    @Override
                    public void _runOnce() throws Exception {
                        if ( checkPeerAdRouteIsRegistered(router.getRoutes())) {
                            setCompleted(true);
                            setResult(Boolean.TRUE);
                        }
                    }
                });
        
        assertThat("The result object should not be null",
                      result, is(notNullValue()));

        assertThat("The wait for the new route should have completed successfully",
                   result.completed());

        assertThat(result.result(), is(notNullValue()));
        assertThat("The execution result should have been successful", result.result());
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

            assertThat(route.getDstNetworkAddr(), not(equalTo(peerAdvertisedNetworkAddr)));
            assertThat(route.getDstNetworkLength(), not(equalTo(peerAdvertisedNetworkLength)));
        }

        return routesMap;
    }
}
