/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.brain.services.vxgw;

import rx.Observable;

/**
 * A VxLanPeer is able to a) apply the relevant configuration changes in
 * the underlying device to reflect the given MacLocation; b) provide an
 * rx.Observable stream of MacLocation updates to apply in its peer.
 */
public interface VxLanPeer {

    /**
     * Apply a change in the location of a remote MAC in the VxLanPeer.
     */
    public void apply(MacLocation macLocation);

    /**
     * Provide an observable on MacLocation changes local to the VxLanPeer.
     */
    public Observable<MacLocation> observableUpdates();
}

