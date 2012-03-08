/*
 * Copyright 2012 Midokura Pte. Ltd.
 */
package com.midokura.util.process;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.midokura.tools.timed.Timed;
import static com.midokura.util.process.ProcessOutputDrainer.DrainTarget;

/**
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 11/24/11
 */
public class ProcessHelper {

    private final static Logger log = LoggerFactory.getLogger(
        ProcessHelper.class);

    public static RunnerConfiguration newProcess(final String commandLine) {
        return new RunnerConfiguration() {
            Logger logger;
            String stdMarker;
            public DrainTarget drainTarget;

            @Override
            public RunnerConfiguration logOutput(Logger log, String marker) {
                logger = log;
                stdMarker = marker;
                return this;
            }

            public RunnerConfiguration setDrainTarget(DrainTarget drainTarget) {
                this.drainTarget = drainTarget;
                return this;
            }

            @Override
            public int runAndWait() {
                Process p = createProcess(true);

                try {
                    if (p != null) {
                        p.waitFor();

                        log.debug("Process \"{}\" exited with code: {}",
                                  commandLine, p.exitValue());
                        return p.exitValue();
                    }
                } catch (InterruptedException e) {
                    log.error(
                        String.format("Error while launching command: \"%s\"",
                                      commandLine), e);
                }

                return -1;
            }

            public Process run() {
                return createProcess(false);
            }

            private Process createProcess(boolean wait) {
                try {
                    Process p = Runtime.getRuntime().exec(commandLine);
                    if (drainTarget == null) {
                        drainTarget = logger == null
                            ? DrainTargets.noneTarget()
                            : DrainTargets.slf4jTarget(logger, stdMarker);
                    }

                    new ProcessOutputDrainer(p).drainOutput(drainTarget, wait);
                    return p;
                } catch (IOException e) {
                    log.error(
                        String.format("Error while launching command: \"%s\"",
                                      commandLine), e);
                }

                return null;
            }
        };
    }

    public static void killProcess(final Process process) {
        // try to kill it naturally. If that fails we will try the hard way.
        process.destroy();

        try {
            // wait to see if the process exists for a couple of seconds
            if (checkForProcessExit(process))
                return;

            log.debug("Process wasn't destroyed by Process.destroy so we will " +
                          "try a kill-15 {}", process);

            Field field = process.getClass().getDeclaredField("pid");
            field.setAccessible(true);
            Object o = field.get(process);

            if (o instanceof Integer) {
                int pid = Integer.class.cast(o);

                // try to send a kill -15 signal first.
                newProcess("kill -15 " + pid)
                    .setDrainTarget(DrainTargets.noneTarget())
                    .runAndWait();
                log.debug("kill -9 executed properly");

                checkForProcessExit(process);
            }
        } catch (Exception e) {
            //
        }
    }

    private static boolean checkForProcessExit(final Process process)
        throws Exception {

        Timed.ExecutionResult<Integer> waitResult =
            Timed.newTimedExecution()
                 .waiting(100)
                 .until(6 * 1000)
                 .execute(new Timed.Execution<Integer>() {
                     @Override
                     protected void _runOnce() throws Exception {
                         try {
                             setResult(process.exitValue());
                             setCompleted(true);
                         } catch (IllegalThreadStateException e) {
                             // this exception is thrown if the process has not
                             // existed yet
                         }
                     }
                 });

        return waitResult.completed();
    }

    public interface RunnerConfiguration {

        public RunnerConfiguration logOutput(Logger log, String marker);

        public RunnerConfiguration setDrainTarget(DrainTarget drainTarget);

        public int runAndWait();

        public Process run();
    }

    public static List<String> executeCommandLine(String command) {
        try {

            List<String> stringList = new ArrayList<String>();

            ProcessHelper.RunnerConfiguration runner = ProcessHelper
                .newProcess(command);

            runner.setDrainTarget(DrainTargets.stringCollector(stringList));
            runner.runAndWait();

            return stringList;

        } catch (Exception e) {
            log.error("cannot execute command line " + e.toString());
        }

        return Collections.emptyList();
    }
}
