/*
 * @(#)MalformedPacketException        1.6 12/01/17
 *
 * Copyright 2012 Midokura KK
 */
package org.midonet.packets;

public class MalformedPacketException extends Exception {

    private static final long serialVersionUID = 1L;

    public MalformedPacketException(String message) {
        super(message);
    }

    public MalformedPacketException(String message, Throwable cause) {
        super(message, cause);
    }

}
