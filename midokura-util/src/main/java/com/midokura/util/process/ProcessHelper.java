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
import java.util.EnumSet;
import java.util.List;

import static java.lang.String.format;

import com.midokura.tools.timed.Timed;
import static com.midokura.util.process.ProcessOutputDrainer.DrainTarget;

/**
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 11/24/11
 */
public class ProcessHelper {

    private static final Logger log = LoggerFactory
        .getLogger(ProcessHelper.class);
    public static final String PROCESS_LAUNCHER_TARGET_HOST = "PROCESS_LAUNCHER_TARGET_HOST";

    public static RunnerConfiguration newProcess(final String commandLine) {
        return new RunnerConfiguration() {
            Logger logger;
            String stdMarker;
            DrainTarget drainTarget;
            EnumSet<OutputStreams> streamsToLog = EnumSet.noneOf(OutputStreams.class);

            @Override
            public RunnerConfiguration logOutput(Logger log, String marker,
                                                 OutputStreams... streams) {
                logger = log;
                stdMarker = marker;
                streamsToLog.clear();

                if (streams.length == 0) {
                    streamsToLog = EnumSet.of(OutputStreams.StdOutput);
                } else {
                    streamsToLog = EnumSet.noneOf(OutputStreams.class);
                    Collections.addAll(streamsToLog, streams);
                }

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
                                  getProcessName(commandLine), p.exitValue());
                        return p.exitValue();
                    }
                } catch (InterruptedException e) {
                    log.error(
                        format("Error while launching command: \"%s\"",
                               getProcessName(commandLine)), e);
                }

                return -1;
            }

            public Process run() {
                return createProcess(false);
            }

            private Process createProcess(boolean wait) {
                try {
                    Process p = launchProcess();
                    if (drainTarget == null) {
                        drainTarget = logger == null
                            ? DrainTargets.noneTarget()
                            : DrainTargets.slf4jTarget(logger, stdMarker);
                    }

                    ProcessOutputDrainer outputDrainer;

                    if (streamsToLog.contains(OutputStreams.StdError)) {
                        outputDrainer = new ProcessOutputDrainer(p, true);
                    } else {
                        outputDrainer = new ProcessOutputDrainer(p);
                    }

                    outputDrainer.drainOutput(drainTarget, wait);

                    return p;
                } catch (IOException e) {
                    log.error(
                        format("Error while launching command: \"%s\"",
                               commandLine), e);
                }

                return null;
            }

            private Process launchProcess() throws IOException {
                String targetHostSpec =
                    System.getProperty(PROCESS_LAUNCHER_TARGET_HOST, "");

                if (!targetHostSpec.trim().equals("")) {
                    return new RemoteSshProcess(targetHostSpec, commandLine);
                } else {
                    return Runtime.getRuntime().exec(commandLine);
                }
            }
        };
    }

    private static String getProcessName(String commandLine) {
        String targetHost = System.getProperty(PROCESS_LAUNCHER_TARGET_HOST, "");
        if (!targetHost.trim().equals("")) {
            return String.format("[%s] on %s", commandLine, targetHost);
        } else {
            return commandLine;
        }
    }

    public static void killProcess(final Process process) {
        // try to kill it naturally. If that fails we will try the hard way.
        process.destroy();

        try {
            // wait to see if the process exists for a couple of seconds
            if (checkForProcessExit(process))
                return;

            log.warn(
                "Process wasn't destroyed by Process.destroy(). We we will " +
                    "try to actually do a kill by hand");

            Field field = process.getClass().getDeclaredField("pid");
            field.setAccessible(true);
            Object o = field.get(process);

            if (o instanceof Integer) {
                int pid = Integer.class.cast(o);

                log.debug("Found pid. Trying kill SIGTEM {}.", pid);

                // try to send a kill -15 signal first.
                newProcess("kill -15 " + pid)
                    .setDrainTarget(DrainTargets.noneTarget())
                    .runAndWait();

                if (!checkForProcessExit(process)) {
                    log.warn("Process didn't exit.  Trying: kill SIGKILL {}.",
                             pid);

                    newProcess("kill -9 " + pid)
                        .setDrainTarget(DrainTargets.noneTarget())
                        .runAndWait();

                    boolean processExited = checkForProcessExit(process);
                    log.debug("Process exit status: {}", processExited);
                }
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
                 .until(5 * 1000)
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

        public RunnerConfiguration logOutput(Logger log, String marker,
                                             OutputStreams... streams);

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

    public enum OutputStreams {
        StdOutput, StdError
    }
}
