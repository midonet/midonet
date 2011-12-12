// Copyright 2011 Midokura Inc.

// Wrapper for managing sudo shelling.

package com.midokura.midolman.util;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class Sudo {
    static final Logger log = LoggerFactory.getLogger(Sudo.class);

    public static int sudoExec(String command)
            throws IOException, InterruptedException {
                log.info("Running \"{}\" with sudo", command);
        Process p = new ProcessBuilder("sh", "-c",
                        "sudo -n " + command + " < /dev/tty").start();
        p.waitFor();
        int returnValue = p.exitValue();
        byte[] cmdOutput = new byte[10000];
        byte[] cmdErrOutput = new byte[10000];
        int errOutputLength = p.getErrorStream().read(cmdErrOutput);
        int outputLength = p.getInputStream().read(cmdOutput);
        if (errOutputLength > 0)
            log.error("sudo error output: {}",
                      new String(cmdErrOutput, 0, errOutputLength));
        if (outputLength > 0)
            log.info("sudo standard output: {}",
                     new String(cmdOutput, 0, outputLength));
        return returnValue;
    }
}
