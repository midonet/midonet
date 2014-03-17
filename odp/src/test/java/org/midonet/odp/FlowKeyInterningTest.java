/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.odp;

import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.midonet.odp.flows.FlowKey;
import org.midonet.odp.flows.FlowKeyEtherType;
import org.midonet.odp.flows.FlowKeys;
import org.midonet.packets.ARP;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;
import org.midonet.packets.Net;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;

public class FlowKeyInterningTest {

    private static List<Callable<FlowKey>> flowKeys = Arrays.asList(
        new Callable<FlowKey>() { public FlowKey call() throws Exception {
            return FlowKeys.inPort(0);
        }},
        new Callable<FlowKey>() { public FlowKey call() throws Exception {
            return FlowKeys.ethernet(MAC.fromString("ae:b3:77:8c:a1:48").getAddress(),
                    MAC.fromString("33:33:00:00:00:16").getAddress());
        }},
        new Callable<FlowKey>() { public FlowKey call() throws Exception {
            return FlowKeys.etherType(FlowKeyEtherType.Type.ETH_P_IP);
        }},
        new Callable<FlowKey>() { public FlowKey call() throws Exception {
            return FlowKeys.arp(MAC.fromString("ae:b3:77:8d:c1:48").getAddress(),
                    MAC.fromString("ae:b3:70:8d:c1:48").getAddress(),
                    ARP.OP_REPLY,
                    IPv4Addr.stringToInt("192.168.100.1"),
                    IPv4Addr.stringToInt("192.168.102.1"));
        }},
        new Callable<FlowKey>() { public FlowKey call() throws Exception {
            return FlowKeys.neighborDiscovery(Net.ipv6FromString(
                    "fe80::acb3:77ff:fe8c:a148"));
        }},
        new Callable<FlowKey>() { public FlowKey call() throws Exception {
            return FlowKeys.vlan((short) 0x0101);
        }},
        new Callable<FlowKey>() { public FlowKey call() throws Exception {
            return FlowKeys.tunnel(10L, 100, 200);
        }}
    );

    private static final int NUM_THREADS = 4;
    private static ExecutorService testSlaves;

    @BeforeClass
    public static void setup() {
        testSlaves = Executors.newFixedThreadPool(NUM_THREADS);
    }

    @AfterClass
    public static void tearDown() throws InterruptedException {
        testSlaves.shutdown();
        testSlaves.awaitTermination(500, TimeUnit.MILLISECONDS);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testInterningOfFlowKeys() throws Exception {
        final ReferenceQueue<FlowKey> rq = new ReferenceQueue<>();
        final CountDownLatch latch = new CountDownLatch(flowKeys.size());
        final List<WeakReference<FlowKey>> wrs = new ArrayList<>(flowKeys.size());

        for (int i = 0; i < flowKeys.size(); ++i) {
            final int x = i;
            final Callable<FlowKey> fk = flowKeys.get(i);
            testSlaves.execute(new Runnable() {
                @Override
                public void run() {
                wrs.add(x, verifyInterning(fk, rq));
                latch.countDown();
                }
            });
        }

        latch.await();

        for (WeakReference<FlowKey> wr : wrs) {
            verifyWeakInterning(wr);
        }
    }

    private WeakReference<FlowKey> verifyInterning(Callable<FlowKey> c,
                                                   ReferenceQueue<FlowKey> rq) {
        try {
            FlowKey original = c.call();
            for (int i = 0; i < 10; ++i) {
                Assert.assertSame(original, c.call());
            }
            return new WeakReference<>(original, rq);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void verifyWeakInterning(WeakReference<FlowKey> wr) {
        for (int i = 0; i < 5; ++i) {
            if (wr.isEnqueued())
                return;

            System.gc();
        }
        Assert.fail("Interned flow key did not get GCed");
    }
}
