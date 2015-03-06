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
package org.midonet.cluster.data.neutron;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CountDownLatch;

import org.apache.curator.test.TestingServer;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class tests simultaneous access to Zookeeper data via Neutron API
 */
public class DataLockingTest extends NeutronPluginTest {

    private static final Logger logger =
        LoggerFactory.getLogger(DataLockingTest.class);

    private CountDownLatch latch;
    private Queue<Throwable> errors;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        errors = new ConcurrentLinkedDeque<>();
    }

    @After // overriding so we can annotate it
    public void tearDown() throws Exception {
        super.tearDown();
    }

    private class PortDeleteCreate implements Runnable {

        private final Port port;
        private final int count;

        public PortDeleteCreate(Port port, int count) {
            this.port = port;
            this.count = count;
        }

        @Override
        public void run() {

            long tid = Thread.currentThread().getId();
            try {
                for (int i = 0; i < count; i++) {

                    logger.debug("{}: Deleting {}", tid, port.id);
                    plugin.deletePort(port.id);

                    logger.debug("{}: Creating {}",tid, port);
                    plugin.createPort(port);

                }
            } catch (Exception e) {
                logger.debug("{}: Exception thrown {}", tid, e);
                errors.add(e);
                throw new RuntimeException(e);
            } finally {
                latch.countDown();
            }
        }
    }

    /**
     * Test that port creation and deletion from two threads work even though
     * they access the same data (metadata routes).
     */
    @Test
    public void testPortsUpdate() throws Throwable {

        latch = new CountDownLatch(2);

        Thread dp = new Thread(new PortDeleteCreate(dhcpPort, 10));
        Thread rp = new Thread(new PortDeleteCreate(routerPort, 10));

        dp.start();
        rp.start();

        latch.await();

        if (errors.size() > 0) {
            // just raise the first exception recorded
            throw errors.remove();
        }
    }
}
