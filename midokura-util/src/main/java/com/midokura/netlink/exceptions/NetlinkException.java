/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.netlink.exceptions;

public class NetlinkException extends Exception {

    int errorCode;

    public static final int ERROR_SENDING_REQUEST = -1;

    public NetlinkException(int errorCode) {
        this.errorCode = errorCode;
    }

    public NetlinkException(int errorCode, String message) {
        super(format(errorCode, message));
        this.errorCode = errorCode;
    }

    public NetlinkException(int errorCode, String message, Throwable cause) {
        super(format(errorCode, message), cause);
        this.errorCode = errorCode;
    }

    public NetlinkException(int errorCode, Throwable cause) {
        super(format(errorCode, null), cause);
        this.errorCode = errorCode;
    }

    public int getErrorCode() {
        return errorCode;
    }

    private static String format(int errorCode, String message) {
        if ( message != null)
            return String.format("[%d] %s", errorCode, message);

        return "error code: " + errorCode;
    }
}

