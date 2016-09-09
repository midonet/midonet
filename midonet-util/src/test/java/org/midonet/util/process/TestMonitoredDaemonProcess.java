/*
 * Copyright 2016 Midokura SARL
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
package org.midonet.util.process;

import java.util.LinkedList;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.util.MockUnixClock;
import org.midonet.util.UnixClock;
import org.midonet.util.UnixClock$;

import static org.hamcrest.MatcherAssert.assertThat;

public class TestMonitoredDaemonProcess {

    private static final Logger log = LoggerFactory
        .getLogger(TestProcessHelper.class);

    static {
        System.setProperty(UnixClock$.MODULE$.USE_MOCK_CLOCK_PROPERTY(), "yes");
    }

    public class TestableMonitoredDaemonProcess extends MonitoredDaemonProcess {
        public volatile boolean exited = false;
        final protected MockUnixClock clock = (MockUnixClock) UnixClock$.MODULE$.apply();

        public TestableMonitoredDaemonProcess(String cmd, Logger log,
                                              String prefix, int retries,
                                              long period, int exitErrorCode) {
            super(cmd, log, prefix, retries, period, exitErrorCode);
        }

        protected void exit() {
            exited = true;
        }

        public void killProcess() throws Exception {
            waitFor(() -> process.isAlive());
            process.destroy();
        }

        public void waitForStartEvents(int numEvents,
                                       LinkedList<Long> events) throws Exception {
            waitFor(() -> events.subList(0, numEvents).equals(startEvents));
        }

        public void waitForExitProcess() throws Exception {
            waitFor(() -> exited);
        }

        private void waitFor(Callable<Boolean> condition) throws Exception {
            int attempts = 10;
            while (attempts > 0 && !condition.call()) {
                log.debug("" + startEvents);
                Thread.sleep(1000);
                attempts--;
            }
            if (!condition.call())
                throw new AssertionError("Condition not met");
        }
    }

    @Test
    public void testMonitoredProcess() throws Exception {
        int numAttempts = 3;
        int period = 100;
        LinkedList<Long> events = new LinkedList<>();
        events.add(0L);
        events.add(0L);
        events.add(0L);

        TestableMonitoredDaemonProcess process =
            new TestableMonitoredDaemonProcess(
                "echo command", log, "", numAttempts, period, -1);

        process.start();
        process.waitForStartEvents(numAttempts, events);
        assertThat("The command has been executed three times",
                   process.startEvents.size() == 3);
        assertThat("The daemon exited",
                   process.exited);
    }

    @Test
    public void testMonitoredLongerProcess() throws Exception {
        int numAttempts = 3;
        int period = 100;

        TestableMonitoredDaemonProcess process =
            new TestableMonitoredDaemonProcess(
                "sleep 10", log, "", numAttempts, period, -1);

        process.start();
        LinkedList<Long> events1 = new LinkedList<>();
        events1.add(0L);
        LinkedList<Long> events2 = new LinkedList<>();
        events2.add(200L);
        events2.add(200L);
        LinkedList<Long> events3 = new LinkedList<>();
        events3.add(400L);
        events3.add(400L);
        events3.add(400L);

        process.waitForStartEvents(1, events1);
        process.clock.time_$eq(process.clock.time() + period * 2);
        process.killProcess();
        process.waitForStartEvents(1, events2);
        process.killProcess();
        process.waitForStartEvents(2, events2);

        assertThat("After " + numAttempts + " attempts, the process does not exit",
                   !process.exited);

        process.clock.time_$eq(process.clock.time() + period * 2);
        process.killProcess();
        process.waitForStartEvents(1, events3);
        process.killProcess();
        process.waitForStartEvents(2, events3);
        process.killProcess();
        process.waitForStartEvents(3, events3);
        process.killProcess();
        process.waitForExitProcess();
    }

    @Test
    public void testMonitoredProcessDoesNotRestartWhenShuttingDown() throws Exception {
        int numAttempts = 3;
        int period = 100;
        final AtomicInteger started = new AtomicInteger(0);

        TestableMonitoredDaemonProcess process =
            new TestableMonitoredDaemonProcess(
                "sleep 10", log, "", numAttempts, period, -1) {
                protected Runnable startHandler() {
                    return new Runnable() {
                        @Override
                        public void run() {
                            started.incrementAndGet();
                        }
                    };
                }
            };

        process.start();
        process.shutdown();
        assertThat("The process is started once", started.get() == 1);
        process.exitHandler.run();
        assertThat("And only once", started.get() == 1);
    }
}
