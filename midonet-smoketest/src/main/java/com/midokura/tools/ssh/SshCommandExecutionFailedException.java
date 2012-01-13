/*
* Copyright 2011 Midokura Europe SARL
*/
package com.midokura.tools.ssh;

/**
 * Author: Toader Mihai Claudiu <mtoader@gmail.com>
 * <p/>
 * Date: 11/24/11
 * Time: 5:47 PM
 */
public class SshCommandExecutionFailedException extends RuntimeException {
    public SshCommandExecutionFailedException() {
    }

    public SshCommandExecutionFailedException(String message) {
        super(message);
    }

    public SshCommandExecutionFailedException(String message, Throwable cause) {
        super(message, cause);
    }

    public SshCommandExecutionFailedException(Throwable cause) {
        super(cause);
    }
}
