/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midonet.cluster.client;

import java.util.UUID;

import com.midokura.midonet.cluster.data.AdRoute;
import com.midokura.midonet.cluster.data.BGP;

public interface BGPListBuilder {
    void addBGP(BGP bgp);
    void removeBGP(UUID bgpID);
    void addAdvertisedRoute(AdRoute adRoute);
    void removeAdvertisedRoute(AdRoute adRoute);
    void updateBGP(BGP bgp);
}
