/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test;

import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openflow.protocol.OFPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnectionImpl;
import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midolman.packets.MAC;
import com.midokura.midonet.functional_test.mocks.MidolmanMgmt;
import com.midokura.midonet.functional_test.mocks.MockMidolmanMgmt;
import com.midokura.midonet.functional_test.openflow.FlowStats;
import com.midokura.midonet.functional_test.openflow.ServiceController;
import com.midokura.midonet.functional_test.topology.Bridge;
import com.midokura.midonet.functional_test.topology.BridgePort;
import com.midokura.midonet.functional_test.topology.OvsBridge;
import com.midokura.midonet.functional_test.topology.TapWrapper;
import com.midokura.midonet.functional_test.topology.Tenant;
import com.midokura.midonet.functional_test.utils.MidolmanLauncher;
import static com.midokura.midonet.functional_test.utils.MidolmanLauncher.ConfigType.Default;
import static com.midokura.midonet.functional_test.utils.MidolmanLauncher.ConfigType.Without_Bgp;

public class BridgePortDeleteTest extends AbstractSmokeTest {

    private final static Logger log = LoggerFactory.getLogger(BridgePortDeleteTest.class);

    Tenant tenant1;
    IntIPv4 ip1;
    IntIPv4 ip2;
    IntIPv4 ip3;
    PacketHelper helper1_3;
    PacketHelper helper3_1;
    OpenvSwitchDatabaseConnectionImpl ovsdb;
    MidolmanMgmt mgmt;
    MidolmanLauncher midolman1;
    MidolmanLauncher midolman2;
    BridgePort bPort1;
    BridgePort bPort2;
    BridgePort bPort3;
    Bridge bridge1;
    TapWrapper tap1;
    TapWrapper tap2;
    TapWrapper tap3;
    OvsBridge ovsBridge1;
    OvsBridge ovsBridge2;
    ServiceController svcController;

    @Before
    public void setUp() throws Exception {

        ovsdb = new OpenvSwitchDatabaseConnectionImpl("Open_vSwitch",
                                                      "127.0.0.1", 12344);
        mgmt = new MockMidolmanMgmt(false);
        midolman1 = MidolmanLauncher.start(Default, "BridgePortDeleteTest-smoke_br");
        midolman2 = MidolmanLauncher.start(Without_Bgp, "BridgePortDeleteTest-smoke_br2");

        if (ovsdb.hasBridge("smoke-br"))
            ovsdb.delBridge("smoke-br");
        if (ovsdb.hasBridge("smoke-br2"))
            ovsdb.delBridge("smoke-br2");

        tenant1 = new Tenant.Builder(mgmt).setName("tenant-bridge").build();
        bridge1 = tenant1.addBridge().setName("br1").build();

        ovsBridge1 = new OvsBridge(ovsdb, "smoke-br", bridge1.getId());
        ovsBridge2 = new OvsBridge(ovsdb, "smoke-br2", bridge1.getId(),
                                   "tcp:127.0.0.1:6657");

        // Add a service controller to OVS bridge 1.
        ovsBridge1.addServiceController(6640);
        svcController = new ServiceController(6640);
        waitForBridgeToConnect(svcController);

        ip1 = IntIPv4.fromString("192.168.231.2");
        bPort1 = bridge1.addPort();
        tap1 = new TapWrapper("tap1");
        ovsBridge1.addSystemPort(bPort1.getId(), tap1.getName());

        ip2 = IntIPv4.fromString("192.168.231.3");
        bPort2 = bridge1.addPort();
        tap2 = new TapWrapper("tap2");
        ovsBridge1.addSystemPort(bPort2.getId(), tap2.getName());

        ip3 = IntIPv4.fromString("192.168.231.4");
        bPort3 = bridge1.addPort();
        tap3 = new TapWrapper("tap3");
        ovsBridge2.addSystemPort(bPort3.getId(), tap3.getName());

        helper1_3 = new PacketHelper(tap1.getHwAddr(), ip1, tap3.getHwAddr(),
                                     ip3);
        helper3_1 = new PacketHelper(tap3.getHwAddr(), ip3, tap1.getHwAddr(),
                                     ip1);

        Thread.sleep(10000);
    }

    @After
    public void tearDown() {
        removeTapWrapper(tap1);
        removeTapWrapper(tap2);

        removeBridge(ovsBridge1);
        removeBridge(ovsBridge2);

        stopMidolman(midolman1);
        stopMidolman(midolman2);

        removeTenant(tenant1);
        stopMidolmanMgmt(mgmt);
    }

    @Test
    public void testPortDelete() throws InterruptedException {
        // Use different MAC addrs from other tests (unlearned MACs).
        MAC mac1 = MAC.fromString("02:00:00:00:aa:01");
        MAC mac2 = MAC.fromString("02:00:00:00:aa:02");
        // Send broadcast from Mac1/port1.
        byte[] pkt = PacketHelper.makeArpRequest(mac1, ip1, ip2);

        assertThat("The ARP packet was sent properly.",
                   tap1.send(pkt), equalTo(true));
        assertThat("The received package is the same as the one sent",
                   tap2.recv(), equalTo(pkt));

        // There should now be one flow that outputs to ALL.
        Thread.sleep(1000);
        MidoMatch match1 = new MidoMatch().setDataLayerSource(mac1);
        List<FlowStats> fstats = svcController.getFlowStats(match1);
        assertEquals(1, fstats.size());
        FlowStats flow1 = fstats.get(0);
        flow1.expectCount(1).expectOutputAction(OFPort.OFPP_ALL.getValue());

        // Send unicast from Mac2/port2 to mac1.
        pkt = PacketHelper.makeIcmpEchoRequest(mac2, ip2, mac1, ip1);
        assertTrue(tap2.send(pkt));
        assertArrayEquals(pkt, tap1.recv());

        // There should now be one flow that outputs to port 1.
        Thread.sleep(1000);
        MidoMatch match2 = new MidoMatch().setDataLayerSource(mac2);
        fstats = svcController.getFlowStats(match2);
        assertEquals(1, fstats.size());
        FlowStats flow2 = fstats.get(0);
        short portNum1 = ovsdb.getPortNumByUUID(ovsdb.getPortUUID(
            tap1.getName()));
        flow2.expectCount(1).expectOutputAction(portNum1);

        // The first flow should not have changed.
        flow1.findSameInList(svcController.getFlowStats(match1))
             .expectCount(1);

        // Delete port1. It is the destination of flow2 and
        // the origin of flow1 - so expect both flows to be removed.
        ovsBridge1.deletePort(tap1.getName());
        Thread.sleep(1000);
        assertEquals(0, svcController.getFlowStats(match1).size());
        assertEquals(0, svcController.getFlowStats(match2).size());

        // Re-add the OVS port to leave things as we found them.
        ovsBridge1.addSystemPort(bPort1.getId(), tap1.getName());
    }
}
