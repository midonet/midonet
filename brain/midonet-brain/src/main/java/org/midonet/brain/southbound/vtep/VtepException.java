/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.brain.southbound.vtep;

/**
 * A checked exception for a VTEP data vtep.
 */
public class VtepException extends Exception {

    private static final long serialVersionUID = -7802562175020274399L;
    public final VtepEndPoint vtep;

    public VtepException(VtepEndPoint vtep) {
        this.vtep = vtep;
    }

    public VtepException(VtepEndPoint vtep, String message) {
        super(message);
        this.vtep = vtep;
    }

    public VtepException(VtepEndPoint vtep, String message,
                         Throwable throwable) {
        super(message, throwable);
        this.vtep = vtep;
    }

    public VtepException(VtepEndPoint vtep, Throwable throwable) {
        super(throwable);
        this.vtep = vtep;
    }

}
