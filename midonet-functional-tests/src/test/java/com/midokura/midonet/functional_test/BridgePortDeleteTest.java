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
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

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
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.removeBridge;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.removeTapWrapper;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.removeTenant;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.sleepBecause;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.stopMidolman;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.stopMidolmanMgmt;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.waitForBridgeToConnect;
import static com.midokura.midonet.functional_test.utils.MidolmanLauncher.ConfigType.Default;
import static com.midokura.midonet.functional_test.utils.MidolmanLauncher.ConfigType.Without_Bgp;

public class BridgePortDeleteTest {

    private final static Logger log =
        LoggerFactory.getLogger(BridgePortDeleteTest.class);

    Tenant tenant1;
    IntIPv4 ip1 = IntIPv4.fromString("192.168.231.2");
    IntIPv4 ip2 = IntIPv4.fromString("192.168.231.3");

    OpenvSwitchDatabaseConnectionImpl ovsdb;
    MidolmanMgmt mgmt;
    MidolmanLauncher midolman1;
    MidolmanLauncher midolman2;
    BridgePort bPort1;
    BridgePort bPort2;
    Bridge bridge1;
    TapWrapper tap1;
    TapWrapper tap2;
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

        ovsBridge1 = new OvsBridge(ovsdb, "smoke-br");
        ovsBridge2 = new OvsBridge(ovsdb, "smoke-br2", "tcp:127.0.0.1:6657");

        // Add a service controller to OVS bridge 1.
        ovsBridge1.addServiceController(6640);
        svcController = new ServiceController(6640);
        waitForBridgeToConnect(svcController);

        bPort1 = bridge1.addPort();
        tap1 = new TapWrapper("tapBridgeDel1");
        ovsBridge1.addSystemPort(bPort1.getId(), tap1.getName());

        bPort2 = bridge1.addPort();
        tap2 = new TapWrapper("tapBridgeDel2");
        ovsBridge1.addSystemPort(bPort2.getId(), tap2.getName());

        sleepBecause("we want the network config to boot up properly", 5);
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
        assertThat("The ARP packet was sent properly.",tap1.send(pkt));
        assertThat("The received package is the same as the one sent",
                   tap2.recv(), equalTo(pkt));

        // There should now be one flow that outputs to ALL.
        Thread.sleep(1000);
        MidoMatch match1 = new MidoMatch().setDataLayerSource(mac1);

        List<FlowStats> fstats = svcController.getFlowStats(match1);
        assertThat("We should have only one FlowStats object.",
                   fstats, hasSize(1));

        FlowStats flow1 = fstats.get(0);
        flow1.expectCount(1).expectOutputAction(OFPort.OFPP_ALL.getValue());

        // Send unicast from Mac2/port2 to mac1.
        pkt = PacketHelper.makeIcmpEchoRequest(mac2, ip2, mac1, ip1);
        assertThat(
            String.format(
                "The ICMP echo packet was properly sent via %s", tap2.getName()),
            tap2.send(pkt));

        assertThat(
            String.format("We received the same packet on %s", tap1.getName()),
            tap1.recv(), equalTo(pkt));

        // There should now be one flow that outputs to port 1.
        Thread.sleep(1000);
        MidoMatch match2 = new MidoMatch().setDataLayerSource(mac2);
        fstats = svcController.getFlowStats(match2);
        assertThat("Only one FlowStats object should be returned.",
                   fstats, hasSize(1));

        FlowStats flow2 = fstats.get(0);
        short portNum1 =
            ovsdb.getPortNumByUUID(ovsdb.getPortUUID(tap1.getName()));
        flow2.expectCount(1).expectOutputAction(portNum1);

        // The first flow should not have changed.
        flow1.findSameInList(svcController.getFlowStats(match1))
             .expectCount(1);

        // Delete port1. It is the destination of flow2 and
        // the origin of flow1 - so expect both flows to be removed.
        ovsBridge1.deletePort(tap1.getName());
        sleepBecause("we want midolman to sense the port deletion", 1);

        assertThat(
            "No FlowStats object should be visible after we deleted a port",
            svcController.getFlowStats(match1), hasSize(0));
        assertThat(
            "No FlowStats object should be visible after we deleted a port",
            svcController.getFlowStats(match2), hasSize(0));
    }
}
