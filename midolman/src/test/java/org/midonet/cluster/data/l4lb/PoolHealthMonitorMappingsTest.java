/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.cluster.data.l4lb;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.midonet.cluster.LocalDataClientImplTestBase;
import org.midonet.cluster.data.Converter;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.InvalidStateOperationException;
import org.midonet.midolman.state.PoolHealthMonitorMappingStatus;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.l4lb.MappingStatusException;
import org.midonet.midolman.state.l4lb.MappingViolationException;
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
            throws InvalidStateOperationException, MappingStatusException,
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
            throws  MappingStatusException, SerializationException,
            StateAccessException {
        // Delete the pool
        pool = emulateHealthMonitorActivation(pool);
        client.poolDelete(poolId);
        assertFalse(poolZkManager.existsPoolHealthMonitorMapping(
                poolId, healthMonitorId));
    }

    private Pool emulateHealthMonitor(
            Pool pool,
            PoolHealthMonitorMappingStatus mappingStatus)
            throws MappingStatusException, SerializationException,
            StateAccessException {
        client.poolSetMapStatus(pool.getId(), mappingStatus);
        Pool updatedPool = client.poolGet(pool.getId());
        return updatedPool;
    }

    private Pool emulateHealthMonitorActivation(Pool pool)
            throws MappingStatusException, SerializationException,
            StateAccessException {
        return emulateHealthMonitor(pool,
                PoolHealthMonitorMappingStatus.ACTIVE);
    }

    private Pool emulateHealthMonitorDeactivation(Pool pool)
            throws MappingStatusException, SerializationException,
            StateAccessException {
        return emulateHealthMonitor(pool,
                PoolHealthMonitorMappingStatus.INACTIVE);
    }

    private void associatePoolHealthMonitor(Pool pool)
            throws MappingStatusException, MappingViolationException,
            SerializationException, StateAccessException {
        // Associate the pool with the health monitor
        pool.setHealthMonitorId(healthMonitorId);
        client.poolUpdate(pool);
        assertTrue(poolZkManager.existsPoolHealthMonitorMapping(
                poolId, healthMonitorId));

        pool = client.poolGet(pool.getId());
        assertThat(pool.getMappingStatus(),
                equalTo(PoolHealthMonitorMappingStatus.PENDING_CREATE));
        pool = emulateHealthMonitorActivation(pool);
    }

    private void disassociatePoolHealthMonitor(Pool pool)
            throws MappingStatusException, MappingViolationException,
            SerializationException, StateAccessException {
        pool = emulateHealthMonitorActivation(pool);
        // Disassociate the pool from the health monitor.
        pool.setHealthMonitorId(null);
        client.poolUpdate(pool);

        pool = client.poolGet(pool.getId());
        assertThat(pool.getMappingStatus(),
                equalTo(PoolHealthMonitorMappingStatus.PENDING_DELETE));
        pool = emulateHealthMonitorDeactivation(pool);
    }

    @Test
    public void poolHealthMonitorMappingsTest()
            throws InvalidStateOperationException, MappingStatusException,
            MappingViolationException, SerializationException,
            StateAccessException {
        associatePoolHealthMonitor(pool);

        PoolHealthMonitorMappingConfig config =
                poolZkManager.getPoolHealthMonitorMapping(
                        poolId, healthMonitorId);
        assertThat(config, notNullValue());
        HealthMonitor healthMonitor = client.healthMonitorGet(healthMonitorId);
        HealthMonitorConfig healthMonitorConfig =
                config.healthMonitorConfig.config;
        assertThat(Converter.toHealthMonitorConfig(healthMonitor),
                equalTo(healthMonitorConfig));
        LoadBalancer loadBalancer = client.loadBalancerGet(loadBalancerId);
        LoadBalancerZkManager.LoadBalancerConfig postedLoadBalancerConfig =
                config.loadBalancerConfig.config;
        assertThat(Converter.toLoadBalancerConfig(loadBalancer),
                equalTo(postedLoadBalancerConfig));

        disassociatePoolHealthMonitor(pool);
    }

    @Test
    public void poolHealthMonitorMappingByDefaultTest()
            throws InvalidStateOperationException, MappingStatusException,
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

    @Test(expected=MappingViolationException.class)
    public void poolHealthMonitorMappingViolationTest()
            throws InvalidStateOperationException, MappingStatusException,
            MappingViolationException, SerializationException,
            StateAccessException {
        associatePoolHealthMonitor(pool);

        // If users try to update a pool which is already associated with a
        // health monitor populating the ID of another health monitor, it
        // throws MappingViolationException.
        UUID anotherHealthMonitorId = createStockHealthMonitor();
        pool.setHealthMonitorId(anotherHealthMonitorId);
        client.poolUpdate(pool);
    }

    @Test(expected = MappingStatusException.class)
    public void poolHealthMonitorMappingStatusTest()
        throws InvalidStateOperationException, MappingStatusException,
            MappingViolationException, SerializationException,
            StateAccessException {
        associatePoolHealthMonitor(pool);

        // Disassociate the mapping not to violate the existing mapping and
        // MappingViolationException is not thrown.
        pool.setHealthMonitorId(null);
        client.poolUpdate(pool);

        // Even after the Pool-HealthMonitor mapping is disassociated, if the
        // mapping status is not ACTIVE or INACTIVE, a MappingStatusException
        // is thrown in this layer. The resource handler catches it and returns
        // 503 ServiceUnavailable to the users.
        UUID anotherHealthMonitorId = createStockHealthMonitor();
        pool.setHealthMonitorId(anotherHealthMonitorId);
        client.poolUpdate(pool);
    }


    @Test
    public void poolDeletionTest()
            throws InvalidStateOperationException, MappingStatusException,
            MappingViolationException, SerializationException,
            StateAccessException {
        associatePoolHealthMonitor(pool);

        PoolHealthMonitorMappingConfig config =
                poolZkManager.getPoolHealthMonitorMapping(
                        poolId, healthMonitorId);
        assertThat(config, notNullValue());
        // Delete is done in `tearDown` method.
    }

    @Test
    public void healthMonitorDeletionTest()
            throws InvalidStateOperationException, MappingStatusException,
            MappingViolationException, SerializationException,
            StateAccessException {
        associatePoolHealthMonitor(pool);

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
            throws InvalidStateOperationException, MappingStatusException,
            MappingViolationException, SerializationException,
            StateAccessException {
        associatePoolHealthMonitor(pool);

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
        pool = emulateHealthMonitorActivation(pool);

        // Delete the pool member
        client.poolMemberDelete(poolMemberId);
        config = poolZkManager.getPoolHealthMonitorMapping(
                poolId, healthMonitorId);
        assertThat(config, notNullValue());
        assertThat(config.poolMemberConfigs.size(), equalTo(0));
        pool = client.poolGet(poolId);
        assertThat(pool.getMappingStatus(),
                equalTo(PoolHealthMonitorMappingStatus.PENDING_UPDATE));

        disassociatePoolHealthMonitor(pool);
    }

    @Test
    public void vipTest()
            throws InvalidStateOperationException, MappingStatusException,
            MappingViolationException, SerializationException,
            StateAccessException {
        associatePoolHealthMonitor(pool);

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
        pool = emulateHealthMonitorActivation(pool);

        // Delete the VIP
        client.vipDelete(vipId);
        config = poolZkManager.getPoolHealthMonitorMapping(
                poolId, healthMonitorId);
        assertThat(config, notNullValue());
        assertThat(config.vipConfigs.size(), equalTo(0));
        pool = client.poolGet(poolId);
        assertThat(pool.getMappingStatus(),
                equalTo(PoolHealthMonitorMappingStatus.PENDING_UPDATE));

        disassociatePoolHealthMonitor(pool);
    }

    @Test
    public void updatePoolTest()
            throws InvalidStateOperationException, MappingStatusException,
            MappingViolationException, SerializationException,
            StateAccessException {
        // Add another health monitor with the different parameters.
        UUID anotherHealthMonitorId = client.healthMonitorCreate(
                new HealthMonitor().setDelay(200)
                        .setMaxRetries(200).setTimeout(2000));
        assertFalse(poolZkManager.existsPoolHealthMonitorMapping(
                poolId, healthMonitorId));

        associatePoolHealthMonitor(pool);

        // Disassociate the existing mapping first.
        pool.setHealthMonitorId(null);
        client.poolUpdate(pool);
        pool = emulateHealthMonitorDeactivation(pool);

        // Update the pool with another health monitor
        pool.setHealthMonitorId(anotherHealthMonitorId);
        client.poolUpdate(pool);
        assertFalse(poolZkManager.existsPoolHealthMonitorMapping(
                poolId, healthMonitorId));
        pool = client.poolGet(poolId);
        assertThat(pool.getMappingStatus(),
                equalTo(PoolHealthMonitorMappingStatus.PENDING_CREATE));

        PoolHealthMonitorMappingConfig config =
                poolZkManager.getPoolHealthMonitorMapping(
                        poolId, anotherHealthMonitorId);
        assertThat(config, notNullValue());

        disassociatePoolHealthMonitor(pool);
    }

    @Test
    public void updateHealthMonitorTest()
            throws InvalidStateOperationException, MappingStatusException,
            MappingViolationException, SerializationException,
            StateAccessException {
        associatePoolHealthMonitor(pool);

        healthMonitor.setDelay(42);
        client.healthMonitorUpdate(healthMonitor);
        assertThat(healthMonitor.getDelay(), equalTo(42));
        pool = client.poolGet(poolId);
        assertThat(pool.getMappingStatus(),
                equalTo(PoolHealthMonitorMappingStatus.PENDING_UPDATE));

        disassociatePoolHealthMonitor(pool);
    }

    @Test
    public void updateVipTest()
            throws InvalidStateOperationException, MappingStatusException,
            MappingViolationException, SerializationException,
            StateAccessException {
        associatePoolHealthMonitor(pool);
        pool = emulateHealthMonitorActivation(pool);

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
        pool = client.poolGet(poolId);
        assertThat(pool.getMappingStatus(),
                equalTo(PoolHealthMonitorMappingStatus.PENDING_UPDATE));
        pool = emulateHealthMonitorActivation(pool);

        // Update the VIP with the ID of another pool
        Pool anotherPool = getStockPool(loadBalancerId);
        anotherPool.setHealthMonitorId(healthMonitorId);
        UUID anotherPoolId = client.poolCreate(anotherPool);
        anotherPool = emulateHealthMonitorActivation(anotherPool);

        vip.setPoolId(anotherPoolId);
        client.vipUpdate(vip);
        // Check if the updated vip is removed from the old mapping
        config = poolZkManager.getPoolHealthMonitorMapping(
                poolId, healthMonitorId);
        assertThat(config, notNullValue());
        assertThat(config.vipConfigs.size(), equalTo(0));

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

        disassociatePoolHealthMonitor(anotherPool);
    }

    @Test
    public void updatePoolMemberTest()
            throws InvalidStateOperationException, MappingStatusException,
            MappingViolationException, SerializationException,
            StateAccessException {
        associatePoolHealthMonitor(pool);
        pool = emulateHealthMonitorActivation(pool);

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
        pool = emulateHealthMonitorActivation(pool);

        // Update the pool member with the ID of another pool
        Pool anotherPool = getStockPool(loadBalancerId);
        anotherPool.setHealthMonitorId(healthMonitorId);
        UUID anotherPoolId = client.poolCreate(anotherPool);
        anotherPool = emulateHealthMonitorActivation(anotherPool);

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

        disassociatePoolHealthMonitor(anotherPool);
    }
}
