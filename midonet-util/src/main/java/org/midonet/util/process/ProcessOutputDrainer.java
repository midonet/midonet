/*
 * Copyright 2014 Midokura SARL
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
import java.io.InputStream;

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.LineIterator;

/**
 * It is a class that will spawn one or two threads that continuously pool
 * a process stdout/stderr streams and pass each new line they get to
 * a provided {@link DrainTarget} object.
 *
 * Used by the {@link ProcessHelper} class to make sure a launched process
 * is not stalled when the stdout buffer is full.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 11/20/11
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

        Thread stdoutThread =
            new Thread(
                new InputStreamDrainer(process.getInputStream(),
                                       drainTarget, false));
        stdoutThread.start();

        Thread stderrThread = null;
        if (separateErrorStream) {
            stderrThread =
                new Thread(
                    new InputStreamDrainer(process.getErrorStream(),
                                           drainTarget, true));
            stderrThread.start();
        }
        if (wait) {
            try {
                stdoutThread.join();
                if (stderrThread != null) {
                    stderrThread.join();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public interface DrainTarget {

        /**
         * Called when a line was printed on a stdout device.
         *
         * @param line the printed line
         */
        public void outLine(String line);

        /**
         * Called when a line was printed on a stderr device.
         *
         * @param line the printed line
         */
        public void errLine(String line);

    }

    private class InputStreamDrainer implements Runnable {

        private InputStream inputStream;
        private DrainTarget drainTarget;
        private boolean drainStdError;

        public InputStreamDrainer(InputStream inputStream,
                                  DrainTarget drainTarget,
                                  boolean drainStdError) {
            this.inputStream = inputStream;
            this.drainTarget = drainTarget;
            this.drainStdError = drainStdError;
        }

        @Override
        public void run() {
            try {
                LineIterator lineIterator =
                    IOUtils.lineIterator(inputStream, "UTF-8");

                while (lineIterator.hasNext()) {
                    String line = lineIterator.nextLine();

                    if (drainStdError) {
                        drainTarget.errLine(line);
                    } else {
                        drainTarget.outLine(line);
                    }
                }
            } catch (IllegalStateException ex) {
                Throwable cause = ex.getCause();
                if (cause != null
                    && cause instanceof IOException
                    && cause.getMessage().equals("Stream closed")) {
                    // we ignore an IllegalStateException caused by a
                    // IOException caused by a stream close
                    // because this usually happens when the watched process is
                    // destroyed forcibly (and we tend to that).
                } else {
                    throw ex;
                }
            } catch (IOException e) {
                // catch and ignore the output. Normally this happens when the
                // reading input stream (which is connected to a process) is
                // closed which usually means that the process died or it was
                // killed. So we bail the loop and end the draining thread.
            }
        }
    }
}
