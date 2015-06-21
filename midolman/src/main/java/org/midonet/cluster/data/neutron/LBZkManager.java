/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.cluster.data.neutron;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.google.inject.Inject;

import org.apache.zookeeper.Op;

import org.midonet.cluster.data.neutron.loadbalancer.HealthMonitor;
import org.midonet.cluster.data.neutron.loadbalancer.Member;
import org.midonet.cluster.data.neutron.loadbalancer.Pool;
import org.midonet.cluster.data.neutron.loadbalancer.PoolHealthMonitor;
import org.midonet.cluster.data.neutron.loadbalancer.VIP;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.BaseZkManager;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.PortDirectory.RouterPortConfig;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;
import org.midonet.midolman.state.zkManagers.HealthMonitorZkManager;
import org.midonet.midolman.state.zkManagers.HealthMonitorZkManager.HealthMonitorConfig;
import org.midonet.midolman.state.zkManagers.LoadBalancerZkManager;
import org.midonet.midolman.state.zkManagers.LoadBalancerZkManager.LoadBalancerConfig;
import org.midonet.midolman.state.zkManagers.PoolHealthMonitorZkManager;
import org.midonet.midolman.state.zkManagers.PoolHealthMonitorZkManager.PoolHealthMonitorConfig;
import org.midonet.midolman.state.zkManagers.PoolMemberZkManager;
import org.midonet.midolman.state.zkManagers.PoolMemberZkManager.PoolMemberConfig;
import org.midonet.midolman.state.zkManagers.PoolZkManager;
import org.midonet.midolman.state.zkManagers.PoolZkManager.PoolConfig;
import org.midonet.midolman.state.zkManagers.PortZkManager;
import org.midonet.midolman.state.zkManagers.RouteZkManager;
import org.midonet.midolman.state.zkManagers.RouterZkManager;
import org.midonet.midolman.state.zkManagers.RouterZkManager.RouterConfig;
import org.midonet.midolman.state.zkManagers.VipZkManager;
import org.midonet.midolman.state.zkManagers.VipZkManager.VipConfig;
import org.midonet.packets.IPv4Subnet;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

public class LBZkManager extends BaseZkManager {

    private final LoadBalancerZkManager loadBalancerZkManager;
    private final PoolZkManager poolZkManager;
    private final HealthMonitorZkManager healthMonitorZkManager;
    private final VipZkManager vipZkManager;
    private final PoolMemberZkManager poolMemberZkManager;
    private final RouterZkManager routerZkManager;
    private final PoolHealthMonitorZkManager poolHealthMonitorZkManager;
    private final NetworkZkManager networkZkManager;
    private final PortZkManager portZkManager;
    private final ProviderRouterZkManager providerRouterZkManager;
    private final RouteZkManager routeZkManager;

    @Inject
    public LBZkManager(ZkManager zk,
                       PathBuilder paths,
                       Serializer serializer,
                       HealthMonitorZkManager healthMonitorZkManager,
                       LoadBalancerZkManager loadBalancerZkManager,
                       PoolZkManager poolZkManager,
                       PoolMemberZkManager poolMemberZkManager,
                       RouterZkManager routerZkManager,
                       PoolHealthMonitorZkManager poolHealthMonitorZkManager,
                       VipZkManager vipZkManager,
                       NetworkZkManager networkZkManager,
                       PortZkManager portZkManager,
                       ProviderRouterZkManager providerRouterZkManager,
                       RouteZkManager routeZkManager) {
        super(zk, paths, serializer);
        this.healthMonitorZkManager = healthMonitorZkManager;
        this.loadBalancerZkManager = loadBalancerZkManager;
        this.poolZkManager = poolZkManager;
        this.poolMemberZkManager = poolMemberZkManager;
        this.routerZkManager = routerZkManager;
        this.vipZkManager = vipZkManager;
        this.poolHealthMonitorZkManager = poolHealthMonitorZkManager;
        this.networkZkManager = networkZkManager;
        this.portZkManager = portZkManager;
        this.providerRouterZkManager = providerRouterZkManager;
        this.routeZkManager = routeZkManager;
    }

    private LoadBalancerConfig ensureLoadBalancerExists(List<Op> ops,
                                                        UUID routerId)
        throws StateAccessException, SerializationException {

        RouterConfig routerConfig = routerZkManager.get(routerId);
        checkState(routerConfig != null,
                   "The router must exist for creating pools");
        if (routerConfig.loadBalancer != null) {
            return loadBalancerZkManager.get(routerConfig.loadBalancer);
        }

        LoadBalancerConfig lbConf = new LoadBalancerConfig(routerId, true);
        lbConf.id = UUID.randomUUID();
        routerConfig.loadBalancer = lbConf.id;
        ops.addAll(loadBalancerZkManager.prepareCreate(lbConf.id, lbConf));
        routerZkManager.prepareUpdateLoadBalancer(ops, routerId, lbConf.id);
        return lbConf;
    }

