/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.config;

/**
 * Exception class that represents bad configuration.
 */
public class InvalidConfigException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidConfigException(String message) {
        super(message);
    }

    public InvalidConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}