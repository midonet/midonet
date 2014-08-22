/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.neutron;

import java.util.List;
import java.util.UUID;

import org.midonet.cluster.data.neutron.loadbalancer.HealthMonitor;
import org.midonet.cluster.data.neutron.loadbalancer.Member;
import org.midonet.cluster.data.neutron.loadbalancer.Pool;
import org.midonet.cluster.data.neutron.loadbalancer.PoolHealthMonitor;
import org.midonet.cluster.data.neutron.loadbalancer.VIP;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;

public interface LoadBalancerApi {

    // Pools
    Pool getPool(UUID id)
        throws StateAccessException, SerializationException;

    List<Pool> getPools() throws StateAccessException, SerializationException;

    void createPool(Pool pool)
        throws StateAccessException, SerializationException;

    void updatePool(UUID id, Pool pool)
        throws StateAccessException, SerializationException;

    void deletePool(UUID id)
        throws StateAccessException, SerializationException;

    // Members
    Member getMember(UUID id)
        throws StateAccessException, SerializationException;

    List<Member> getMembers()
        throws StateAccessException, SerializationException;

    void createMember(Member member)
        throws StateAccessException, SerializationException;

    void updateMember(UUID id, Member member)
        throws StateAccessException, SerializationException;

    void deleteMember(UUID id)
        throws StateAccessException, SerializationException;

    // Vips
    VIP getVip(UUID id)
        throws StateAccessException, SerializationException;

    List<VIP> getVips() throws StateAccessException, SerializationException;

    void createVip(VIP vip)
        throws StateAccessException, SerializationException;

    void updateVip(UUID id, VIP vip)
        throws StateAccessException, SerializationException;

    void deleteVip(UUID id) throws StateAccessException, SerializationException;

    // Health Monitors
    HealthMonitor getHealthMonitor(UUID id)
        throws StateAccessException, SerializationException;

    List<HealthMonitor> getHealthMonitors()
        throws StateAccessException, SerializationException;

    void createHealthMonitor(HealthMonitor healthMonitor)
        throws StateAccessException, SerializationException;

    void updateHealthMonitor(UUID id, HealthMonitor healthMonitor)
        throws StateAccessException, SerializationException;

    void deleteHealthMonitor(UUID id)
        throws StateAccessException, SerializationException;

    // Pool Health Monitors
    void createPoolHealthMonitor(UUID poolId,
                                 PoolHealthMonitor poolHealthMonitor)
        throws StateAccessException, SerializationException;

    void deletePoolHealthMonitor(UUID poolId, UUID hmId)
        throws StateAccessException, SerializationException;
}
