/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.cluster.client;

import java.util.Map;
import java.util.UUID;

import org.midonet.cluster.data.l4lb.PoolMember;

public interface PoolBuilder {
    void setPoolMembers(Map<UUID,PoolMember> poolMembers);
}
