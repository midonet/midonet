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
    Pool createPool(Pool pool)
        throws StateAccessException, SerializationException;

    List<Pool> createPoolBulk(List<Pool> pool)
        throws StateAccessException, SerializationException;

    Pool getPool(UUID id) throws StateAccessException, SerializationException;

    List<Pool> getPools() throws StateAccessException, SerializationException;

    Pool updatePool(UUID id, Pool pool)
        throws StateAccessException, SerializationException;

    Pool deletePool(UUID id)
        throws StateAccessException, SerializationException;

    // Members
    Member createMember(Member member)
        throws StateAccessException, SerializationException;

    List<Member> createMemberBulk(List<Member> member)
        throws StateAccessException, SerializationException;

    Member getMember(UUID id)
        throws StateAccessException, SerializationException;

    List<Member> getMembers()
        throws StateAccessException, SerializationException;

    Member updateMember(UUID id, Member member)
        throws StateAccessException, SerializationException;

    Member deleteMember(UUID id)
        throws StateAccessException, SerializationException;

    // Vips
    VIP createVip(VIP vip)
        throws StateAccessException, SerializationException;

    List<VIP> createVipBulk(List<VIP> vip)
        throws StateAccessException, SerializationException;

    VIP getVip(UUID id) throws StateAccessException, SerializationException;

    List<VIP> getVips() throws StateAccessException, SerializationException;

    VIP updateVip(UUID id, VIP vip)
        throws StateAccessException, SerializationException;

    VIP deleteVip(UUID id) throws StateAccessException, SerializationException;

    // Health Monitors
    HealthMonitor createHealthMonitor(HealthMonitor healthMonitor)
        throws StateAccessException, SerializationException;

    List<HealthMonitor> createHealthMonitorBulk(
        List<HealthMonitor> healthMonitor)
        throws StateAccessException, SerializationException;

    HealthMonitor getHealthMonitor(UUID id)
        throws StateAccessException, SerializationException;

    List<HealthMonitor> getHealthMonitors()
        throws StateAccessException, SerializationException;

    HealthMonitor updateHealthMonitor(UUID id, HealthMonitor healthMonitor)
        throws StateAccessException, SerializationException;

    HealthMonitor deleteHealthMonitor(UUID id)
        throws StateAccessException, SerializationException;

    PoolHealthMonitor createPoolHealthMonitor(
        PoolHealthMonitor poolHealthMonitor)
        throws StateAccessException, SerializationException;

    PoolHealthMonitor deletePoolHealthMonitor(
        PoolHealthMonitor poolHealthMonitor)
        throws StateAccessException, SerializationException;
}
