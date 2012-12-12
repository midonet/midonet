/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.odp.protos;

import com.midokura.netlink.exceptions.NetlinkException;

public class OvsDatapathInvalidParametersException extends NetlinkException {

    public OvsDatapathInvalidParametersException(String message) {
        super(-1, message);
    }
}
