/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.brain.southbound.vtep;

/**
 * A checked exception for the VTEP vtep state.
 */
public class VtepStateException extends VtepException {

    private static final long serialVersionUID = -29438946408794685L;

    public VtepStateException(VtepEndPoint vtep, String message) {
        super(vtep, message);
    }

    public VtepStateException(VtepEndPoint vtep, String message,
                              Throwable throwable) {
        super(vtep, message, throwable);
    }

    public VtepStateException(VtepEndPoint vtep, Throwable throwable) {
        super(vtep, throwable);
    }

}
