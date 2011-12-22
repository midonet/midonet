// Copyright 2011 Midokura Inc.

// Wrapper for managing sudo shelling.
package com.midokura.midolman.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Sudo {
    static final Logger log = LoggerFactory.getLogger(Sudo.class);

    static final boolean hasControllingTTY = checkForControllingTTY();

    private static boolean checkForControllingTTY() {
        boolean _hasControllingTTY = false;

        try {
            File ttyFile = new File("/dev/tty");
            if (ttyFile.exists() && ttyFile.canRead()) {
                // just try to open the file;
                FileInputStream fileInputStream = new FileInputStream(ttyFile);
                fileInputStream.close();

                // since nothing bad happened until here we know that we can
                // open the /dev/tty device (which implies that we have a
                // controlling terminal
                _hasControllingTTY = true;
            }
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
