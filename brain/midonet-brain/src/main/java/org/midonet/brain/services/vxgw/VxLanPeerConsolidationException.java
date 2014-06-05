/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.brain.services.vxgw;

/**
 * An update to a VxLanPeer could not be applied.
 */
public class VxLanPeerConsolidationException extends RuntimeException {
    private static final long serialVersionUID = -1;
    private final String lsName;

    public VxLanPeerConsolidationException(String msg, String lsName) {
        super(msg);
        this.lsName = lsName;
    }

    @Override
    public String toString() {
        return String.format("Failed to consolidate %s, %s",
                             lsName, getMessage());
    }
}
