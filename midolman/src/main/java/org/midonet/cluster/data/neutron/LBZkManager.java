/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.neutron;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.google.inject.Inject;

import org.apache.zookeeper.Op;

import org.midonet.cluster.data.l4lb.PoolMember;
import org.midonet.cluster.data.neutron.loadbalancer.HealthMonitor;
import org.midonet.cluster.data.neutron.loadbalancer.Member;
import org.midonet.cluster.data.neutron.loadbalancer.Pool;
import org.midonet.cluster.data.neutron.loadbalancer.PoolHealthMonitor;
import org.midonet.cluster.data.neutron.loadbalancer.VIP;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.BaseZkManager;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;
import org.midonet.midolman.state.zkManagers.HealthMonitorZkManager;
import org.midonet.midolman.state.zkManagers.LoadBalancerZkManager;
import org.midonet.midolman.state.zkManagers.PoolMemberZkManager;
import org.midonet.midolman.state.zkManagers.PoolZkManager;
import org.midonet.midolman.state.zkManagers.RouterZkManager;
import org.midonet.midolman.state.zkManagers.VipZkManager;

public class LBZkManager extends BaseZkManager {

    private final LoadBalancerZkManager loadBalancerZkManager;
    private final PoolZkManager poolZkManager;
    private final HealthMonitorZkManager healthMonitorZkManager;
    private final VipZkManager vipZkManager;
    private final PoolMemberZkManager poolMemberZkManager;
    private final RouterZkManager routerZkManager;

    @Inject
    public LBZkManager(ZkManager zk,
                       PathBuilder paths,
                       Serializer serializer,
                       HealthMonitorZkManager healthMonitorZkManager,
                       LoadBalancerZkManager loadBalancerZkManager,
                       PoolZkManager poolZkManager,
                       PoolMemberZkManager poolMemberZkManager,
                       RouterZkManager routerZkManager,
                       VipZkManager vipZkManager) {
        super(zk, paths, serializer);
        this.healthMonitorZkManager = healthMonitorZkManager;
        this.loadBalancerZkManager = loadBalancerZkManager;
        this.poolZkManager = poolZkManager;
        this.poolMemberZkManager = poolMemberZkManager;
        this.routerZkManager = routerZkManager;
        this.vipZkManager = vipZkManager;
    }

    private UUID ensureLoadBalancerExists(List<Op> ops, UUID routerId)
        throws StateAccessException, SerializationException {
        RouterZkManager.RouterConfig routerConfig
            = routerZkManager.get(routerId);
        if (routerConfig.loadBalancer != null) {
            return routerConfig.loadBalancer;
        }

        LoadBalancerZkManager.LoadBalancerConfig lbConf
            = new LoadBalancerZkManager.LoadBalancerConfig(routerId, true);

        ops.addAll(
            loadBalancerZkManager.prepareCreate(UUID.randomUUID(), lbConf));
        return lbConf.id;
    }

    private void cleanupLoadBalancer(List<Op> ops, UUID loadBalancerId,
                                     UUID poolId)
        throws StateAccessException, SerializationException {
        List<UUID> pools = poolZkManager.getAll();
        for (UUID pool : pools) {
            PoolZkManager.PoolConfig poolConfig = poolZkManager.get(pool);
            if (Objects.equals(loadBalancerId, poolConfig.loadBalancerId) &&
                !Objects.equals(poolId, pool)) {
                // If there is at least one pool that refers to this load
                // balancer, but is not the pool being deleted, then keep the
                // load balancer.
                return;
            }
        }
        ops.addAll(loadBalancerZkManager.prepareDelete(loadBalancerId));
    }

    // Pools
    private void prepareCreateNeutronPool(List<Op> ops, Pool pool)
        throws SerializationException {
        String path = paths.getNeutronPoolPath(pool.id);
        ops.add(zk.getPersistentCreateOp(path, serializer.serialize(pool)));
    }

    public void prepareCreatePool(List<Op> ops, Pool pool)
    throws StateAccessException, SerializationException {
        UUID loadBalancerId = ensureLoadBalancerExists(ops, pool.routerId);
        prepareCreateNeutronPool(ops, pool);
        PoolZkManager.PoolConfig poolConfig
            = new PoolZkManager.PoolConfig(pool, loadBalancerId);
        ops.addAll(poolZkManager.prepareCreate(pool.id, poolConfig));
    }

