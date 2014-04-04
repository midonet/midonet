/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.cluster;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.midonet.cluster.data.Converter;
import org.midonet.cluster.data.l4lb.HealthMonitor;
import org.midonet.cluster.data.l4lb.LoadBalancer;
import org.midonet.cluster.data.l4lb.Pool;
import org.midonet.cluster.data.l4lb.PoolMember;
import org.midonet.cluster.data.l4lb.VIP;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.InvalidStateOperationException;
import org.midonet.midolman.state.PoolHealthMonitorMappingStatus;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.zkManagers.LoadBalancerZkManager;
import org.midonet.midolman.state.zkManagers.PoolZkManager;

import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.midonet.midolman.state.zkManagers.HealthMonitorZkManager.HealthMonitorConfig;
import static org.midonet.midolman.state.zkManagers.PoolMemberZkManager.PoolMemberConfig;
import static org.midonet.midolman.state.zkManagers.PoolZkManager.PoolHealthMonitorMappingConfig;
import static org.midonet.midolman.state.zkManagers.VipZkManager.VipConfig;

public class PoolHealthMonitorMappingsTest extends LocalDataClientImplTestBase {

    private PoolZkManager poolZkManager;
    private UUID loadBalancerId;
    private HealthMonitor healthMonitor;
    private UUID healthMonitorId;
    private Pool pool;
    private UUID poolId;

    @Before
    public void setUp()
            throws InvalidStateOperationException,
            SerializationException, StateAccessException {
        poolZkManager = getPoolZkManager();
        loadBalancerId = createStockLoadBalancer();
        // Add a health monitor
        healthMonitor = getStockHealthMonitor();
        healthMonitorId = client.healthMonitorCreate(healthMonitor);
        // Add a pool
        pool = getStockPool(loadBalancerId);
        poolId = client.poolCreate(pool);
    }

    @After
    public void tearDown()
        throws SerializationException, StateAccessException {
        // Delete the pool
        client.poolDelete(poolId);
        assertFalse(poolZkManager.existsPoolHealthMonitorMapping(
                poolId, healthMonitorId));
    }

    private void associatePoolHealthMonitor()
            throws SerializationException, StateAccessException {
        // Associate the pool with the health monitor
        pool.setHealthMonitorId(healthMonitorId);
        client.poolUpdate(pool);
        assertTrue(poolZkManager.existsPoolHealthMonitorMapping(
                poolId, healthMonitorId));

        pool = client.poolGet(poolId);
        assertThat(pool.getMappingStatus(),
                equalTo(PoolHealthMonitorMappingStatus.PENDING_CREATE));
    }

    private void disassociatePoolHealthMonitor()
            throws SerializationException, StateAccessException {
        // Disassociate the pool from the health monitor
        pool.setHealthMonitorId(null);
        client.poolUpdate(pool);
        assertFalse(poolZkManager.existsPoolHealthMonitorMapping(
                poolId, healthMonitorId));

        pool = client.poolGet(poolId);
        assertThat(pool.getMappingStatus(),
                equalTo(PoolHealthMonitorMappingStatus.PENDING_DELETE));
    }

    @Test
    public void poolHealthMonitorMappingsTest()
            throws InvalidStateOperationException,
            SerializationException, StateAccessException {

        associatePoolHealthMonitor();

        PoolHealthMonitorMappingConfig config =
                poolZkManager.getPoolHealthMonitorMapping(
                        poolId, healthMonitorId);
        assertThat(config, notNullValue());
        HealthMonitor healthMonitor = client.healthMonitorGet(healthMonitorId);
        HealthMonitorConfig healthMonitorConfig = config.healthMonitorConfig.config;
        assertThat(Converter.toHealthMonitorConfig(healthMonitor),
                equalTo(healthMonitorConfig));
        LoadBalancer loadBalancer = client.loadBalancerGet(loadBalancerId);
        LoadBalancerZkManager.LoadBalancerConfig postedLoadBalancerConfig =
                config.loadBalancerConfig.config;
        assertThat(Converter.toLoadBalancerConfig(loadBalancer),
                equalTo(postedLoadBalancerConfig));

        disassociatePoolHealthMonitor();
    }