    private void cleanupLoadBalancer(List<Op> ops, UUID loadBalancerId,
                                     UUID poolId)
        throws StateAccessException, SerializationException {
        List<UUID> pools = poolZkManager.getAll();

        checkState(pools.size() > 0, "There should be at least one pool " +
                                     "associated with this load balancer");
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

        Pool pool = getNeutronPool(poolId);
        checkNotNull(pool.routerId, "No router associated with pool");
        ops.addAll(routerZkManager.prepareClearRefsToLoadBalancer(
                       pool.routerId, loadBalancerId));
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

        checkNotNull(pool, "A pool object is required for creation");
        UUID routerId = checkNotNull(pool.routerId,
                                     "the Pool must have a router ID set");
        checkNotNull(pool.id, "The neutron pool must have an id");

        LoadBalancerConfig loadBalancer
            = ensureLoadBalancerExists(ops, routerId);

        checkState(loadBalancer.id != null,
            "In creating a Pool, the load balancer ID was NULL");
        // Create the Neutron data regardless of whatever else we do.
        prepareCreateNeutronPool(ops, pool);

        PoolConfig poolConfig = new PoolConfig(pool, loadBalancer.id);

        // Create the pool itself.
        ops.addAll(loadBalancerZkManager.prepareAddPool(loadBalancer.id,
                                                        pool.id));
        ops.addAll(poolZkManager.prepareCreate(pool.id, poolConfig));

        if (pool.hasHealthMonitorAssociated()) {

            UUID healthMonitorId = pool.getHealthMonitor();
            HealthMonitorConfig healthMonitorConfig =
                healthMonitorZkManager.get(healthMonitorId);

            HealthMonitor healthMonitor = getNeutronHealthMonitor(pool.id);
            healthMonitor.addPool(pool.id);
            prepareUpdateNeutronHealthMonitor(ops, healthMonitor);

            // vips and members can not be set to the pool at creation time.
            prepareAssociatePoolHealthMonitor(ops, loadBalancer, poolConfig,
                                              healthMonitorConfig);
        }
    }

    private PoolHealthMonitorConfig gatherLatestMapInfo(UUID poolId)
        throws StateAccessException, SerializationException {

        PoolConfig poolConfig = poolZkManager.get(poolId);
        LoadBalancerConfig loadBalancerConfig =
            loadBalancerZkManager.get(poolConfig.loadBalancerId);
        List<PoolMemberConfig> members = getMembers(poolId);

        List<VipConfig> vips = getVips(poolId);
        HealthMonitorConfig hmConfig =
            healthMonitorZkManager.get(poolConfig.healthMonitorId);

        return new PoolHealthMonitorConfig(loadBalancerConfig, vips, members,
                                           hmConfig);
    }

    private void preparePoolHealthMonitorAddMember(
        List<Op> ops, PoolMemberConfig poolMemberConfig)
        throws StateAccessException, SerializationException {

        PoolHealthMonitorConfig phmConfig
            = gatherLatestMapInfo(poolMemberConfig.poolId);
        phmConfig.addMember(poolMemberConfig);

        poolHealthMonitorZkManager.preparePoolHealthMonitorUpdate(
            ops, poolMemberConfig.poolId, phmConfig);
    }

    private void preparePoolHealthMonitorRemoveMember(
        List<Op> ops, PoolMemberConfig poolMemberConfig)
        throws StateAccessException, SerializationException {

        PoolHealthMonitorConfig phmConfig = gatherLatestMapInfo(
            poolMemberConfig.poolId);
        phmConfig.removeMember(poolMemberConfig.id);

        poolHealthMonitorZkManager.preparePoolHealthMonitorUpdate(
            ops, poolMemberConfig.poolId, phmConfig);
    }

    private void preparePoolHealthMonitorModifyMember(
        List<Op> ops, PoolMemberConfig pmConfig)
        throws StateAccessException, SerializationException {

        PoolHealthMonitorConfig phmConfig
            = gatherLatestMapInfo(pmConfig.poolId);
        phmConfig.updateMember(pmConfig);

        poolHealthMonitorZkManager.preparePoolHealthMonitorUpdate(
            ops, pmConfig.poolId, phmConfig);
    }

    private void preparePoolHealthMonitorModifyHealthMonitor(
        List<Op> ops, UUID poolId, HealthMonitor healthMonitor)
        throws StateAccessException, SerializationException {
        PoolHealthMonitorConfig phmConfig = gatherLatestMapInfo(poolId);
        phmConfig.updateHealthMonitor(new HealthMonitorConfig(healthMonitor));
        poolHealthMonitorZkManager.preparePoolHealthMonitorUpdate(
            ops, poolId, phmConfig);
    }

