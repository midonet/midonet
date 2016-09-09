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
import java.util.function.Consumer;

import com.google.common.util.concurrent.Service;

import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.util.MockUnixClock;
import org.midonet.util.UnixClock$;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;

public class TestMonitoredDaemonProcess {

    private static final Logger log = LoggerFactory
        .getLogger(TestProcessHelper.class);

    static {
        System.setProperty(UnixClock$.MODULE$.USE_MOCK_CLOCK_PROPERTY(), "yes");
    }


    public class TestableMonitoredDaemonProcess extends MonitoredDaemonProcess {
        final protected MockUnixClock clock = (MockUnixClock) UnixClock$.MODULE$.apply();

        public TestableMonitoredDaemonProcess(String cmd, Logger log,
                                              String prefix, int retries,
                                              long period, Consumer<Exception> exitAction) {
            super(cmd, log, prefix, retries, period, exitAction);
        }

        public void killProcess() throws Exception {
            waitFor(() -> process.isAlive());
            process.destroy();
        }

        public void waitForStartEvents(int numEvents,
                                       LinkedList<Long> events) throws Exception {
            waitFor(() -> events.subList(0, numEvents).equals(startEvents));
        }

        private void waitFor(Callable<Boolean> condition) throws Exception {
            int attempts = 20;
            while (attempts > 0 && !condition.call()) {
                log.debug("Start events: " + startEvents);
                Thread.sleep(1000);
                attempts--;
            }
            if (!condition.call())
                throw new AssertionError("Condition not met");
        }
    }

    public class ExitAction implements Consumer<Exception> {
        public boolean exited = false;

        @Override
        public void accept(Exception e) {
            log.debug(e.getMessage());
            exited = true;
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
        events.add(0L);

        ExitAction action = new ExitAction();
        TestableMonitoredDaemonProcess process =
            new TestableMonitoredDaemonProcess(
                "sleep 1", log, "", numAttempts, period, action);

        process.startAsync().awaitRunning(5, SECONDS);
        process.waitFor(() -> action.exited);
        assertThat("The command has been executed three times",
                   process.startEvents.size() == 3);
    }

    @Test
    public void testMonitoredLongerProcess() throws Exception {
        int numAttempts = 3;
        int period = 100;

        ExitAction action = new ExitAction();
        TestableMonitoredDaemonProcess process =
            new TestableMonitoredDaemonProcess(
                "sleep 5", log, "", numAttempts, period, action);

        process.startAsync().awaitRunning(5, SECONDS);
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
                   !action.exited);

        process.clock.time_$eq(process.clock.time() + period * 2);
        process.killProcess();
        process.waitForStartEvents(1, events3);
        process.killProcess();
        process.waitForStartEvents(2, events3);
        process.killProcess();
        process.waitForStartEvents(3, events3);
        process.killProcess();
        process.waitFor(
            () -> (process.state() == Service.State.FAILED));
    }

    @Test
    public void testMonitoredProcessDoesNotRestartWhenShuttingDown() throws Exception {
        int numAttempts = 3;
        int period = 100;
        final AtomicInteger started = new AtomicInteger(0);

        ExitAction action = new ExitAction();
        TestableMonitoredDaemonProcess process =
            new TestableMonitoredDaemonProcess(
                "sleep 10", log, "", numAttempts, period, action) {

                @Override
                protected void createProcess() {
                    started.incrementAndGet();
                }
            };

        process.startAsync().awaitRunning(5, SECONDS);
        process.stopAsync().awaitTerminated(5, SECONDS);
        assertThat("The process is started once", started.get() == 1);
        process.exitHandler.run();
        assertThat("And only once", started.get() == 1);
    }
}