    @Test
    public void poolHealthMonitorMappingByDefaultTest()
            throws InvalidStateOperationException,
            SerializationException, StateAccessException {
        Pool newPool = getStockPool(loadBalancerId);
        newPool.setHealthMonitorId(healthMonitorId);
        UUID newPoolId = client.poolCreate(newPool);
        newPool = client.poolGet(newPoolId);
        assertThat(newPool.getHealthMonitorId(),
                equalTo(healthMonitorId));
        assertThat(newPool.getMappingStatus(),
                equalTo(PoolHealthMonitorMappingStatus.PENDING_CREATE));
    }

    @Test
    public void poolDeletionTest()
            throws InvalidStateOperationException,
            SerializationException, StateAccessException {
        associatePoolHealthMonitor();

        PoolHealthMonitorMappingConfig config =
                poolZkManager.getPoolHealthMonitorMapping(
                        poolId, healthMonitorId);
        assertThat(config, notNullValue());
        // Delete is done in `tearDown` method.
    }

    @Test
    public void healthMonitorDeletionTest()
            throws InvalidStateOperationException,
            SerializationException, StateAccessException {
        associatePoolHealthMonitor();

        PoolHealthMonitorMappingConfig config =
                poolZkManager.getPoolHealthMonitorMapping(
                        poolId, healthMonitorId);
        assertThat(config, notNullValue());

        // Delete the health monitor.
        client.healthMonitorDelete(healthMonitorId);
        assertFalse(poolZkManager.existsPoolHealthMonitorMapping(
                poolId, healthMonitorId));
        pool = client.poolGet(poolId);
        assertThat(pool.getMappingStatus(),
                equalTo(PoolHealthMonitorMappingStatus.PENDING_DELETE));
    }

    @Test
    public void poolMemberTest()
            throws InvalidStateOperationException,
            SerializationException, StateAccessException {
        associatePoolHealthMonitor();

        PoolHealthMonitorMappingConfig config =
                poolZkManager.getPoolHealthMonitorMapping(
                        poolId, healthMonitorId);
        assertThat(config, notNullValue());

        // Add a pool member
        UUID poolMemberId = createStockPoolMember(poolId);
        config = poolZkManager.getPoolHealthMonitorMapping(
                poolId, healthMonitorId);
        assertThat(config, notNullValue());
        assertThat(config.poolMemberConfigs.size(), equalTo(1));
        PoolMember poolMember = client.poolMemberGet(poolMemberId);
        PoolMemberConfig poolMemberConfig =
                Converter.toPoolMemberConfig(poolMember);
        PoolMemberConfig addedPoolMemberConfig =
                config.poolMemberConfigs.get(0).config;
        assertThat(poolMemberConfig, equalTo(addedPoolMemberConfig));
        pool = client.poolGet(poolId);
        assertThat(pool.getMappingStatus(),
                equalTo(PoolHealthMonitorMappingStatus.PENDING_UPDATE));

        // Delete the pool member
        client.poolMemberDelete(poolMemberId);
        config = poolZkManager.getPoolHealthMonitorMapping(
                poolId, healthMonitorId);
        assertThat(config, notNullValue());
        assertThat(config.poolMemberConfigs.size(), equalTo(0));
        pool = client.poolGet(poolId);
        assertThat(pool.getMappingStatus(),
                equalTo(PoolHealthMonitorMappingStatus.PENDING_UPDATE));

        disassociatePoolHealthMonitor();
    }

