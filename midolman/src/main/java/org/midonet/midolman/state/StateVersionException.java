/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.midolman.state;

/**
 * Thrown when a Zookeeper update fails due to a mismatch between the
 * expected and actual node versions due to a concurrent update.
 */
public class StateVersionException extends StateAccessException {

    private static final long serialVersionUID = -7471016483164721122L;

    public StateVersionException(String message, Throwable cause) {
        super(message, cause);
    }
}