    private void preparePoolHealthMonitorAddVip(
        List<Op> ops, UUID poolId, VIP vip)
        throws StateAccessException, SerializationException {

        PoolHealthMonitorConfig phmConfig = gatherLatestMapInfo(poolId);

        UUID lbId = phmConfig.loadBalancerConfig.persistedId;
        checkState(lbId != null, "The load balancer requires an ID");
        phmConfig.updateVip(new VipConfig(vip, lbId));
        poolHealthMonitorZkManager.preparePoolHealthMonitorUpdate(
            ops, poolId, phmConfig);
    }

    private void preparePoolHealthMonitorModifyVip(
        List<Op> ops, UUID poolId, VIP vip)
        throws StateAccessException, SerializationException {
        preparePoolHealthMonitorAddVip(ops, poolId, vip);
    }

    private void preparePoolHealthMonitorRemoveVip(
        List<Op> ops, UUID poolId, VIP vip)
        throws StateAccessException, SerializationException {
        PoolHealthMonitorConfig phmConfig = gatherLatestMapInfo(poolId);

        UUID lbId = checkNotNull(phmConfig.loadBalancerConfig.persistedId,
                                 "The load balancer requires an ID");

        phmConfig.removeVip(new VipConfig(vip, lbId));
        poolHealthMonitorZkManager.preparePoolHealthMonitorUpdate(ops, poolId,
                                                                  phmConfig);
    }

    private void prepareAssociatePoolHealthMonitor(List<Op> ops,
        LoadBalancerConfig lbConf, PoolConfig poolConf,
        HealthMonitorConfig hmConf)
        throws StateAccessException, SerializationException {

        checkNotNull(poolConf.id, "The pool must have an id to create " +
                                  "pool-healthmonitor association");

        checkNotNull(hmConf.id, "The health monitor must have an id to " +
                                "create pool-healthmonitor association");

        // Add a pool ref to the health monitor. This is so we can easily
        // find all the pools associated with a given health monitor.
        ops.addAll(healthMonitorZkManager.prepareAddPool(hmConf.id,
                                                         poolConf.id));

        List<VipConfig> vipConfs = getVips(poolConf.id);
        List<PoolMemberConfig> memberConfigs = getMembers(poolConf.id);
        // vips and members can not be set to the pool at creation time.
        poolHealthMonitorZkManager.preparePoolHealthMonitorCreate(
            ops, lbConf, vipConfs, memberConfigs, hmConf, poolConf);
    }

    private void prepareDisassociatePoolHealthMonitor(List<Op> ops,
            UUID healthMonitorId, UUID poolId)
        throws StateAccessException, SerializationException {

        checkNotNull(healthMonitorId, "The pool must have an id to create " +
                                      "pool-healthmonitor association");

        checkNotNull(poolId, "The health monitor must have an id to create " +
                             "pool-healthmonitor association");

        // Remove a pool ref to the health monitor. This is so we can easily
        // find all the pools associated with a given health monitor.
        ops.addAll(healthMonitorZkManager.prepareRemovePool(
            healthMonitorId, poolId));

        // vips and members can not be set to the pool at creation time.
        poolHealthMonitorZkManager.preparePoolHealthMonitorDelete(
            ops, poolId, healthMonitorId);
    }

    private void prepareUpdateNeutronPool(List<Op> ops, Pool pool)
        throws SerializationException {
        String path = paths.getNeutronPoolPath(pool.id);
        ops.add(zk.getSetDataOp(path, serializer.serialize(pool)));
    }

    private List<VipConfig> getVips(UUID poolId)
        throws StateAccessException, SerializationException {
        List<VipConfig> vips = new ArrayList<>();
        for (UUID vipId: poolZkManager.getVipIds(poolId)) {
            vips.add(vipZkManager.get(vipId));
        }
        return vips;
    }

    private List<PoolMemberZkManager.PoolMemberConfig> getMembers(UUID poolId)
        throws StateAccessException, SerializationException {
        List<PoolMemberConfig> members = new ArrayList<>();
        for (UUID memberId: poolZkManager.getMemberIds(poolId)) {
            members.add(poolMemberZkManager.get(memberId));
        }
        return members;
    }

