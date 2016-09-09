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
package org.midonet.util.process;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.management.monitor.Monitor;

import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.midonet.util.process.ProcessHelper.OutputStreams.StdError;
import static org.midonet.util.process.ProcessHelper.OutputStreams.StdOutput;

/**
 * Tests that test the ProcessHelper internal api entry point.
 */
public class TestProcessHelper {

    private static final Logger log = LoggerFactory
        .getLogger(TestProcessHelper.class);

    @Test
    public void testLaunchProcess() throws Exception {
        assertCommandCode("false", 1);
        assertCommandCode("true", 0);
    }

    @Test
    public void testNoCommand() throws Exception {
        assertCommandCode("xyzt", -1);
    }

    @Test
    public void testLongerProcess() throws Exception {
        long currentTime = System.currentTimeMillis();

        assertCommandCode("sleep 1", 0);

        assertThat("The process helper waited at least 1 second",
                   System.currentTimeMillis() - currentTime,
                   greaterThan(TimeUnit.SECONDS.toMillis(1)));
    }

    @Test
    public void testExitHandler() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        ProcessHelper.newProcess("echo command").addExitHandler(new Runnable() {
            @Override
            public void run() {
                latch.countDown();
            }
        }).run();
        latch.await();
    }

    @Test
    public void testMonitoredProcess() throws Exception {
        final Long currentTime = new Long(0);

        MonitoredDaemonProcess process = new TestableMonitoredDaemonProcess(
            "echo command", log, "", 3, 100, -1);

        process.start();
        process.exitHandler().run();

    }

    public class TestableMonitoredDaemonProcess extends MonitoredDaemonProcess {
        public long currentTime = 0L;

        public TestableMonitoredDaemonProcess(String cmd, Logger log, String prefix,
                                              int retries, long period, int exitErrorCode) {
            super(cmd, log, prefix, retries, period, exitErrorCode);
        }

        @Override
        protected long now () {
            return currentTime;
        }
    }

    private void assertCommandCode(String command, int code) {
        assertThat(
            "The command \"" + command + "\" didn't return the proper exit code",
            ProcessHelper
                .newProcess(command)
                .logOutput(log, "command",
                           StdOutput, StdError)
                .runAndWait(),
            equalTo(code));
    }
}
