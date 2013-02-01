/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.odp.protos;

import org.midonet.netlink.exceptions.NetlinkException;

public class OvsDatapathInvalidParametersException extends NetlinkException {

    public OvsDatapathInvalidParametersException(String message) {
        super(-1, message);
    }
}
