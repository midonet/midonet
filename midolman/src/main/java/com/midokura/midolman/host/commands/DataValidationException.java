/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.host.commands;

import com.midokura.midolman.state.StateAccessException;

/**
 * Exception class that is thrown when a DataValidationException has occurred.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 2/22/12
 */
public class DataValidationException extends StateAccessException {

    public DataValidationException() {
    }

    public DataValidationException(String message) {
        super(message);
    }

    public DataValidationException(String message, Throwable cause) {
        super(message, cause);
    }

    public DataValidationException(Throwable cause) {
        super(cause);
    }
}
