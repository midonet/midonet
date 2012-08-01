/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.netlink.protos;

import com.midokura.netlink.exceptions.NetlinkException;

public class OvsDatapathNotInitializedException extends NetlinkException {

    public OvsDatapathNotInitializedException() {
        super(ErrorCode.E_NOT_INITIALIZED);
    }
}
