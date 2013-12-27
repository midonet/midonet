/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.cluster.client;

import java.util.Map;
import java.util.UUID;

import org.midonet.cluster.data.l4lb.VIP;

public interface LoadBalancerBuilder extends Builder<LoadBalancerBuilder> {
    void setAdminStateUp(boolean adminStateUp);
    void setVips(Map<UUID,VIP> vips);
}
