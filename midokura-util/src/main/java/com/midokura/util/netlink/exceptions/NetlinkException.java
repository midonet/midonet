/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.netlink.exceptions;

public class NetlinkException extends Exception {

    int errorCode;

    public static final int ERROR_SENDING_REQUEST = -1;

    public NetlinkException(int errorCode) {
        this.errorCode = errorCode;
    }

    public NetlinkException(int errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public NetlinkException(int errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public NetlinkException(int errorCode, Throwable cause) {
        super(cause);
        this.errorCode = errorCode;
    }

    public int getErrorCode() {
        return errorCode;
    }
}