    public void prepareUpdatePool(List<Op> ops, UUID id, Pool pool)
        throws StateAccessException, SerializationException {

        checkNotNull(pool.id, "The neutron pool must have an id to update");
        Pool oldPool = getNeutronPool(id);
        checkNotNull(oldPool.id, "The neutron pool must have an id to update");
        checkNotNull(oldPool.routerId,
            "The pool must have a router associated with it");
        RouterConfig routerConfig = routerZkManager.get(oldPool.routerId);
        UUID lbID = checkNotNull(routerConfig.loadBalancer,
            "The router must have a load balancer associated with it");

        // Updates don't give us the routerId. So we have to "repair" it.
        pool.routerId = oldPool.routerId;
        prepareUpdateNeutronPool(ops, pool);

        // There are 4 cases we have to worry about:
        // 1) A health monitor is added to the pool
        // 2) A health monitor is removed from the pool
        // 3) The health monitor associated with the pool changes
        // 4) no change

        UUID oldHmId = oldPool.getHealthMonitor();
        UUID newHmId = pool.getHealthMonitor();

        if (oldHmId == null && newHmId != null) {
            // case 1: a health monitor is added.
            PoolConfig poolConfig = new PoolConfig(pool, lbID);
            HealthMonitor healthMonitor = getNeutronHealthMonitor(pool.id);
            healthMonitor.addPool(pool.id);
            prepareUpdateNeutronHealthMonitor(ops, healthMonitor);
            prepareAssociatePoolHealthMonitor(ops,
                loadBalancerZkManager.get(lbID), poolConfig,
                healthMonitorZkManager.get(newHmId));
        } else if (oldHmId != null && newHmId == null) {
            // case 2: a health monitor is removed from the pool
            HealthMonitor healthMonitor = getNeutronHealthMonitor(oldHmId);
            prepareUpdateNeutronHealthMonitor(ops, healthMonitor);

            healthMonitor.removePool(pool.id);
            prepareUpdateNeutronHealthMonitor(ops, healthMonitor);

            prepareDisassociatePoolHealthMonitor(ops, oldHmId, pool.id);
        } else if (!Objects.equals(oldHmId, newHmId)) {
            // case 3: the health monitor was exchanged
            // NOT SUPPORTED
            throw new IllegalStateException("The pool already has a health " +
                                            "monitor associated with it");
        } else {
            // case 4: no change == NOTHING TO DO
        }

        PoolConfig poolConfig = new PoolConfig(pool, lbID);
        ops.addAll(poolZkManager.prepareUpdate(id, poolConfig));
    }

    private void prepareDeleteNeutronPool(List<Op> ops, UUID id) {
        checkNotNull(id, "The pool id must not be null in a neutron pool " +
                         "delete");

        String path = paths.getNeutronPoolPath(id);
        ops.add(zk.getDeleteOp(path));
    }

    public void prepareDeletePool(List<Op> ops, UUID id)
        throws StateAccessException, SerializationException {
        Pool pool = getNeutronPool(id);
        prepareDeleteNeutronPool(ops, id);
        PoolConfig poolConfig = poolZkManager.get(id);

        if (pool.hasHealthMonitorAssociated()) {
            UUID healthMonitorId = pool.getHealthMonitor();

            poolHealthMonitorZkManager.preparePoolHealthMonitorDelete(
                ops, pool.id, healthMonitorId);
            HealthMonitor healthMonitor =
                getNeutronHealthMonitor(pool.getHealthMonitor());
            healthMonitor.removePool(pool.id);
            prepareUpdateNeutronHealthMonitor(ops, healthMonitor);
        }

        // Each member of this pool needs to be updated.
        for (UUID memberId : pool.members) {
            ops.addAll(poolZkManager.prepareRemoveMember(pool.id, memberId));
            Member member = getNeutronMember(memberId);
            member.poolId = null;
            prepareUpdateNeutronMember(ops, member);

            PoolMemberConfig pmConfig = poolMemberZkManager.get(memberId);
            pmConfig.poolId = null;
            ops.addAll(poolMemberZkManager.prepareUpdate(memberId, pmConfig));
        }

        UUID lbId = getLoadBalancerIdFromPool(pool);

        checkState(lbId != null, "The pool must have a load balancer " +
            "associated with it for deletion");
        ops.addAll(loadBalancerZkManager.prepareRemovePool(lbId, id));
        cleanupLoadBalancer(ops, poolConfig.loadBalancerId, id);
        ops.addAll(poolZkManager.prepareDelete(id));
    }

    // Members
    private void prepareCreateNeutronMember(List<Op> ops, Member member)
        throws SerializationException {
        checkNotNull(member.id, "Creating a neutron pool member " +
                                "requires an id");

        String path = paths.getNeutronMemberPath(member.id);
        ops.add(zk.getPersistentCreateOp(path, serializer.serialize(member)));
    }

    private void prepareUpdateNeutronMember(List<Op> ops, Member member)
        throws SerializationException {
        checkNotNull(member.id, "updating a neutron pool member requires " +
                                "an id");

        String path = paths.getNeutronMemberPath(member.id);
        ops.add(zk.getSetDataOp(path, serializer.serialize(member)));
    }

