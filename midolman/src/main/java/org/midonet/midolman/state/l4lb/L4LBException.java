/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.midolman.state.l4lb;

/**
 * An common exception among the L4LB related exception
 */
public class L4LBException extends Exception {
    private static final long serialVersionUID = 1L;

    public L4LBException() {
        super();
    }

    public L4LBException(String message) {
        super(message);
    }

    public L4LBException(String message, Throwable cause) {
        super(message, cause);
    }

    public L4LBException(Throwable cause) {
        super(cause);
    }
}