    @Test
    public void vipTest()
            throws InvalidStateOperationException,
            SerializationException, StateAccessException {
        associatePoolHealthMonitor();

        PoolHealthMonitorMappingConfig config =
                poolZkManager.getPoolHealthMonitorMapping(
                        poolId, healthMonitorId);
        assertThat(config, notNullValue());

        // Add a VIP
        UUID vipId = createStockVip(poolId);
        config = poolZkManager.getPoolHealthMonitorMapping(
                poolId, healthMonitorId);
        assertThat(config, notNullValue());
        assertThat(config.vipConfigs.size(), equalTo(1));
        VIP vip = client.vipGet(vipId);
        VipConfig vipConfig = Converter.toVipConfig(vip);
        VipConfig addedVipConfig = config.vipConfigs.get(0).config;
        assertThat(vipConfig, equalTo(addedVipConfig));
        pool = client.poolGet(poolId);
        assertThat(pool.getMappingStatus(),
                equalTo(PoolHealthMonitorMappingStatus.PENDING_UPDATE));

        // Delete the VIP
        client.vipDelete(vipId);
        config = poolZkManager.getPoolHealthMonitorMapping(
                poolId, healthMonitorId);
        assertThat(config, notNullValue());
        assertThat(config.vipConfigs.size(), equalTo(0));
        pool = client.poolGet(poolId);
        assertThat(pool.getMappingStatus(),
                equalTo(PoolHealthMonitorMappingStatus.PENDING_UPDATE));

        disassociatePoolHealthMonitor();
    }

    @Test
    public void updatePoolTest()
            throws InvalidStateOperationException,
            SerializationException, StateAccessException {
        // Add another health monitor with the different parameters.
        UUID anotherHealthMonitorId = client.healthMonitorCreate(
                new HealthMonitor().setDelay(200)
                        .setMaxRetries(200).setTimeout(2000));
        assertFalse(poolZkManager.existsPoolHealthMonitorMapping(
                poolId, healthMonitorId));

        associatePoolHealthMonitor();

        // Update the pool with another health monitor
        pool.setHealthMonitorId(anotherHealthMonitorId);
        client.poolUpdate(pool);
        assertFalse(poolZkManager.existsPoolHealthMonitorMapping(
                poolId, healthMonitorId));
        pool = client.poolGet(poolId);
        assertThat(pool.getMappingStatus(),
                equalTo(PoolHealthMonitorMappingStatus.PENDING_UPDATE));

        PoolHealthMonitorMappingConfig config =
                poolZkManager.getPoolHealthMonitorMapping(
                        poolId, anotherHealthMonitorId);
        assertThat(config, notNullValue());

        disassociatePoolHealthMonitor();
    }

    @Test
    public void updateHealthMonitorTest()
            throws InvalidStateOperationException,
            SerializationException, StateAccessException {
        associatePoolHealthMonitor();

        healthMonitor.setDelay(42);
        client.healthMonitorUpdate(healthMonitor);
        assertThat(healthMonitor.getDelay(), equalTo(42));
        pool = client.poolGet(poolId);
        assertThat(pool.getMappingStatus(),
                equalTo(PoolHealthMonitorMappingStatus.PENDING_UPDATE));

        disassociatePoolHealthMonitor();
    }

