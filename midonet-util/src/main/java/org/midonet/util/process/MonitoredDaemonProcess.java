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

import java.util.List;
import java.util.LinkedList;
import java.util.Map;
import java.util.function.Consumer;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.AbstractService;

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
public class MonitoredDaemonProcess extends AbstractService {

    final private String cmd;
    final private Logger log;
    final private String prefix;
    final private int retriesPerPeriod;
    final private long period;
    final private Consumer<Exception> exitAction;
    final protected UnixClock clock = UnixClock$.MODULE$.apply();
    final private LinkedList<Long> startEvents;
    final private Map<String, String> envVars;

    volatile protected Process process;

    public MonitoredDaemonProcess(String cmd, Logger log, String prefix,
                                  int retriesPerPeriod, long period,
                                  Consumer<Exception> exitAction) {
        this(cmd, log, prefix, retriesPerPeriod, period, exitAction, null);
    }

    public MonitoredDaemonProcess(String cmd, Logger log, String prefix,
                                  int retriesPerPeriod, long period,
                                  Consumer<Exception> exitAction,
                                  Map<String, String> envVars) {
        this.cmd = cmd;
        this.log = log;
        this.prefix = prefix;
        this.retriesPerPeriod = retriesPerPeriod;
        this.period = period;
        this.exitAction = exitAction;
        this.startEvents = new LinkedList<>();
        this.envVars = envVars;
    }

    @Override
    protected void doStart() {
        synchronized (startEvents) {
            if (startEventsInPreviousPeriod() < retriesPerPeriod) {
                startEvents.offer(clock.time());
                createProcess();
                if (!isRunning()) {
                    notifyStarted();
                }
            } else {
                Exception reason = new Exception(
                    "Process ``" + cmd + "`` failed after " + retriesPerPeriod +
                    " retries in a period of " + period + " ms: " +
                    "notifying failure.");
                notifyFailed(reason);
                exitAction.accept(reason);
            }
        }
    }

    @Override
    protected void doStop() {
        if (process != null) {
            process.destroy();
            log.info("Process ``{}`` stopped", cmd);
        }
        notifyStopped();
    }

    @VisibleForTesting
    protected void createProcess() {
        ProcessHelper.RunnerConfiguration runner = ProcessHelper
            .newDaemonProcess(cmd, log, prefix)
            .addExitHandler(exitHandler);
        if (this.envVars != null) {
            runner.setEnvVariables(envVars);
        }
        process = runner.run();
        log.info("Process ``{}`` starting with pid {} at time {}",
                 cmd, ProcessHelper.getProcessPid(process), clock.time());
    }

    @VisibleForTesting
    protected List<Long> getStartEvents() {
        synchronized (startEvents) {
            return new LinkedList<Long>(startEvents);
        }
    }

    private int startEventsInPreviousPeriod() {
        synchronized (startEvents) {
            long startWindow = clock.time() - period;
            while (startEvents.size() > 0 && startEvents.peek() < startWindow) {
                startEvents.poll();
            }
            return startEvents.size();
        }
    }

    final Runnable exitHandler = new Runnable() {
        @Override
        public void run() {
            if (process != null) {
                log.warn("Process ``{}`` exited with error code {}",
                         cmd, process.exitValue());
            } else {
                log.warn("Process ``{}`` exited", cmd);
            }
            if (isRunning()) {
                doStart();
            }
        }
    };
}
