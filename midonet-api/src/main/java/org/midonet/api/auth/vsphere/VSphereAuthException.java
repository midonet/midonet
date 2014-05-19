/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.api.auth.vsphere;

import org.midonet.api.auth.AuthException;

public class VSphereAuthException extends AuthException {

    public VSphereAuthException(String message) {
        super(message);
    }

    public VSphereAuthException(Throwable e) {
        super(e);
    }

    public VSphereAuthException(String message, Throwable e) {
        super(message, e);
    }
}