    private void prepareUpdateNeutronPool(List<Op> ops, Pool pool)
        throws SerializationException {
        String path = paths.getNeutronPoolPath(pool.id);
        ops.add(zk.getSetDataOp(path, serializer.serialize(pool)));
    }

    public void prepareUpdatePool(List<Op> ops, UUID id, Pool pool)
        throws StateAccessException, SerializationException {
        prepareUpdateNeutronPool(ops, pool);
        Pool neutronPool = getNeutronPool(id);
        RouterZkManager.RouterConfig routerConfig
            = routerZkManager.get(neutronPool.routerId);
        PoolZkManager.PoolConfig poolConfig
            = new PoolZkManager.PoolConfig(pool, routerConfig.loadBalancer);
        ops.addAll(poolZkManager.prepareUpdate(id, poolConfig));
    }

    private void prepareDeleteNeutronPool(List<Op> ops, UUID id) {
        String path = paths.getNeutronPoolPath(id);
        ops.add(zk.getDeleteOp(path));
    }

    public void prepareDeletePool(List<Op> ops, UUID id)
        throws StateAccessException, SerializationException {
        prepareDeleteNeutronPool(ops, id);
        PoolZkManager.PoolConfig poolConfig = poolZkManager.get(id);
        cleanupLoadBalancer(ops, poolConfig.loadBalancerId, id);
        ops.addAll(poolZkManager.prepareDelete(id));
    }

    // Members
    private void prepareCreateNeutronMember(List<Op> ops, Member member)
        throws SerializationException {
        String path = paths.getNeutronMemberPath(member.id);
        ops.add(zk.getPersistentCreateOp(path, serializer.serialize(member)));
    }

    private void prepareUpdateNeutronMember(List<Op> ops, Member member)
        throws SerializationException {
        String path = paths.getNeutronMemberPath(member.id);
        ops.add(zk.getSetDataOp(path, serializer.serialize(member)));
    }

    private void prepareDeleteNeutronMember(List<Op> ops, UUID id) {
        String path = paths.getNeutronMemberPath(id);
        ops.add(zk.getDeleteOp(path));
    }

    public void prepareCreateMember(List<Op> ops, Member member)
        throws SerializationException, StateAccessException {
        prepareCreateNeutronMember(ops, member);
        PoolMemberZkManager.PoolMemberConfig poolMemberConfig
            = new PoolMemberZkManager.PoolMemberConfig(member);
        ops.addAll(
            poolMemberZkManager.prepareCreate(member.id, poolMemberConfig));
    }

    public void prepareUpdateMember(List<Op> ops, UUID id, Member member)
        throws StateAccessException, SerializationException {
        prepareUpdateNeutronMember(ops, member);
        PoolMemberZkManager.PoolMemberConfig poolMemberConfig
            = new PoolMemberZkManager.PoolMemberConfig(member);
        ops.addAll(poolMemberZkManager.prepareUpdate(id,poolMemberConfig));
    }

    public void prepareDeleteMember(List<Op> ops, UUID id)
        throws StateAccessException, SerializationException {
        prepareDeleteNeutronMember(ops, id);
        ops.addAll(poolMemberZkManager.prepareDelete(id));
    }

    // Vips
    private void prepareCreateNeutronVip(List<Op> ops, VIP vip)
        throws SerializationException {
        String path = paths.getNeutronVipPath(vip.id);
        ops.add(zk.getPersistentCreateOp(path, serializer.serialize(vip)));
    }

    private Pool getNeutronPool(UUID id)
        throws StateAccessException, SerializationException {
        String path = paths.getNeutronPoolPath(id);
        return serializer.deserialize(zk.get(path), Pool.class);
    }

    private UUID getLoadBalancerIdFromPool(Pool pool)
        throws StateAccessException, SerializationException {
        RouterZkManager.RouterConfig
            routerConfig =
            routerZkManager.get(pool.routerId);
        return routerConfig.loadBalancer;
    }

    private UUID getLoadBalancerIdFromVip(VIP vip)
        throws StateAccessException, SerializationException {
        Pool neutronPool = getNeutronPool(vip.poolId);
        return getLoadBalancerIdFromPool(neutronPool);
    }

