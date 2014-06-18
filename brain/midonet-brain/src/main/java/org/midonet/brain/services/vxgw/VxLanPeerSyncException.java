/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.brain.services.vxgw;

/**
 * An update to a VxLanPeer could not be applied.
 */
public class VxLanPeerSyncException extends RuntimeException {
    private static final long serialVersionUID = -1;
    private final MacLocation change;

    public VxLanPeerSyncException(String msg, MacLocation change) {
        super(msg);
        this.change = change;
    }

    public VxLanPeerSyncException(String msg, MacLocation change,
                                  Throwable cause) {
        super(msg, cause);
        this.change = change;
    }

    @Override
    public String toString() {
        return String.format("Failed to apply %s, %s",
                             change.toString(), this.getMessage());
    }
}
