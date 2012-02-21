/*
 * Copyright 2012 Midokura Pte. Ltd.
 */
package com.midokura.util.process;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

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
                try {
                    Process p = Runtime.getRuntime().exec(commandLine);
                    if (drainTarget == null) {
                        drainTarget = logger == null
                            ? DrainTargets.noneTarget()
                            : DrainTargets.slf4jTarget(logger, stdMarker);
                    }

                    new ProcessOutputDrainer(p).drainOutput(drainTarget);
                    p.waitFor();

                    log.debug("Process \"{}\" exited with code: {}",
                              commandLine, p.exitValue());
                    return p.exitValue();

                } catch (IOException e) {
                    log.error(
                        String.format("Error while launching command: \"%s\"",
                                      commandLine), e);
                } catch (InterruptedException e) {
                    log.error(
                        String.format("Error while launching command: \"%s\"",
                                      commandLine), e);
                }

                return -1;
            }
        };
    }

    public interface RunnerConfiguration {

        public RunnerConfiguration logOutput(Logger log, String marker);

        public RunnerConfiguration setDrainTarget(DrainTarget drainTarget);

        public int runAndWait();
    }
}
