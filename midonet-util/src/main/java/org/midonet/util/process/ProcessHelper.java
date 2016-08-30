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

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.midonet.util.process.ProcessOutputDrainer.DrainTarget;

/**
 * Class that takes care of launching processes on local/remote connections,
 * monitors then, executes them using sudo support, etc.
 */
public class ProcessHelper {

    private static final Logger log =
        LoggerFactory.getLogger(ProcessHelper.class);

    public static RunnerConfiguration newDaemonProcess(String commandLine) {
        RunnerConfiguration configuration = newProcess(commandLine);

        configuration.setDrainTarget(DrainTargets.noneTarget());

        return configuration;
    }

    public static RunnerConfiguration newDaemonProcess(
             @Nonnull String commandLine,
             @Nonnull Logger logger,
             @Nonnull String prefix) {
        RunnerConfiguration configuration = newProcess(commandLine);

        configuration.setDrainTarget(DrainTargets.slf4jTarget(logger, prefix));

        return configuration;
    }

    public static RunnerConfiguration newProcess(final String commandLine) {

        return new RunnerConfiguration() {
            DrainTarget drainTarget;
            String procCommandLine = commandLine;
            Map<String, String> envVars = new HashMap<>();

            EnumSet<OutputStreams> streamsToLog =
                EnumSet.allOf(OutputStreams.class);

            @Override
            public RunnerConfiguration logOutput(Logger log, String marker,
                                                 OutputStreams... streams) {
                streamsToLog.clear();

                if (streams.length == 0) {
                    streamsToLog = EnumSet.of(OutputStreams.StdOutput);
                } else {
                    streamsToLog = EnumSet.noneOf(OutputStreams.class);
                    Collections.addAll(streamsToLog, streams);
                }

                drainTarget = DrainTargets.slf4jTarget(log, marker);
                return this;
            }

            public RunnerConfiguration setDrainTarget(DrainTarget drainTarget) {
                this.drainTarget = drainTarget;
                return this;
            }

            @Override
            public RunnerConfiguration setEnvVariables(
                Map<String, String> vars) {

                this.envVars.putAll(vars);
                return this;
            }

            @Override
            public RunnerConfiguration setEnvVariable(String var,
                                                      String value) {
                this.envVars.put(var, value);
                return this;
            }

            @Override
            public int runAndWait() {
                Process p = createProcess(true);

                String processName = procCommandLine;

                try {
                    if (p != null) {
                        p.waitFor();

                        IOUtils.closeQuietly(p.getInputStream());
                        IOUtils.closeQuietly(p.getErrorStream());
                        IOUtils.closeQuietly(p.getOutputStream());

                        if (p.exitValue() == 0) {
                            log.trace("Process \"{}\" exited with code: {}",
                                    processName, p.exitValue());

                        } else {
                            log.debug("Process \"{}\" exited with non zero code: {}",
                                    processName, p.exitValue());
                        }
                        return p.exitValue();
                    }
                } catch (InterruptedException e) {
                    log.error(
                        String.format("Error while launching command: \"%s\"",
                               processName), e);
                }

                return -1;
            }

            public Process run() {
                return createProcess(false);
            }

            @Override
            public RunnerConfiguration withSudo() {
                procCommandLine = "sudo " + procCommandLine;
                return this;
            }

            private Process createProcess(boolean wait) {
                try {
                    Process p = launchProcess();
                    if (drainTarget == null) {
                        drainTarget = DrainTargets.noneTarget();
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

            private Process launchProcess() throws IOException {

                if (envVars.isEmpty()) {
                    return Runtime.getRuntime().exec(procCommandLine);
                } else {
                    List<String> param = new ArrayList<>();
                    for (Map.Entry<String, String> var : envVars.entrySet()) {
                        param.add(var.getKey() + "=" + var.getValue());
                    }
                    return
                        Runtime.getRuntime()
                               .exec(procCommandLine,
                                     param.toArray(new String[param.size()]));

                }
            }
        };
    }

    public static int getProcessPid(Process process) {
        Field field;
        try {
            field = process.getClass().getDeclaredField("pid");
            field.setAccessible(true);
            Object o = field.get(process);
            if (o instanceof Integer) {
                return Integer.class.cast(o);
            }

            return -1;
        } catch (NoSuchFieldException e) {
            return -1;
        } catch (IllegalAccessException e) {
            return -1;
        }
    }

    public interface RunnerConfiguration {

        RunnerConfiguration logOutput(Logger log, String marker,
                                      OutputStreams... streams);

        RunnerConfiguration setDrainTarget(DrainTarget drainTarget);

        int runAndWait();

        Process run();

        RunnerConfiguration withSudo();

        RunnerConfiguration setEnvVariables(Map<String, String> vars);

        RunnerConfiguration setEnvVariable(String var, String value);

    }

    public static ProcessResult executeLocalCommandLine(String commandLine) {
        return executeCommandLine(commandLine);
    }

    public static ProcessResult executeCommandLine(String command) {
        return executeCommandLine(command, false);
    }

    public static ProcessResult executeCommandLine(String command, boolean ignoreErrors) {
        ProcessResult result = new ProcessResult();
        List<String> outputList = new ArrayList<>();
        List<String> errorList = new ArrayList<>();

        RunnerConfiguration runner = newProcess(command);

        runner.setDrainTarget(DrainTargets.stringCollector(
                outputList, errorList));

        result.returnValue = runner.runAndWait();
        result.consoleOutput = outputList;
        result.errorOutput = errorList;

        if (errorList.size() != 0 && !ignoreErrors) {
            // TODO: remove once tuntap is out (RHEL)
            if(!errorList.get(0).contains("tuntap")) {
                log.warn("Process \"{}\" generated errors:", command);
                for (String s : errorList) log.warn(s);
            }
        }

        return result;
    }

    public static class ProcessResult {
        public List<String> consoleOutput;
        public List<String> errorOutput;
        public int returnValue;

        public ProcessResult() {
            this.returnValue = 0;
            this.consoleOutput = Collections.emptyList();
            this.errorOutput = Collections.emptyList();
        }
    }

    public enum OutputStreams {
        StdOutput, StdError
    }
}
