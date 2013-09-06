/*
 * Copyright (c) 2012 Midokura Pte. Ltd
 */
package org.midonet.functional_test;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import akka.util.Duration;
import org.junit.Test;
import org.junit.Ignore;

import org.midonet.cassandra.CassandraClient;
import org.midonet.midolman.monitoring.metrics.VMMetricsCollection;
import org.midonet.midolman.monitoring.metrics.ZookeeperMetricsCollection;
import org.midonet.midolman.monitoring.metrics.vrn.VifMetrics;
import org.midonet.midolman.monitoring.store.CassandraStore;
import org.midonet.client.resource.Bridge;
import org.midonet.client.resource.BridgePort;
import org.midonet.functional_test.utils.TapWrapper;
import org.midonet.midolman.topology.LocalPortActive;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;
import org.midonet.util.lock.LockHelper;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.midonet.functional_test.FunctionalTestsHelper.assertPacketWasSentOnTap;
import static org.midonet.functional_test.FunctionalTestsHelper.removeTapWrapper;
import static org.midonet.util.Waiters.sleepBecause;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;

@Ignore
public class MonitoringTest extends TestBase {

    static int replicationFactor = 1;
    static int ttlInSecs = 1000;
    CassandraStore store;

    private Bridge bridge;
    private BridgePort intBridgePort;
    private BridgePort tapBridgePort;
    private PacketHelper helperTap_int;
    private IPv4Addr ipInt;
    private IPv4Addr ipTap;
    private TapWrapper metricsTap;

    private static LockHelper.Lock lock;


    @Override
    public void setup() {
        // Build a bridge and link it to the router's interior port
        bridge = apiClient.addBridge()
            .tenantId("tenant-monitoring")
            .name("bridge-metrics")
            .create();

        ipInt = IPv4Addr.fromString("192.168.231.4");
        MAC macInt = MAC.fromString("02:aa:bb:cc:ee:d1");
        intBridgePort = bridge.addExteriorPort().create();
        //ovsBridge.addInternalPort(intBridgePort.getId(), "metricsInt",
        //        ipInt, 24);
        thisHost.addHostInterfacePort()
            .interfaceName("metricsInt")
            .portId(intBridgePort.getId()).create();
        LocalPortActive activeMsg = probe.expectMsgClass(
            Duration.create(10, TimeUnit.SECONDS), LocalPortActive.class);
        assertTrue(activeMsg.active());
        log.info("Received local port active {}", activeMsg);

        ipTap = IPv4Addr.fromString("192.168.231.4");
        MAC macTap = MAC.fromString("02:aa:bb:cc:ee:d2");

        tapBridgePort = bridge.addExteriorPort().create();
        metricsTap = new TapWrapper("metricsTap");

        log.debug("Bind datapath's local port to bridge's exterior port.");
        //ovsBridge.addSystemPort(tapBridgePort.getId(), metricsTap.getName());
        thisHost.addHostInterfacePort()
            .interfaceName(metricsTap.getName())
            .portId(tapBridgePort.getId()).create();
        activeMsg = probe.expectMsgClass(
            Duration.create(10, TimeUnit.SECONDS), LocalPortActive.class);
        assertTrue(activeMsg.active());
        assertEquals(tapBridgePort.getId(), activeMsg.portID());

        helperTap_int = new PacketHelper(macTap, ipTap, macInt, ipInt);

        CassandraClient client = new CassandraClient("localhost:9171", 3,
                "midonet", "midonet_monitoring", "monitoring_data",
                replicationFactor, ttlInSecs, null);
        store = new CassandraStore(client);
        store.initialize();

    }

    @Override
    public void teardown() {
        removeTapWrapper(metricsTap);
    }

