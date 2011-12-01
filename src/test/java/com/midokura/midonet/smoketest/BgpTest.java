/*
* Copyright 2011 Midokura Europe SARL
*/

package com.midokura.midonet.smoketest;

import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnectionImpl;
import com.midokura.midonet.smoketest.mocks.MidolmanMgmt;
import com.midokura.midonet.smoketest.mocks.MockMidolmanMgmt;
import com.midokura.midonet.smoketest.topology.*;
import com.midokura.midonet.smoketest.vm.VMController;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Author: Toader Mihai Claudiu <mtoader@gmail.com>
 * <p/>
 * Date: 11/28/11
 * Time: 1:34 PM
 */
public class BgpTest {

    private final static Logger log = LoggerFactory.getLogger(BgpTest.class);

    static Tenant tenant;
    static InternalPort internalPort;
    static Bgp bgp;
    static MidolmanMgmt mgmt;

    static VMController bgpPeerVm;

    static OpenvSwitchDatabaseConnection ovsdb;

    static String tapPortName = "tapPort1";

    @BeforeClass
    public static void setUp() throws InterruptedException, IOException {

        ovsdb = new OpenvSwitchDatabaseConnectionImpl("Open_vSwitch",
                                                         "127.0.0.1",
                                                         12344);
        mgmt = new MockMidolmanMgmt(false);

        tenant = new Tenant.Builder(mgmt).setName("tenant1").build();

        Router router = tenant.addRouter().setName("rtr1").build();

        internalPort = router.addPort(ovsdb)
                           .setDestination("192.168.100.3")
                           .buildInternal();

        // the bgp machine builder will create a vm bound to a local tap and
        // assign the following address 10.10.173.2/24
        // we will create a tap device on the local machine with the
        // 10.0.173.1/24 address so we can communicate with it


        Bgp bgp = internalPort.addBgp()
            .setLocalAs(65104)
            .setPeer(23637, "10.10.173.2")
            .build();

        AdRoute advertisedRoute = bgp.addAdvertisedRoute("14.128.23.0", 27);

/*
        LibvirtHandler libvirtHandler = LibvirtHandler.forHypervisor(HypervisorType.Qemu);

        libvirtHandler.setTemplate("basic_template_x86_64");

        bgpPeerVm = libvirtHandler.newBgpDomain()
                .setDomainName("test_bgp")
                .setHostName("bgppeer")
                .setLocalAS(345)
                .setPeerAS(543)
                .setPeerIP("192.168.10.1")
                .build();

*/
        Thread.sleep(1000);
    }

    @AfterClass
    public static void tearDown() {

        ovsdb.delBridge("smoke-br");

        try {
            mgmt.deleteTenant("tenant1");
        } catch (Exception e) {
        }
    }

    @Test
    public void testBgpConfiguration() throws Exception {
//        int a = 10;
    }
}
