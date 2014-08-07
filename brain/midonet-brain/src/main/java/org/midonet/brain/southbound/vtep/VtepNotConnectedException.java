/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.brain.southbound.vtep;

/**
 * A checked exception for an unconnected VTEP data vtep.
 */
public class VtepNotConnectedException extends VtepException {

    private static final long serialVersionUID = 2817256740296080692L;

    public VtepNotConnectedException(VtepEndPoint vtep) {
        super(vtep);
    }

    public VtepNotConnectedException(VtepEndPoint vtep, String message) {
        super(vtep, message);
    }

    public VtepNotConnectedException(VtepEndPoint vtep, String message,
                                     Throwable throwable) {
        super(vtep, message, throwable);
    }

    public VtepNotConnectedException(VtepEndPoint vtep,
                                     Throwable throwable) {
        super(vtep, throwable);
    }

}
