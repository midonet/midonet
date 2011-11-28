/*
* Copyright 2011 Midokura Europe SARL
*/
package com.midokura.midonet.smoketest;

import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnectionImpl;
import com.midokura.midonet.smoketest.mocks.MidolmanMgmt;
import com.midokura.midonet.smoketest.mocks.MockMidolmanMgmt;
import com.midokura.midonet.smoketest.topology.*;
import com.midokura.midonet.smoketest.topology.AdRoute;
import com.midokura.midonet.smoketest.topology.Bgp;
import com.midokura.midonet.smoketest.topology.Router;
import com.midokura.midonet.smoketest.topology.Tenant;
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

        Bgp bgp = internalPort.addBgp()
            .setLocalAs(65104)
            .setPeer(23637, "180.214.47.65")
            .build();

        AdRoute advertisedRoute = bgp.addAdvertisedRoute("14.128.23.0", 27);

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
        int a = 10;
    }
}
