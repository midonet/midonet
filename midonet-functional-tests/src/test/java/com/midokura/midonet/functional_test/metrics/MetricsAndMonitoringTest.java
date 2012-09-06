/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midonet.functional_test.metrics;

import javax.management.MBeanServerConnection;
import javax.management.remote.JMXConnector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import com.midokura.midolman.monitoring.metrics.vrn.VifMetrics;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnectionImpl;
import com.midokura.packets.IntIPv4;
import com.midokura.packets.MAC;
import com.midokura.midonet.functional_test.FunctionalTestsHelper;
import com.midokura.midonet.functional_test.PacketHelper;
import com.midokura.midonet.functional_test.mocks.MidolmanMgmt;
import com.midokura.midonet.functional_test.mocks.MockMidolmanMgmt;
import com.midokura.midonet.functional_test.topology.Bridge;
import com.midokura.midonet.functional_test.topology.BridgePort;
import com.midokura.midonet.functional_test.topology.OvsBridge;
import com.midokura.midonet.functional_test.topology.TapWrapper;
import com.midokura.midonet.functional_test.topology.Tenant;
import com.midokura.midonet.functional_test.utils.MidolmanLauncher;
import com.midokura.util.jmx.JMXAttributeAccessor;
import com.midokura.util.jmx.JMXHelper;
import com.midokura.util.lock.LockHelper;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.assertPacketWasSentOnTap;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.fixQuaggaFolderPermissions;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.removeBridge;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.removeTapWrapper;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.removeTenant;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.sleepBecause;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.stopMidolman;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.stopMidolmanMgmt;

/**
 * Functional tests for the metrics and monitoring.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 5/4/12
 */
public class MetricsAndMonitoringTest {

    private static final Logger log = LoggerFactory
        .getLogger(MetricsAndMonitoringTest.class);

    OpenvSwitchDatabaseConnection ovsdb;
    OvsBridge ovsBridge;
    MidolmanLauncher midolman;
    MidolmanMgmt api;

    private Bridge bridge;
    private Tenant tenant;
    private BridgePort intBridgePort;
    private BridgePort tapBridgePort;
    private PacketHelper helperTap_int;
    private IntIPv4 ipInt;
    private IntIPv4 ipTap;
    private TapWrapper metricsTap;

    private static LockHelper.Lock lock;

    @BeforeClass
    public void setUp() throws Exception {
        lock = LockHelper.lock(FunctionalTestsHelper.LOCK_NAME);

        fixQuaggaFolderPermissions();

        ovsdb = new OpenvSwitchDatabaseConnectionImpl(
            "Open_vSwitch", "127.0.0.1", 12344);

        midolman = MidolmanLauncher.start("MetricsAndMonitoringTest");
        api = new MockMidolmanMgmt(false);

        sleepBecause("Give ten seconds to midolman to startup", 10);

        if (ovsdb.hasBridge("smoke-br"))
            ovsdb.delBridge("smoke-br");

        ovsBridge = new OvsBridge(ovsdb, "smoke-br");

        tenant = new Tenant.Builder(api).setName("tenant-metrics").build();

        bridge = tenant.addBridge()
                       .setName("bridge-metrics")
                       .build();

        ipInt = IntIPv4.fromString("192.168.231.4");
        MAC macInt = MAC.fromString("02:aa:bb:cc:ee:d1");
        intBridgePort = bridge.addPort().build();
        ovsBridge.addInternalPort(intBridgePort.getId(), "metricsInt",
                                  ipInt, 24);

        ipTap = IntIPv4.fromString("192.168.231.4");
        MAC macTap = MAC.fromString("02:aa:bb:cc:ee:d2");
        tapBridgePort = bridge.addPort().build();
        metricsTap = new TapWrapper("metricsTap");
        ovsBridge.addSystemPort(tapBridgePort.getId(), metricsTap.getName());

        helperTap_int = new PacketHelper(macTap, ipTap, macInt, ipInt);
    }

    @AfterClass
    public void tearDown() throws Exception {
        removeTapWrapper(metricsTap);
        removeBridge(ovsBridge);
        stopMidolman(midolman);
        removeTenant(tenant);
       // stopMidolmanMgmt(api);
        lock.release();
    }

    @Test
    public void testRxMetrics() throws Exception {

        JMXConnector connector =
            JMXHelper.newJvmJmxConnectorForPid(midolman.getPid());

        assertThat("The JMX connection should have been non-null.",
                   connector, notNullValue());

        MBeanServerConnection connection = connector.getMBeanServerConnection();

        JMXAttributeAccessor<Long> rxPkts =
            JMXHelper.<Long>newAttributeAccessor(connection, "Count")
                     .withNameDomain(VifMetrics.class)
                     .withNameKey("type", VifMetrics.class)
                     .withNameKey("scope", tapBridgePort.getId())
                     .withNameKey("name", "rxPackets")
                     .build();

        // record the current metric value
        long previousCount = rxPkts.getValue();

        // send a packet
        assertPacketWasSentOnTap(metricsTap,
                                 helperTap_int.makeIcmpEchoRequest(ipInt));
        sleepBecause("need to wait for metric to update", 1);

        // check that the counter increased properly
        assertThat("the counter didn't increased properly",
                   rxPkts.getValue(), is(previousCount + 1));

        // send another packet
        assertPacketWasSentOnTap(metricsTap,
                                 helperTap_int.makeIcmpEchoRequest(ipInt));
        sleepBecause("need to wait for metric to update", 1);

        // check that the counter increased properly
        assertThat("the counter didn't increase properly",
                   rxPkts.getValue(), is(previousCount + 2));

        assertPacketWasSentOnTap(metricsTap,
                                 helperTap_int.makeIcmpEchoRequest(ipInt));
        sleepBecause("need to wait for metric to update", 1);

        // check that the counter increased properly
        assertThat("the counter didn't increased properly",
                   rxPkts.getValue(), is(previousCount + 3));

        assertPacketWasSentOnTap(metricsTap,
                                 helperTap_int.makeIcmpEchoRequest(ipInt));
        sleepBecause("need to wait for metric to update", 1);

        // check that the counter increased properly
        assertThat("the counter didn't increased properly",
                   rxPkts.getValue(), is(previousCount + 4));

        connector.close();
    }
}
