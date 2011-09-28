/*
 * @(#)RuleIndexOutOfBoundsException        1.6 11/09/27
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.state;

/**
 * Exception class to indicate the Rule's position index is out of bounds.
 * 
 * @version 1.6 27 Sept 2011
 * @author Ryu Ishimoto
 */
public class RuleIndexOutOfBoundsException extends
        InvalidStateOperationException {
    private static final long serialVersionUID = 1L;

    /**
     * Default constructor
     */
    public RuleIndexOutOfBoundsException() {
        super();
    }

    public RuleIndexOutOfBoundsException(String message) {
        super(message);
    }

    public RuleIndexOutOfBoundsException(String message, Throwable cause) {
        super(message, cause);
    }

}
