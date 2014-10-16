/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.odp;

import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import org.midonet.odp.flows.FlowKey;
import org.midonet.odp.flows.FlowKeyEtherType;
import org.midonet.odp.flows.FlowKeys;
import org.midonet.packets.ARP;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;
import org.midonet.packets.Net;

public class FlowKeyInterningTest {

    private List<Callable<FlowKey>> flowKeys = Arrays.asList(
        new Callable<FlowKey>() { public FlowKey call() throws Exception {
            return FlowKeys.inPort(53362);
        }},
        new Callable<FlowKey>() { public FlowKey call() throws Exception {
            return FlowKeys.ethernet(MAC.fromString("ae:b3:35:8c:a1:48").getAddress(),
                    MAC.fromString("33:33:00:00:00:16").getAddress());
        }},
        new Callable<FlowKey>() { public FlowKey call() throws Exception {
            return FlowKeys.etherType((short) 1234);
        }},
        new Callable<FlowKey>() { public FlowKey call() throws Exception {
            return FlowKeys.arp(MAC.fromString("ae:b3:77:8d:c1:48").getAddress(),
                    MAC.fromString("ae:b3:78:8d:c1:48").getAddress(),
                    ARP.OP_REPLY,
                    IPv4Addr.stringToInt("192.168.100.1"),
                    IPv4Addr.stringToInt("192.168.102.1"));
        }},
        new Callable<FlowKey>() { public FlowKey call() throws Exception {
            return FlowKeys.neighborDiscovery(Net.ipv6FromString(
                    "fe80::acb3:67ff:fe8c:a158"));
        }},
        new Callable<FlowKey>() { public FlowKey call() throws Exception {
            return FlowKeys.vlan((short) 0x03015);
        }},
        new Callable<FlowKey>() { public FlowKey call() throws Exception {
            return FlowKeys.tunnel(11L, 101, 202);
        }}
    );

    private static final int NUM_THREADS = 4;
    private ExecutorService testSlaves;

    @Before
    public void setup() {
        testSlaves = Executors.newFixedThreadPool(NUM_THREADS);
    }

    @After
    public void tearDown() throws InterruptedException {
        testSlaves.shutdown();
        testSlaves.awaitTermination(500, TimeUnit.MILLISECONDS);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testInterningOfFlowKeys() throws Exception {
        final CountDownLatch latch = new CountDownLatch(flowKeys.size());
        final WeakReference<FlowKey>[] wrs = (WeakReference<FlowKey>[])
                Array.newInstance(WeakReference.class, flowKeys.size());

        for (int i = 0; i < flowKeys.size(); ++i) {
            final int x = i;
            final Callable<FlowKey> fk = flowKeys.get(i);
            testSlaves.execute(new Runnable() {
                @Override
                public void run() {
                    wrs[x] = verifyInterning(fk);
                    latch.countDown();
                }
            });
        }

        latch.await();

        for (WeakReference<FlowKey> wr : wrs) {
            verifyWeakInterning(wr);
        }
    }

    private WeakReference<FlowKey> verifyInterning(Callable<FlowKey> c) {
        try {
            FlowKey original = c.call();
            for (int i = 0; i < 10; ++i) {
                Assert.assertSame(original, c.call());
            }
            return new WeakReference<>(original);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void verifyWeakInterning(WeakReference<FlowKey> wr) {
        for (int i = 0; i < 5; ++i) {
            if (wr.get() == null)
                return;

            System.gc();
        }

        Assert.fail("Interned flow key did not get GCed: " + wr.get());
    }
}
