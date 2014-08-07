/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.brain.services.vxgw;

import org.opendaylight.controller.sal.utils.Status;

/**
 * An update to a VxLanPeer could not be applied.
 */
public class VxLanPeerConsolidationException extends Exception {

    private static final long serialVersionUID = 7453713126850041078L;

    public final String lsName;
    public final Status status;

    public VxLanPeerConsolidationException(String message, String lsName) {
        super(message);
        this.lsName = lsName;
        this.status = null;
    }

    public VxLanPeerConsolidationException(String message, String lsName,
                                           Status status) {
        super(message);
        this.lsName = lsName;
        this.status = status;
    }

    @Override
    public String toString() {
        return String.format("Failed to consolidate logical-switch %s with "
                             + "status code %s (%s)",
                             lsName, status, getMessage());
    }
}
