/*
 * Copyright 2012 Midokura Pte. Ltd.
 */
package com.midokura.midolman.agent.sensor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;

/**
 * Author: Toader Mihai Claudiu <mtoader@gmail.com>
 * <p/>
 * Date: 11/24/11
 * Time: 1:55 PM
 */
public class ProcessHelper {

    private final static Logger log = LoggerFactory.getLogger(ProcessHelper.class);

    public static RunnerConfiguration newProcess(final String commandLine) {
        return new RunnerConfiguration() {
            Logger logger;
            String stdMarker;
            public ProcessOutputDrainer.DrainTarget drainTarget;

            @Override
            public RunnerConfiguration logOutput(Logger log, String marker) {
                logger = log;
                stdMarker = marker;
                return this;
            }

            @Override
            public int runAndWait() {
                try {
                    Process p = Runtime.getRuntime().exec(commandLine);
                    if (drainTarget == null ) {
                        drainTarget = DrainTargets.slf4jTarget(logger, stdMarker);
                    }

                    new ProcessOutputDrainer(p).drainOutput(drainTarget);
                    p.waitFor();

                    logger.debug("Process \"{}\" exited with code: {}", commandLine, p.exitValue());
                    return p.exitValue();

                } catch (IOException e) {
                    log.error(String.format("Error while launching command: \"%s\"", commandLine), e);
                } catch (InterruptedException e) {
                    log.error(String.format("Error while launching command: \"%s\"", commandLine), e);
                }

                return -1;
            }

            public void setDrainTarget(ProcessOutputDrainer.DrainTarget drainTarget) {
                this.drainTarget = drainTarget;
            }
        };
    }

    public interface RunnerConfiguration {

        public RunnerConfiguration logOutput(Logger log, String marker);

        public void setDrainTarget(ProcessOutputDrainer.DrainTarget drainTarget);

        public int runAndWait();
    }
}
