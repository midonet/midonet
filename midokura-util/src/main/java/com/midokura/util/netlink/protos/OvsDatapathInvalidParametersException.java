/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.netlink.protos;

import com.midokura.util.netlink.exceptions.NetlinkException;

public class OvsDatapathInvalidParametersException extends NetlinkException {

    public OvsDatapathInvalidParametersException(String message) {
        super(-1, message);
    }
}
