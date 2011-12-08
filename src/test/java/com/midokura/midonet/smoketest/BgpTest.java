/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.smoketest;

import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnectionImpl;
import com.midokura.midonet.smoketest.mocks.MidolmanMgmt;
import com.midokura.midonet.smoketest.mocks.MockMidolmanMgmt;
import com.midokura.midonet.smoketest.topology.*;
import com.midokura.midonet.smoketest.vm.HypervisorType;
import com.midokura.midonet.smoketest.vm.VMController;
import com.midokura.midonet.smoketest.vm.libvirt.LibvirtHandler;
import com.midokura.tools.process.ProcessHelper;
import com.midokura.tools.timed.Timed;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.NetworkInterface;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static com.midokura.tools.timed.Timed.newTimedExecution;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

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
    static TapPort bgpPort;
    static Bgp bgp;
    static MidolmanMgmt mgmt;

    static VMController bgpPeerVm;

    static OpenvSwitchDatabaseConnection ovsdb;

    static String bgpPortPortName = "bgpPeerPort";

    static String peerAdvertisedNetworkAddr = "10.173.0.0";
    static int peerAdvertisedNetworkLength = 24;

    static NetworkInterface ethernetDevice;

    @BeforeClass
    public static void setUp() throws InterruptedException, IOException {

        ovsdb = new OpenvSwitchDatabaseConnectionImpl(
                                                         "Open_vSwitch",
                                                         "127.0.0.1", 12344);

        mgmt = new MockMidolmanMgmt(false);

        tenant = new Tenant.Builder(mgmt).setName("tenant1").build();


        router = tenant.addRouter().setName("rtr1").build();

        bgpPort = router.addPort(ovsdb)
                      .setDestination("10.10.173.1")
                      .setOVSPortName(bgpPortPortName)
                      .buildTap();

        bgpPort.closeFd();

        Bgp bgp = bgpPort.addBgp()
                      .setLocalAs(543)
                      .setPeer(345, "10.10.173.2")
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

        removeTapPort(bgpPort);

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
                .until(50 * 1000)
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
        assertThat("The wait for the new route should have completed successfully", result.completed());

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
