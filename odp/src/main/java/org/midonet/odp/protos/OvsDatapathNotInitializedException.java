/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.odp.protos;

import org.midonet.netlink.exceptions.NetlinkException;

public class OvsDatapathNotInitializedException extends NetlinkException {

    static final long serialVersionUID = 1L;

    public OvsDatapathNotInitializedException() {
        super(ErrorCode.E_NOT_INITIALIZED);
    }
}
