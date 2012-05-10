/*
 * Copyright 2012 Midokura Pte. Ltd.
 */
package com.midokura.util.process;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import static java.lang.String.format;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.remote.RemoteHost;
import com.midokura.tools.timed.Timed;
import static com.midokura.util.process.ProcessOutputDrainer.DrainTarget;

/**
 * Class that takes care of launching processes on local/remote connections,
 * monitors then, executes them using sudo support, etc.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 11/24/11
 */
public class ProcessHelper {

    private static final Logger log =
        LoggerFactory.getLogger(ProcessHelper.class);

    public static RunnerConfiguration newDemonProcess(final String commandLine)
    {
        RunnerConfiguration configuration = _newProcess(commandLine, true);

        configuration.setDrainTarget(DrainTargets.noneTarget());

        return configuration;
    }

    public static RunnerConfiguration newLocalProcess(final String commandLine)
    {
       return _newProcess(commandLine, false);
    }

    public static RunnerConfiguration newProcess(final String commandLine) {
        return _newProcess(commandLine, true);
    }

    private static RunnerConfiguration _newProcess(final String commandLine,
                                                   final boolean canBeRemote) {
        return new RunnerConfiguration() {
            Logger logger;
            String stdMarker;
            DrainTarget drainTarget;
            EnumSet<OutputStreams> streamsToLog =
                EnumSet.noneOf(OutputStreams.class);

            String processCommandLine = commandLine;

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
                Process p = createProcess(true, canBeRemote);

                String processName = getProcessName(processCommandLine,
                                                    canBeRemote);
                try {
                    if (p != null) {
                        p.waitFor();

                        log.debug("Process \"{}\" exited with code: {}",
                                  processName, p.exitValue());
                        return p.exitValue();
                    }
                } catch (InterruptedException e) {
                    log.error(
                        format("Error while launching command: \"%s\"",
                               processName), e);
                }

                return -1;
            }

            public Process run() {
                return createProcess(false, canBeRemote);
            }

            @Override
            public RunnerConfiguration withSudo() {
                processCommandLine = "sudo " + processCommandLine;
                return this;
            }

            private Process createProcess(boolean wait,
                                          boolean canBeExecutedRemote) {
                try {
                    Process p = launchProcess(canBeExecutedRemote);
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
                    log.error("Error while executing command: \"{}\"",
                              commandLine, e);
                }

                return null;
            }

            private Process launchProcess(boolean canBeExecutedRemote)
                throws IOException {
                RemoteHost remoteHostSpec = RemoteHost.getSpecification();

                // if the remoteHostSpec is not valid it means that remote
                // specification was not defined or defined poorly so we revert
                // to the standard way of running all processes as local processes.
                // the canBeExecutedRemote is a signal that if possbile this
                // process will be executed remotely.
                if (canBeExecutedRemote && remoteHostSpec.isValid()) {
                    return new RemoteSshProcess(remoteHostSpec,
                                             processCommandLine);
                }

                return Runtime.getRuntime().exec(processCommandLine);
            }
        };
    }

    private static String getProcessName(String commandLine,
                                         boolean canBeRemote) {
        if (canBeRemote && RemoteHost.getSpecification().isValid())
            return String.format("[%s] on %s",
                                 commandLine,
                                 RemoteHost.getSpecification().getSafeName());

        return commandLine;
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

        public RunnerConfiguration withSudo();
    }

    public static List<String> executeLocalCommandLine(String commandLine) {
        return _executeCommandLine(commandLine, false);
    }

    public static List<String> executeCommandLine(String commandLine) {
        return _executeCommandLine(commandLine, true);
    }

    private static List<String> _executeCommandLine(String command,
                                                    boolean canBeRemote) {
        try {

            List<String> stringList = new ArrayList<String>();

            RunnerConfiguration runner = _newProcess(command, canBeRemote);

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