    private void prepareDeleteNeutronMember(List<Op> ops, UUID id) {
        checkNotNull(id, "deleting a neutron pool member requires an id");

        String path = paths.getNeutronMemberPath(id);
        ops.add(zk.getDeleteOp(path));
    }

    public void prepareCreateMember(List<Op> ops, Member member)
        throws SerializationException, StateAccessException {

        prepareCreateNeutronMember(ops, member);
        PoolMemberConfig poolMemberConfig = new PoolMemberConfig(member);
        poolMemberConfig.id = member.id;
        ops.addAll(
            poolMemberZkManager.prepareCreate(member.id, poolMemberConfig));
        if (poolMemberConfig.poolId != null) {
            Pool pool = getNeutronPool(member.poolId);
            pool.addMember(member.id);
            prepareUpdateNeutronPool(ops, pool);
            ops.addAll(
                poolZkManager.prepareAddMember(member.poolId, member.id));
        }
        PoolConfig poolConfig = poolZkManager.get(member.poolId);
        if (poolConfig.healthMonitorId != null) {
            // If a mapping exists for this pool, we have to update that as well.
            preparePoolHealthMonitorAddMember(ops, poolMemberConfig);
        }
    }

    public void prepareUpdateMember(List<Op> ops, UUID id, Member member)
        throws StateAccessException, SerializationException {

        Member oldMember = getNeutronMember(id);
        PoolMemberConfig pmConfig = new PoolMemberConfig(member);
        PoolMemberConfig oldPmConfig = new PoolMemberConfig(oldMember);
        if (!Objects.equals(oldMember.poolId, member.poolId)) {
            // The Pool has changed
            if (oldMember.poolId != null) {
                PoolConfig poolConfig = poolZkManager.get(oldMember.poolId);
                Pool pool = getNeutronPool(oldMember.poolId);
                pool.removeMember(oldMember.id);
                prepareUpdateNeutronPool(ops, pool);
                ops.addAll(poolZkManager.prepareRemoveMember(
                    oldMember.poolId, member.id));
                if (poolConfig.healthMonitorId != null) {
                    // A mapping exists, so remove the member from it
                    preparePoolHealthMonitorRemoveMember(ops, oldPmConfig);
                }
            }

            if (member.poolId != null) {
                PoolConfig poolConfig = poolZkManager.get(member.poolId);
                Pool pool = getNeutronPool(member.poolId);
                pool.addMember(member.id);
                prepareUpdateNeutronPool(ops, pool);
                ops.addAll(poolZkManager.prepareAddMember(
                    member.poolId, member.id));
                if (poolConfig.healthMonitorId != null) {
                    preparePoolHealthMonitorAddMember(ops, pmConfig);
                }
            }
        } else if (member.poolId != null) {
            // The pool has NOT changed. Just update the mapping if it exists
            PoolConfig poolConfig = poolZkManager.get(member.poolId);
            if (poolConfig.healthMonitorId != null) {
                preparePoolHealthMonitorModifyMember(ops, pmConfig);
            }
        }

        prepareUpdateNeutronMember(ops, member);
        ops.addAll(poolMemberZkManager.prepareUpdate(id, pmConfig));
    }

    public void prepareDeleteMember(List<Op> ops, UUID id)
        throws StateAccessException, SerializationException {

        Member member = getNeutronMember(id);
        prepareDeleteNeutronMember(ops, id);
        if (member.poolId != null) {
            Pool pool = getNeutronPool(member.poolId);
            pool.removeMember(member.id);
            prepareUpdateNeutronPool(ops, pool);
            ops.addAll(poolZkManager.prepareRemoveMember(
                member.poolId, member.id));
            PoolConfig poolConfig = poolZkManager.get(member.poolId);
            if (poolConfig.healthMonitorId != null) {
                PoolMemberConfig pmConf = new PoolMemberConfig(member);
                preparePoolHealthMonitorRemoveMember(ops, pmConf);
            }
        }
        ops.addAll(poolMemberZkManager.prepareDelete(id));
    }

    // Vips
    private void prepareCreateNeutronVip(List<Op> ops, VIP vip)
        throws SerializationException, StateAccessException {
        if (vip.poolId != null) {
            Pool pool = getNeutronPool(vip.poolId);
            checkNotNull(pool.id, "The neutron pool must have an id to update");
            checkState(pool.vipId == null, "can not create-associate vip " +
                "with a pool already assigned to a vip ");

            pool.vipId = vip.id;
            prepareUpdateNeutronPool(ops, pool);
        }
        String path = paths.getNeutronVipPath(vip.id);
        ops.add(zk.getPersistentCreateOp(path, serializer.serialize(vip)));
    }

