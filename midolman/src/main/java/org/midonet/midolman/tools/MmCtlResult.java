/*
* Copyright 2013 Midokura Europe SARL
*/
package org.midonet.midolman.tools;

/**
 * Interface for mm-ctl command result.
 */
public class MmCtlResult {

    private final int exitCode;
    private final String message;

    public MmCtlResult(int exitCode, String message) {
        this.exitCode = exitCode;
        this.message = message;
    }

    /**
     * The exit code from the command
     */
    public int getExitCode() {
        return this.exitCode;
    }

    /**
     * True if the command was a success.
     */
    public boolean isSuccess() {
        return getExitCode() == 0;
    }

    /**
     * Message from the command.
     */
    public String getMessage() {
        return this.message;
    }
}
