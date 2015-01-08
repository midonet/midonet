/*
 * Copyright 2015 Midokura SARL
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
package org.midonet.midolman;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Timer;
import java.util.TimerTask;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WatchedProcess {

    static final Logger log = LoggerFactory.getLogger("org.midonet.midolman.watchdog");

    private volatile boolean running = false;
    private int intervalMillis = 0;
    private Path pipePath = null;
    private FileChannel pipe = null;
    private Timer timer = null;

    private Thread shutdownHook = new Thread() {
        @Override
        public void run() {
            log.info("Stopping watchdog thread");
            timer.cancel();
            running = false;
        }
    };

    private TimerTask tick = new TimerTask() {
        private ByteBuffer buf = ByteBuffer.allocate(1);
        {
            buf.put((byte) 57);
            buf.flip();
        }

        @Override
        public void run() {
            try {
                log.debug("Writing to watchdog pipe");
                if (running) {
                    buf.position(0);
                    pipe.write(buf);
                }
            } catch (IOException e) {
                log.warn("Could not write to watchdog pipe", e);
            }
        }
    };

    private boolean readConfig() {
        String pipeStr = System.getenv("WDOG_PIPE");
        if (pipeStr == null) {
            log.info("Disabling watchdog: WDOG_PIPE environment var is not set");
            return false;
        }
        String timeoutStr = System.getenv("WDOG_TIMEOUT");
        if (timeoutStr == null) {
            log.warn("Disabling watchdog: WDOG_TIMEOUT environment var is not set");
            return false;
        }

        try {
            int timeout = Integer.valueOf(timeoutStr);
            intervalMillis = timeout * 1000 / 2;
        } catch (NumberFormatException e) {
            log.warn("Disabling watchdog: Invalid WDOG_TIMEOUT value: {}", timeoutStr);
            return false;
        }

        if (intervalMillis < 1) {
            log.warn("Disabling watchdog: Invalid WDOG_TIMEOUT value: {}", timeoutStr);
            return false;
        }

        pipePath = FileSystems.getDefault().getPath(pipeStr);

        return true;
    }

    private boolean openPipe() {
        try {
            pipe = FileChannel.open(pipePath, StandardOpenOption.WRITE);
            log.debug("Opened pipe to watchdog process at {}", pipePath);
            return true;
        } catch (IOException e) {
            log.warn("Failed to open pipe at " + pipePath, e);
            return false;
        }
    }

    public void start() {
        if (running)
            return;
        if (!readConfig())
            return;
        if (!openPipe())
            return;

        log.info("Starting watchdog thread");
        running = true;
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        timer = new Timer("watchdog", true);
        timer.schedule(tick, 0, intervalMillis);
    }

}
