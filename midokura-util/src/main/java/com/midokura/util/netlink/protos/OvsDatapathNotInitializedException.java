/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.netlink.protos;

import com.midokura.util.netlink.exceptions.NetlinkException;

public class OvsDatapathNotInitializedException extends NetlinkException {

    public static final int NOT_INITIALIZED_EXCEPTION_CODE = -1;

    public OvsDatapathNotInitializedException() {
        super(NOT_INITIALIZED_EXCEPTION_CODE);
    }
}
