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

// Wrapper for managing sudo shelling.
package org.midonet.midolman.util;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Sudo {
    static final Logger log = LoggerFactory.getLogger(Sudo.class);

    static final boolean hasControllingTTY = checkForControllingTTY();

    private static boolean checkForControllingTTY() {
        boolean _hasControllingTTY = false;

        try {
            // just try to open /dev/tty as a stream
            FileInputStream fileInputStream = new FileInputStream("/dev/tty");
            fileInputStream.close();

            // since nothing bad happened until here we know that we can
            // open the /dev/tty device (which implies that we have a
            // controlling terminal
            _hasControllingTTY = true;
        } catch (FileNotFoundException ex) {
            // when there is no controlling TTY this exception will be thrown.
        } catch (Exception e) {
            log.error("Exception while testing for controlling TTY.", e);
        }

        log.info("Controlling TTY check status: {}", _hasControllingTTY);
        return _hasControllingTTY;
    }

    public static int sudoExec(String command)
        throws IOException, InterruptedException {
        log.info("Running \"{}\" with sudo", command);

        Process p;

        if (hasControllingTTY) {
            p = new ProcessBuilder("sh", "-c",
                                   "sudo -n " + command + " </dev/tty").start();
        } else {
            p = Runtime.getRuntime().exec("sudo -n " + command);
        }
        p.waitFor();

        byte[] output = new byte[10240];
        int outputLen = p.getErrorStream().read(output);
        if (outputLen > 0) {
            log.error("sudo error output: {}",
                      new String(output, 0, outputLen));
        }

        outputLen = p.getInputStream().read(output);
        if (outputLen > 0) {
            log.info("sudo standard output: {}",
                     new String(output, 0, outputLen));
        }


        return p.exitValue();
    }
}