    public Pool getNeutronPool(UUID id)
        throws StateAccessException, SerializationException {
        checkNotNull(id, "Can not retrieve neutron pool associated " +
                         "with NULL id");
        String path = paths.getNeutronPoolPath(id);
        return serializer.deserialize(zk.get(path), Pool.class);
    }

    public List<Pool> getNeutronPools()
        throws StateAccessException, SerializationException {
        String path = paths.getNeutronPoolsPath();
        Set<UUID> poolIds = getUuidSet(path);
        List<Pool> pools = new ArrayList<>();
        for (UUID poolId : poolIds) {
            pools.add(getNeutronPool(poolId));
        }
        return pools;
    }

    public Member getNeutronMember(UUID id)
        throws StateAccessException, SerializationException {
        checkNotNull(id, "Can not retrieve neutron member associated " +
                         "with NULL id");
        String path = paths.getNeutronMemberPath(id);
        return serializer.deserialize(zk.get(path), Member.class);
    }

    public List<Member> getNeutronMembers()
        throws StateAccessException, SerializationException {
        String path = paths.getNeutronMembersPath();
        Set<UUID> memberIds = getUuidSet(path);
        List<Member> members = new ArrayList<>();
        for (UUID memberId : memberIds) {
            members.add(getNeutronMember(memberId));
        }
        return members;
    }

    public HealthMonitor getNeutronHealthMonitor(UUID id)
        throws StateAccessException, SerializationException {
        checkNotNull(id, "Can not retrieve neutron health monitor associated " +
                         "with NULL id");
        String path = paths.getNeutronHealthMonitorPath(id);
        return serializer.deserialize(zk.get(path), HealthMonitor.class);
    }

    public List<HealthMonitor> getNeutronHealthMonitors()
        throws StateAccessException, SerializationException {
        String path = paths.getNeutronHealthMonitorsPath();
        Set<UUID> healthMonitorIds = getUuidSet(path);
        List<HealthMonitor> healthMonitors = new ArrayList<>();
        for (UUID hmId : healthMonitorIds) {
            healthMonitors.add(getNeutronHealthMonitor(hmId));
        }
        return healthMonitors;
    }

    public VIP getNeutronVip(UUID id)
        throws StateAccessException, SerializationException {
        checkNotNull(id, "Can not retrieve neutron vip associated " +
                         "with NULL id");
        String path = paths.getNeutronVipPath(id);
        return serializer.deserialize(zk.get(path), VIP.class);
    }

    public List<VIP> getNeutronVips()
        throws StateAccessException, SerializationException {
        String path = paths.getNeutronVipsPath();
        Set<UUID> vipIds = getUuidSet(path);
        List<VIP> vips = new ArrayList<>();
        for (UUID vipId : vipIds) {
            vips.add(getNeutronVip(vipId));
        }
        return vips;
    }

    private UUID getLoadBalancerIdFromPool(Pool pool)
        throws StateAccessException, SerializationException {
        RouterConfig routerConfig = routerZkManager.get(pool.routerId);
        return routerConfig.loadBalancer;
    }

    private UUID getLoadBalancerIdFromVip(VIP vip)
        throws StateAccessException, SerializationException {
        Pool neutronPool = getNeutronPool(vip.poolId);
        return getLoadBalancerIdFromPool(neutronPool);
    }

    private void prepareVipRouteCreation(List<Op> ops, VIP vip)
        throws StateAccessException, SerializationException {
        Subnet subnet = networkZkManager.getSubnet(vip.subnetId);
        Network network = networkZkManager.getNetwork(subnet.networkId);
        if (network.external) {
            UUID prId = providerRouterZkManager.getId();
            // Delete the route that already exists on the provider router
            // for this vip port. This route was created by the "create port"
            // operation because the port was created on an external network.
            routeZkManager.prepareRoutesDelete(ops, prId,
                                               new IPv4Subnet(vip.address, 32));

            Pool neutronPool = getNeutronPool(vip.poolId);

            UUID routerId = neutronPool.routerId;
            // Find the GW port
            RouterPortConfig gwPort =
                portZkManager.findFirstRouterPortByPeer(routerId, prId);
            // Add a route to this gateway port on the provider router
            RouterPortConfig prPortCfg =
                (RouterPortConfig) portZkManager.get(gwPort.peerId);
            routeZkManager.preparePersistPortRouteCreate(
                ops, prId, new IPv4Subnet(0, 0),
                new IPv4Subnet(vip.address, 32), prPortCfg, null);
        }
    }

