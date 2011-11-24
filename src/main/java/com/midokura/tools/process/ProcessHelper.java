package com.midokura.tools.process;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
            org.slf4j.Logger logger;
            String stdMarker;

            @Override
            public RunnerConfiguration logOutput(Logger log, String marker) {
                logger = log;
                stdMarker = marker;
                return this;
            }

            @Override
            public void runAndWait() {
                try {
                    Process p = Runtime.getRuntime().exec(commandLine);
                    new ProcessOutputDrainer(p).drainOutput(DrainTargets.slf4jTarget(logger, stdMarker));
                    p.waitFor();

                    logger.debug("Process \"{}\" exited with code: {}", commandLine, p.exitValue());
                } catch (IOException e) {
                    log.error(String.format("Error while launching command: \"%s\"", commandLine), e);
                } catch (InterruptedException e) {
                    log.error(String.format("Error while launching command: \"%s\"", commandLine), e);
                }
            }
        };
    }

    public interface RunnerConfiguration {

        public RunnerConfiguration logOutput(Logger log, String marker);

        public void runAndWait();
    }
}
