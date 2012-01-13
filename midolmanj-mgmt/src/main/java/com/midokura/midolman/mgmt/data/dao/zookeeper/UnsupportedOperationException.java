/*
 * @(#)UnsupportedOperationException        1.6 11/09/08
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao.zookeeper;

import com.midokura.midolman.state.StateAccessException;

public class UnsupportedOperationException extends StateAccessException {

    private static final long serialVersionUID = 6144720312726540080L;

    public UnsupportedOperationException(String msg) {
        super(msg);
    }
}
