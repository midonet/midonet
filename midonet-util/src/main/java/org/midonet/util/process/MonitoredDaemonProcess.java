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

import com.google.common.annotations.VisibleForTesting;

import org.slf4j.Logger;

import org.midonet.util.UnixClock;
import org.midonet.util.UnixClock$;

/**
 * A monitored daemon process is a subprocess being monitored by the parent
 * process. It will restart the subprocess for a number of times during a
 * specific time interval. If the number of failures during an interval exceeds
 * the provided limit, the parent process will exit with the specified exit
 * error code.
 */
public class MonitoredDaemonProcess {

    final private String cmd;
    final private Logger log;
    final private String prefix;
    final private int retries;
    final private long period;
    final private int exitErrorCode;
    final protected UnixClock clock = UnixClock$.MODULE$.apply();
    final protected LinkedList<Long> startEvents;

    protected volatile boolean shuttingDown = false;
    protected Process process;

    public MonitoredDaemonProcess(String cmd, Logger log, String prefix,
                                  int retries, long period, int exitErrorCode) {
        this.cmd = cmd;
        this.log = log;
        this.prefix = prefix;
        this.period = period;
        this.exitErrorCode = exitErrorCode;
        this.retries = retries;
        this.startEvents = new LinkedList<>();
    }

    public void start() {
        synchronized (startEvents) {
            long startWindow = clock.time() - period;
            while (startEvents.size() > 0 && startEvents.peek() < startWindow) {
                startEvents.poll();
            }

            if (startEvents.size() < retries) {
                startHandler().run();
            } else {
                log.error("Process ``{}`` failed after {} times in a "
                          + "period of {} ms: shutting down", cmd, retries,
                          period);
                exit();
            }
        }
    }

    public void shutdown() {
        shuttingDown = true;
        if (process != null) {
            process.destroy();
            log.info("Process ``{}`` stopped", cmd);
        }
    }

    @VisibleForTesting
    protected Runnable startHandler() {
        return new Runnable() {
            @Override
            public void run() {
                startEvents.offer(clock.time());
                process = ProcessHelper.newDaemonProcess(cmd, log, prefix)
                    .addExitHandler(exitHandler)
                    .run();
                log.info("Process ``{}`` starting with pid {} at time {}",
                         cmd, ProcessHelper.getProcessPid(process), clock.time());
            }
        };
    }

    @VisibleForTesting
    protected Runnable exitHandler = new Runnable() {
        @Override
        public void run() {
            if (process != null) {
                log.warn("Process ``{}`` exited with error code {}",
                         cmd, process.exitValue());
            } else {
                log.warn("Process ``{}`` exited", cmd);
            }
            if (!shuttingDown) {
                start();
            }
        }
    };

    @VisibleForTesting
    protected void exit() {
        System.exit(exitErrorCode);
    }
}
