/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midonet.cluster.client;

import java.util.UUID;

import com.midokura.midonet.cluster.data.AdRoute;
import com.midokura.midonet.cluster.data.BGP;

public interface BGPListBuilder extends Builder {
    void addBGP(BGP bgp);
    void removeBGP(UUID bgpID);
    void addAdvertisedRoute(AdRoute route);
    void removeAdvertisedRoute(AdRoute route);
    void updateBGP(BGP bgp);
}