    public void prepareCreateVip(List<Op> ops, VIP vip)
        throws SerializationException, StateAccessException {
        UUID loadBalancerId = getLoadBalancerIdFromVip(vip);
        prepareCreateNeutronVip(ops, vip);
        VipZkManager.VipConfig vipConfig
            = new VipZkManager.VipConfig(vip, loadBalancerId);
        ops.addAll(vipZkManager.prepareCreate(vip.id, vipConfig));
    }

    private void prepareUpdateNeutronVip(List<Op> ops, VIP vip)
        throws SerializationException {
        String path = paths.getNeutronVipPath(vip.id);
        ops.add(zk.getSetDataOp(path, serializer.serialize(vip)));
    }

    public void prepareUpdateVip(List<Op> ops, UUID id, VIP vip)
        throws StateAccessException, SerializationException {
        prepareUpdateNeutronVip(ops, vip);
        UUID loadBalancerId = getLoadBalancerIdFromVip(vip);
        VipZkManager.VipConfig vipConfig
            = new VipZkManager.VipConfig(vip, loadBalancerId);
        ops.addAll(vipZkManager.prepareUpdate(id, vipConfig));
    }

    private void prepareDeleteNeutronVip(List<Op> ops, UUID id) {
        String path = paths.getNeutronVipPath(id);
        ops.add(zk.getDeleteOp(path));
    }

    public void prepareDeleteVip(List<Op> ops, UUID id)
        throws StateAccessException, SerializationException {
        prepareDeleteNeutronVip(ops, id);
        ops.addAll(vipZkManager.prepareDelete(id));
    }

    // Health Monitors
    private void prepareCreateNeutronHealthMonitor(List<Op> ops,
                                                   HealthMonitor healthMonitor)
        throws SerializationException {
        String path = paths.getNeutronHealthMonitorPath(healthMonitor.id);
        ops.add(
            zk.getPersistentCreateOp(path,
                                     serializer.serialize(healthMonitor)));
    }

    private void prepareUpdateNeutronHealthMonitor(List<Op> ops,
                                                   HealthMonitor healthMonitor)
        throws SerializationException {
        String path = paths.getNeutronHealthMonitorPath(healthMonitor.id);
        ops.add(zk.getSetDataOp(path, serializer.serialize(healthMonitor)));
    }

    private void prepareDeleteNeutronHealthMonitor(List<Op> ops, UUID id) {
        String path = paths.getNeutronHealthMonitorPath(id);
        ops.add(zk.getDeleteOp(path));
    }

    public void prepareCreateHealthMonitor(List<Op> ops,
                                           HealthMonitor healthMonitor)
        throws SerializationException, StateAccessException {
        prepareCreateNeutronHealthMonitor(ops, healthMonitor);
        HealthMonitorZkManager.HealthMonitorConfig config
            = new HealthMonitorZkManager.HealthMonitorConfig(healthMonitor);
        ops.addAll(healthMonitorZkManager.prepareCreate(healthMonitor.id,
                                                        config));
    }

    public void prepareUpdateHealthMonitor(List<Op> ops, UUID id,
                                           HealthMonitor healthMonitor)
        throws StateAccessException, SerializationException {
        prepareUpdateNeutronHealthMonitor(ops, healthMonitor);
        HealthMonitorZkManager.HealthMonitorConfig config
            = new HealthMonitorZkManager.HealthMonitorConfig(healthMonitor);
        ops.addAll(healthMonitorZkManager.prepareUpdate(id, config));
    }

    public void prepareDeleteHealthMonitor(List<Op> ops, UUID id)
        throws StateAccessException, SerializationException {
        prepareDeleteNeutronHealthMonitor(ops, id);
        ops.addAll(healthMonitorZkManager.prepareDelete(id));
    }

    // Pool Health Monitors
    public PoolHealthMonitor createPoolHealthMonitor(
        PoolHealthMonitor poolHealthMonitor)
        throws StateAccessException, SerializationException {
        return poolHealthMonitor;
    }

    public PoolHealthMonitor deletePoolHealthMonitor(
        PoolHealthMonitor poolHealthMonitor)
        throws StateAccessException, SerializationException {
        return poolHealthMonitor;
    }
}