    private void prepareVipRouteDeletion(List<Op> ops, VIP vip)
        throws StateAccessException, SerializationException {
        Subnet subnet = networkZkManager.getSubnet(vip.subnetId);
        Network network = networkZkManager.getNetwork(subnet.networkId);
        if (network.external) {
            UUID prId = providerRouterZkManager.getId();
            routeZkManager.prepareRoutesDelete(ops, prId,
                                               new IPv4Subnet(vip.address, 32));
        }
    }

    public void prepareCreateVip(List<Op> ops, VIP vip)
        throws SerializationException, StateAccessException {
        UUID loadBalancerId = null;
        if (vip.poolId != null) {
            loadBalancerId = getLoadBalancerIdFromVip(vip);
            ops.addAll(poolZkManager.prepareAddVip(vip.poolId, vip.id));
            ops.addAll(
                loadBalancerZkManager.prepareAddVip(loadBalancerId, vip.id));
            Pool pool = getNeutronPool(vip.poolId);
            if (pool.hasHealthMonitorAssociated()) {
                preparePoolHealthMonitorAddVip(ops, pool.id, vip);
            }
        }

        prepareVipRouteCreation(ops, vip);

        prepareCreateNeutronVip(ops, vip);
        VipConfig vipConfig = new VipConfig(vip, loadBalancerId);
        ops.addAll(vipZkManager.prepareCreate(vip.id, vipConfig));
    }

    private void prepareUpdateNeutronVip(List<Op> ops, VIP vip)
        throws SerializationException, StateAccessException {
        VIP oldVip = getNeutronVip(vip.id);
        if (!Objects.equals(oldVip.poolId, vip.poolId)) {
            if (oldVip.poolId != null) {
                Pool oldPool = getNeutronPool(oldVip.poolId);
                checkNotNull(oldPool.id,
                             "The neutron pool must have an id to update");
                oldPool.vipId = null;
                prepareUpdateNeutronPool(ops, oldPool);
            }

            if (vip.poolId != null) {
                Pool pool = getNeutronPool(vip.poolId);
                checkNotNull(pool.id,
                             "The neutron pool must have an id to update");
                pool.vipId = vip.id;
                prepareUpdateNeutronPool(ops, pool);
            }
        }
        String path = paths.getNeutronVipPath(vip.id);
        ops.add(zk.getSetDataOp(path, serializer.serialize(vip)));
    }

    public void prepareUpdateVip(List<Op> ops, UUID id, VIP vip)
        throws StateAccessException, SerializationException {
        VIP oldVip = getNeutronVip(vip.id);
        prepareUpdateNeutronVip(ops, vip);

        if (!Objects.equals(oldVip.poolId, vip.poolId)) {
            if (vip.poolId != null) {
                UUID loadBalancerId = getLoadBalancerIdFromVip(vip);
                ops.addAll(poolZkManager.prepareAddVip(vip.poolId, vip.id));
                ops.addAll(
                    loadBalancerZkManager.prepareAddVip(
                        loadBalancerId, vip.id));
                Pool pool = getNeutronPool(vip.poolId);
                if (pool.hasHealthMonitorAssociated()) {
                    preparePoolHealthMonitorAddVip(ops, pool.id, vip);
                }
            }

            if (oldVip.poolId != null) {
                UUID loadBalancerId = getLoadBalancerIdFromVip(oldVip);
                ops.addAll(poolZkManager.prepareRemoveVip(oldVip.poolId,
                                                          oldVip.id));
                ops.addAll(loadBalancerZkManager.prepareRemoveVip(
                    loadBalancerId, oldVip.id));
                Pool pool = getNeutronPool(vip.poolId);
                if (pool.hasHealthMonitorAssociated()) {
                    preparePoolHealthMonitorRemoveVip(ops, pool.id, vip);
                }
            }
        } else if (vip.poolId != null) {
            Pool pool = getNeutronPool(vip.poolId);
            if (pool.hasHealthMonitorAssociated()) {
                preparePoolHealthMonitorModifyVip(ops, pool.id, vip);
            }
        }

        UUID loadBalancerId = getLoadBalancerIdFromVip(vip);
        VipConfig vipConfig = new VipConfig(vip, loadBalancerId);
        ops.addAll(vipZkManager.prepareUpdate(id, vipConfig));
    }

    private void prepareDeleteNeutronVip(List<Op> ops, UUID id)
        throws StateAccessException, SerializationException {
        VIP vip = getNeutronVip(id);

        if (vip.poolId != null) {
            Pool pool = getNeutronPool(vip.poolId);
            checkNotNull(pool.id, "The neutron pool must have an id to update");
            pool.vipId = null;
            prepareUpdateNeutronPool(ops, pool);
        }

        String path = paths.getNeutronVipPath(id);
        ops.add(zk.getDeleteOp(path));
    }

