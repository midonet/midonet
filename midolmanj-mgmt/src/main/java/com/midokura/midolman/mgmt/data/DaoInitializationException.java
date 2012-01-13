/*
 * @(#)DaoInitializationException        1.6 11/11/23
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data;

/**
 * Exception to indicate that DAO could not be initialized.
 *
 * @version 1.6 23 Nov 2011
 * @author Ryu Ishimoto
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