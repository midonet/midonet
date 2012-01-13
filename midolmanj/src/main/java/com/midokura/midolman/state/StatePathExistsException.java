package com.midokura.midolman.state;

public class StatePathExistsException extends StateAccessException {
    private static final long serialVersionUID = 1L;

    /**
     * Default constructor
     */
    public StatePathExistsException() {
        super();
    }

    public StatePathExistsException(String message) {
        super(message);
    }

    public StatePathExistsException(String message, Throwable cause) {
        super(message, cause);
    }
}