    public void prepareDeleteVip(List<Op> ops, UUID id)
        throws StateAccessException, SerializationException {
        VIP vip = getNeutronVip(id);
        if (vip.poolId != null) {
            UUID loadBalancerId = getLoadBalancerIdFromVip(vip);
            ops.addAll(poolZkManager.prepareRemoveVip(vip.poolId, vip.id));
            ops.addAll(
                loadBalancerZkManager.prepareRemoveVip(loadBalancerId, vip.id));
            Pool pool = getNeutronPool(vip.poolId);
            if (pool.hasHealthMonitorAssociated()) {
                preparePoolHealthMonitorRemoveVip(ops, pool.id, vip);
            }
        }
        prepareVipRouteDeletion(ops, vip);
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
        HealthMonitorConfig config = new HealthMonitorConfig(healthMonitor);
        ops.addAll(healthMonitorZkManager.prepareCreate(healthMonitor.id,
                                                        config));

        if (healthMonitor.hasPoolAssociated()) {
            // Starting in Kilo, MidoNet plugin adopted the advanced service
            // driver model.  In that model, HM-Pool association happens when
            // creating a health monitor.
            PoolConfig pConfig = prepareUpdatePoolWithHealthMonitor(
                ops, healthMonitor.getPoolId(), healthMonitor.id);
            LoadBalancerConfig lbConf
                = loadBalancerZkManager.get(pConfig.loadBalancerId);
            prepareAssociatePoolHealthMonitor(ops, lbConf, pConfig, config);
        }
    }

    public void prepareUpdateHealthMonitor(List<Op> ops, UUID id,
                                           HealthMonitor healthMonitor)
        throws StateAccessException, SerializationException {
        if (healthMonitor.hasPoolAssociated()) {
            UUID poolId = healthMonitor.getPoolId();
            preparePoolHealthMonitorModifyHealthMonitor(ops, poolId,
                                                        healthMonitor);
        }

        prepareUpdateNeutronHealthMonitor(ops, healthMonitor);
        HealthMonitorConfig config = new HealthMonitorConfig(healthMonitor);
        ops.addAll(healthMonitorZkManager.prepareUpdate(id, config));
    }

    public void prepareDeleteHealthMonitor(List<Op> ops, UUID id)
        throws StateAccessException, SerializationException {
        HealthMonitor hm = getNeutronHealthMonitor(id);
        if (hm.hasPoolAssociated()) {
            prepareUpdatePoolWithHealthMonitor(ops, hm.getPoolId(), null);
            prepareDisassociatePoolHealthMonitor(ops, id, hm.getPoolId());
        }

        prepareDeleteNeutronHealthMonitor(ops, id);
        ops.addAll(healthMonitorZkManager.prepareDelete(id));
    }

    private PoolConfig prepareUpdatePoolWithHealthMonitor(List<Op> ops,
                                                          UUID poolId,
                                                          UUID hmId)
        throws SerializationException, StateAccessException {
        Pool pool = getNeutronPool(poolId);
        if (hmId == null) {
            pool.healthMonitors = null;
        } else {
            pool.healthMonitors = Collections.singletonList(hmId);
        }
        prepareUpdateNeutronPool(ops, pool);

        PoolConfig pConfig = poolZkManager.get(poolId);
        pConfig.healthMonitorId = hmId;
        ops.addAll(poolZkManager.prepareUpdate(poolId, pConfig));

        return pConfig;
    }

    // Pool Health Monitors
    public void createPoolHealthMonitor(List<Op> ops, UUID poolId,
                                        PoolHealthMonitor poolHealthMonitor)
        throws StateAccessException, SerializationException {

        PoolConfig pConfig = prepareUpdatePoolWithHealthMonitor(
            ops, poolId, poolHealthMonitor.id);

        HealthMonitor healthMonitor = getNeutronHealthMonitor(
            poolHealthMonitor.id);
        healthMonitor.addPool(poolId);
        prepareUpdateNeutronHealthMonitor(ops, healthMonitor);

        HealthMonitorConfig hmConfig
            = healthMonitorZkManager.get(poolHealthMonitor.id);
        LoadBalancerConfig lbConf
            = loadBalancerZkManager.get(pConfig.loadBalancerId);

        prepareAssociatePoolHealthMonitor(ops, lbConf, pConfig, hmConfig);
    }

    public void deletePoolHealthMonitor(List<Op> ops, UUID poolId, UUID hmId)
        throws StateAccessException, SerializationException {
        prepareUpdatePoolWithHealthMonitor(ops, poolId, null);

        HealthMonitor healthMonitor = getNeutronHealthMonitor(hmId);
        healthMonitor.removePool(poolId);
        prepareUpdateNeutronHealthMonitor(ops, healthMonitor);

        prepareDisassociatePoolHealthMonitor(ops, hmId, poolId);
    }
}
