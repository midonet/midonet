package com.midokura.midonet.functional_test;/*
 * Copyright (c) 2012 Midokura Pte.Ltd.
 */

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.cassandraunit.utils.EmbeddedCassandraServerHelper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;

import com.midokura.midolman.agent.NodeAgent;
import com.midokura.midolman.monitoring.metrics.VMMetricsCollection;
import com.midokura.midolman.monitoring.metrics.ZookeeperMetricsCollection;
import com.midokura.midolman.monitoring.metrics.vrn.VifMetrics;
import com.midokura.midolman.monitoring.store.CassandraStore;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnectionImpl;
import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midolman.packets.MAC;
import com.midokura.midonet.functional_test.mocks.MidolmanMgmt;
import com.midokura.midonet.functional_test.mocks.MockMidolmanMgmt;
import com.midokura.midonet.functional_test.topology.Bridge;
import com.midokura.midonet.functional_test.topology.BridgePort;
import com.midokura.midonet.functional_test.topology.OvsBridge;
import com.midokura.midonet.functional_test.topology.Tenant;
import com.midokura.midonet.functional_test.utils.MidolmanLauncher;
import com.midokura.midonet.functional_test.utils.RemoteTap;
import com.midokura.midonet.functional_test.utils.ZKLauncher;
import com.midokura.remote.RemoteHost;
import com.midokura.util.lock.LockHelper;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.cleanupZooKeeperData;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.removeRemoteTap;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.removeTenant;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.sleepBecause;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.stopMidolman;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.stopMidolmanMgmt;

/**
 * Date: 5/31/12
 */
public class MonitoringTest {

    static int replicationFactor = 1;
    static int ttlInSecs = 1000;
    CassandraStore store;
    OpenvSwitchDatabaseConnection ovsdb;
    OvsBridge ovsBridge;
    MidolmanLauncher midolman;
    MidolmanMgmt api;
    ZKLauncher launcher;

    private Bridge bridge;
    private Tenant tenant;
    private BridgePort intBridgePort;
    private BridgePort tapBridgePort;
    private PacketHelper helperTap_int;
    private IntIPv4 ipInt;
    private IntIPv4 ipTap;
    private RemoteTap metricsTap;

    private static LockHelper.Lock lock;
    private static long startTime;
    private static final String hostId = "e3f9adc0-5175-11e1-b86c-0800200c9a66";
    String basePath = "/smoketest/midolman";

    private HierarchicalConfiguration config;

    NodeAgent agent;

    @Before
    public void setUp() throws Exception {
        RemoteHost.getSpecification();

        lock = LockHelper.lock(FunctionalTestsHelper.LOCK_NAME);

        midolman = MidolmanLauncher.start(
            MidolmanLauncher.ConfigType.Monitoring,
            "MonitoringTest");

        //config = new HierarchicalINIConfiguration(configFilePath);
        launcher = ZKLauncher.start(ZKLauncher.ConfigType.Jmx_Enabled, "12222");

        EmbeddedCassandraServerHelper.startEmbeddedCassandra();
        api = new MockMidolmanMgmt(false);

        ///fixQuaggaFolderPermissions();

        ovsdb = new OpenvSwitchDatabaseConnectionImpl(
            "Open_vSwitch", "127.0.0.1", 12344);

        sleepBecause("Give ten seconds to midolman to startup", 10);

        /*if (ovsdb.hasBridge("smoke-br"))
            ovsdb.delBridge("smoke-br");*/
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
//        metricsTap = new TapWrapper("metricsTap");
        metricsTap = new RemoteTap("metricsTap");
        ovsBridge.addSystemPort(tapBridgePort.getId(), metricsTap.getName());

        helperTap_int = new PacketHelper(macTap, ipTap, macInt, ipInt);

        sleepBecause("Give ten seconds to midolman to startup", 30);

        store = new CassandraStore("localhost:9171",
                                   "midonet",
                                   "midonet_monitoring_keyspace",
                                   "midonet_monitoring_column_family",
                                   replicationFactor, ttlInSecs);

    }

    @After
    public void tearDown() throws IOException, InterruptedException {
        EmbeddedCassandraServerHelper.stopEmbeddedCassandra();
        removeRemoteTap(metricsTap);
        //removeBridge(ovsBridge);
        stopMidolman(midolman);
        removeTenant(tenant);
        stopMidolmanMgmt(api);
        cleanupZooKeeperData();
        launcher.stop();
        lock.release();
    }

    @Test
    public void test() throws Exception {
        startTime = System.currentTimeMillis();
        sleepBecause("Let's collect metrics", 5);
        List<String> zkMetrics =
            store.getMetrics(ZookeeperMetricsCollection.class.getSimpleName(),
                             hostId);

        assertThat("We initialized some metric for ZooKeeper",
                   zkMetrics.size(), greaterThan(0));

        for (String metric : zkMetrics) {
            Map<String, Long> res = store.getTSPoints(
                ZookeeperMetricsCollection.class.getSimpleName(),
                hostId, metric, startTime, System.currentTimeMillis());
            assertThat("The ts points for Zk metrics are > 0",
                       res.size(), greaterThan(0));
        }

        List<String> vmMetrics =
            store.getMetrics(VMMetricsCollection.class.getSimpleName(), hostId);

        assertThat("We initialized some metric for the vm",
                   vmMetrics.size(), greaterThan(0));

        for (String metric : vmMetrics) {
            Map<String, Long> res =
                store.getTSPoints(VMMetricsCollection.class.getSimpleName(),
                                  hostId, metric, startTime,
                                  System.currentTimeMillis());

            assertThat("The ts points for vm metrics are > 0", res.size(),
                       greaterThan(0));
        }

        List<String> vifMetrics = store.getMetrics(
            VifMetrics.class.getSimpleName(), tapBridgePort.getId().toString());

        assertThat("We initialized some metric for this vif",
                   vifMetrics.size(), greaterThan(0));
    }
}
