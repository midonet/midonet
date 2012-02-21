/*
 * Copyright 2012 Midokura Pte. Ltd.
 */
package com.midokura.midolman.agent.sensor;

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.LineIterator;

import java.io.IOException;
import java.io.InputStream;

/**
 * Author: Toader Mihai Claudiu <mtoader@gmail.com>
 * <p/>
 * Date: 11/20/11
 * Time: 10:09 PM
 */
public class ProcessOutputDrainer {

    private Process process;
    private boolean separateErrorStream;

    public ProcessOutputDrainer(Process process) {
        this(process, false);
    }

    public ProcessOutputDrainer(Process process, boolean separateErrorStream) {
        this.process = process;
        this.separateErrorStream = separateErrorStream;
    }

    public void drainOutput(DrainTarget drainTarget) {
        drainOutput(drainTarget, true);
    }

    public void drainOutput(DrainTarget drainTarget, boolean wait) {

        Thread stdoutThread = new Thread(new InputStreamDrainer(process.getInputStream(), drainTarget, true));
        stdoutThread.start();

        Thread stderrThread = null;

        if ( separateErrorStream ) {
            stderrThread = new Thread(new InputStreamDrainer(process.getErrorStream(), drainTarget, false));
            stderrThread.start();
        }
        if ( wait ) {
            try {
                stdoutThread.join();
                if ( stderrThread != null ) {
                    stderrThread.join();
                }
            } catch (InterruptedException e) {
                //
            }
        }
    }

    public interface DrainTarget {

        public void outLine(String line);

        public void errLine(String line);

    }

    private class InputStreamDrainer implements Runnable {

        private InputStream inputStream;
        private DrainTarget drainTarget;
        private boolean stdoutOrStderr;

        public InputStreamDrainer(InputStream inputStream, DrainTarget drainTarget, boolean stdoutOrStderr) {
            this.inputStream = inputStream;
            this.drainTarget = drainTarget;
            this.stdoutOrStderr = stdoutOrStderr;
        }

        @Override
        public void run() {
            try {
                LineIterator lineIterator = IOUtils.lineIterator(inputStream, "UTF-8");
                while (lineIterator.hasNext()) {
                    String line = lineIterator.nextLine();

                    if (stdoutOrStderr) {
                        drainTarget.outLine(line);
                    } else {
                        drainTarget.errLine(line);
                    }
                }
            } catch (IOException e) {

            }
        }
    }
}
