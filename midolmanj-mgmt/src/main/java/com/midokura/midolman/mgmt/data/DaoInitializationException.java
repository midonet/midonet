/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data;

/**
 * Exception to indicate that DAO could not be initialized.
 */
public class DaoInitializationException extends Exception {

    private static final long serialVersionUID = -368193890887957370L;

    /**
     * Constructor.
     *
     * @param msg
     *            Error message.
     * @param e
     *            Throwable object.
     */
    public DaoInitializationException(String msg, Throwable e) {
        super(msg, e);
    }
}