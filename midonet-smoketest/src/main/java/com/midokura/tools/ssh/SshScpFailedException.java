package com.midokura.tools.ssh;

/**
 * Author: Toader Mihai Claudiu <mtoader@midokura.com>
 * <p/>
 * Date: 12/14/11
 * Time: 5:01 PM
 */
public class SshScpFailedException extends RuntimeException {
    public SshScpFailedException() {
    }

    public SshScpFailedException(String message) {
        super(message);
    }

    public SshScpFailedException(String message, Throwable cause) {
        super(message, cause);
    }

    public SshScpFailedException(Throwable cause) {
        super(cause);
    }
}