    @Test
    public void test() throws Exception {

        long startTime = System.currentTimeMillis();
        String hostName = thisHost.getId().toString();
        Map<String, Long> resZkMetrics = new HashMap<String, Long>();
        Map<String, Long> resVmMetrics = new HashMap<String, Long>();


        sleepBecause("Let's collect metrics for host " + hostName, 5);
        List<String> types = store.getMetricsTypeForTarget(hostName);
        assertThat("We didn't save the metric type", types.size(), greaterThan(2));

        List<String> zkMetrics =
                store.getMetricsForType(ZookeeperMetricsCollection.class.getSimpleName());

        assertThat("We didn't initialize some metric for ZooKeeper",
                zkMetrics.size(), greaterThan(0));

        for (String metric : zkMetrics) {
            Map<String, Long> res = store.getTSPoints(
                    ZookeeperMetricsCollection.class.getSimpleName(),
                    hostName, metric, startTime, System.currentTimeMillis());
            assertThat("The ts points for Zk metrics are > 0",
                    res.size(), greaterThan(0));
            Long lastValue = getValueLastKey(res);
            resZkMetrics.put(metric, lastValue);
        }

        List<String> vmMetrics =
                store.getMetricsForType(VMMetricsCollection.class.getSimpleName());

        assertThat("We didn't initialize some metric for the vm",
                vmMetrics.size(), greaterThan(0));

        for (String metric : vmMetrics) {
            Map<String, Long> res =
                    store.getTSPoints(VMMetricsCollection.class.getSimpleName(),
                            hostName, metric, startTime,
                            System.currentTimeMillis());

            assertThat("The ts points for vm metrics aren't > 0", res.size(),
                    greaterThan(0));

            Long lastValue = getValueLastKey(res);
            resVmMetrics.put(metric, lastValue);
        }

        List<String> vifMetrics = store.getMetricsForType(
                VifMetrics.class.getSimpleName());

        assertThat("We didn't initialize some metric for this vif",
                vifMetrics.size(), greaterThan(0));

        Map<String, Long> rxPackets =
                store.getTSPoints(VifMetrics.class.getSimpleName(),
                        tapBridgePort.getId().toString(), "rxPackets",
                        startTime, System.currentTimeMillis());

        // record the current metric value, take last key in the map
        long previousCount = getValueLastKey(rxPackets);
        long timeBeforeSending = System.currentTimeMillis();
        // send a packet
        assertPacketWasSentOnTap(metricsTap,
                helperTap_int.makeIcmpEchoRequest(ipInt));
        sleepBecause("need to wait for metric to update", 2);

        rxPackets =
                store.getTSPoints(VifMetrics.class.getSimpleName(),
                        tapBridgePort.getId().toString(), "rxPackets",
                        timeBeforeSending, System.currentTimeMillis());

        long currentValue = getValueLastKey(rxPackets);
        // check that the counter increased properly
        assertThat("the counter didn't increased properly",
                currentValue, is(previousCount + 1));

        timeBeforeSending = System.currentTimeMillis();

        // send another packet
        assertPacketWasSentOnTap(metricsTap,
                helperTap_int.makeIcmpEchoRequest(ipInt));
        sleepBecause("need to wait for metric to update", 2);

        rxPackets =
                store.getTSPoints(VifMetrics.class.getSimpleName(),
                        tapBridgePort.getId().toString(), "rxPackets",
                        timeBeforeSending, System.currentTimeMillis());

        currentValue = getValueLastKey(rxPackets);

        // check that the counter increased properly
        assertThat("the counter didn't increase properly",
                currentValue, is(previousCount + 2));

        timeBeforeSending = System.currentTimeMillis();

        assertPacketWasSentOnTap(metricsTap,
                helperTap_int.makeIcmpEchoRequest(ipInt));
        sleepBecause("need to wait for metric to update", 2);

        rxPackets =
                store.getTSPoints(VifMetrics.class.getSimpleName(),
                        tapBridgePort.getId().toString(), "rxPackets",
                        timeBeforeSending, System.currentTimeMillis());

        currentValue = getValueLastKey(rxPackets);

        // check that the counter increased properly
        assertThat("the counter didn't increased properly",
                currentValue, is(previousCount + 3));


        timeBeforeSending = System.currentTimeMillis();

        assertPacketWasSentOnTap(metricsTap,
                helperTap_int.makeIcmpEchoRequest(ipInt));
        sleepBecause("need to wait for metric to update", 2);

        rxPackets =
                store.getTSPoints(VifMetrics.class.getSimpleName(),
                        tapBridgePort.getId().toString(), "rxPackets",
                        timeBeforeSending, System.currentTimeMillis());
        currentValue = getValueLastKey(rxPackets);

        // check that the counter increased properly
        assertThat("the counter didn't increased properly",
                currentValue, is(previousCount + 4));

        // Since it's hard to predict these values, we will just check that they have been modified
        boolean succeed = false;
        for (String metric : zkMetrics) {
            Map<String, Long> res = store.getTSPoints(
                    ZookeeperMetricsCollection.class.getSimpleName(),
                    hostName, metric, timeBeforeSending,
                    System.currentTimeMillis());
            // if at least one metric got update we succeed
            if (!(getValueLastKey(res).equals(
                    (Long) resZkMetrics.get(metric)))) {
                succeed = true;
                break;
            }
        }

        assertThat("The values for Zk metrics haven't been updated",
                succeed, is(true));

        succeed = false;
        for (String metric : vmMetrics) {
            Map<String, Long> res =
                    store.getTSPoints(VMMetricsCollection.class.getSimpleName(),
                            hostName, metric, startTime,
                            System.currentTimeMillis());

            // if at least one metric got update we succeed
            if (!(getValueLastKey(res).equals(
                    (Long) resVmMetrics.get(metric)))) {
                succeed = true;
                break;
            }
        }

        assertThat("The values for VM metrics haven't been updated",
                succeed, is(true));
    }

    public static Long getValueLastKey(Map<String, Long> entries) {

        TreeSet<String> keys = new TreeSet<String>(entries.keySet());
        return entries.get(keys.descendingIterator().next());

    }
}