    @Test
    public void updateVipTest()
            throws InvalidStateOperationException,
            SerializationException, StateAccessException {
        associatePoolHealthMonitor();

        // Create a VIP with the pool ID
        VIP vip = getStockVip(poolId);
        UUID vipId = client.vipCreate(vip);
        PoolHealthMonitorMappingConfig config =
                poolZkManager.getPoolHealthMonitorMapping(
                        poolId, healthMonitorId);
        assertThat(config, notNullValue());
        assertThat(config.vipConfigs.size(), equalTo(1));
        vip = client.vipGet(vipId);
        VipConfig vipConfig = Converter.toVipConfig(vip);
        VipConfig addedVipConfig = config.vipConfigs.get(0).config;
        assertThat(vipConfig, equalTo(addedVipConfig));

        // Update the VIP with the ID of another pool
        Pool anotherPool = getStockPool(loadBalancerId);
        anotherPool.setHealthMonitorId(healthMonitorId);
        UUID anotherPoolId = client.poolCreate(anotherPool);
        vip.setPoolId(anotherPoolId);
        client.vipUpdate(vip);
        // Check if the updated vip is removed from the old mapping
        config = poolZkManager.getPoolHealthMonitorMapping(
                poolId, healthMonitorId);
        assertThat(config, notNullValue());
        assertThat(config.vipConfigs.size(), equalTo(0));
        pool = client.poolGet(poolId);
        assertThat(pool.getMappingStatus(),
                equalTo(PoolHealthMonitorMappingStatus.PENDING_UPDATE));

        // Check if the update vip is added to another mapping
        PoolHealthMonitorMappingConfig anotherConfig =
                poolZkManager.getPoolHealthMonitorMapping(
                        anotherPoolId, healthMonitorId);
        assertThat(anotherConfig, notNullValue());
        assertThat(anotherConfig.vipConfigs.size(), equalTo(1));
        vip = client.vipGet(vipId);
        vipConfig = Converter.toVipConfig(vip);
        VipConfig updatedVipConfig = anotherConfig.vipConfigs.get(0).config;
        assertThat(vipConfig, equalTo(updatedVipConfig));
        anotherPool = client.poolGet(anotherPoolId);
        assertThat(anotherPool.getMappingStatus(),
                equalTo(PoolHealthMonitorMappingStatus.PENDING_UPDATE));

        disassociatePoolHealthMonitor();
    }

    @Test
    public void updatePoolMemberTest()
            throws InvalidStateOperationException,
            SerializationException, StateAccessException {
        associatePoolHealthMonitor();

        // Create a pool member with the pool ID
        PoolMember poolMember = getStockPoolMember(poolId);
        UUID poolMemberId = client.poolMemberCreate(poolMember);
        PoolHealthMonitorMappingConfig config =
                poolZkManager.getPoolHealthMonitorMapping(
                        poolId, healthMonitorId);
        assertThat(config, notNullValue());
        assertThat(config.poolMemberConfigs.size(), equalTo(1));
        poolMember = client.poolMemberGet(poolMemberId);
        PoolMemberConfig poolMemberConfig =
                Converter.toPoolMemberConfig(poolMember);
        PoolMemberConfig addedPoolMemberConfig =
                config.poolMemberConfigs.get(0).config;
        assertThat(poolMemberConfig, equalTo(addedPoolMemberConfig));
        pool = client.poolGet(poolId);
        assertThat(pool.getMappingStatus(),
                equalTo(PoolHealthMonitorMappingStatus.PENDING_UPDATE));

        // Update the pool member with the ID of another pool
        Pool anotherPool = getStockPool(loadBalancerId);
        anotherPool.setHealthMonitorId(healthMonitorId);
        UUID anotherPoolId = client.poolCreate(anotherPool);
        poolMember.setPoolId(anotherPoolId);
        client.poolMemberUpdate(poolMember);
        // Check if the updated pool member is removed from the old mapping
        config = poolZkManager.getPoolHealthMonitorMapping(
                poolId, healthMonitorId);
        assertThat(config, notNullValue());
        assertThat(config.poolMemberConfigs.size(), equalTo(0));
        // Check if the update pool member is added to the new mapping
        PoolHealthMonitorMappingConfig anotherConfig =
                poolZkManager.getPoolHealthMonitorMapping(
                        anotherPoolId, healthMonitorId);
        assertThat(anotherConfig, notNullValue());
        assertThat(anotherConfig.poolMemberConfigs.size(), equalTo(1));
        poolMember = client.poolMemberGet(poolMemberId);
        poolMemberConfig = Converter.toPoolMemberConfig(poolMember);
        addedPoolMemberConfig = anotherConfig.poolMemberConfigs.get(0).config;
        assertThat(poolMemberConfig, equalTo(addedPoolMemberConfig));
        anotherPool = client.poolGet(anotherPoolId);
        assertThat(anotherPool.getMappingStatus(),
                equalTo(PoolHealthMonitorMappingStatus.PENDING_UPDATE));

        disassociatePoolHealthMonitor();
    }
}
