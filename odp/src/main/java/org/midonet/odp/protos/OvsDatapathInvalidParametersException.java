/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.odp.protos;

import org.midonet.netlink.exceptions.NetlinkException;

public class OvsDatapathInvalidParametersException extends NetlinkException {

    static final long serialVersionUID = 1L;

    public OvsDatapathInvalidParametersException(String message) {
        super(-1, message);
    }
}
