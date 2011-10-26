package com.midokura.midolman.mgmt.config;

public class InvalidConfigException extends Exception {

    private static final long serialVersionUID = 1L;

    public InvalidConfigException(String message) {
        super(message);
    }

    public InvalidConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}