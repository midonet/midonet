/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.cluster.data.l4lb;

import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.zookeeper.KeeperException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.midonet.cluster.data.Converter;
import org.midonet.cluster.data.neutron.LBaaSApi;
import org.midonet.cluster.data.neutron.NeutronClusterModule;
import org.midonet.midolman.Setup;
import org.midonet.midolman.config.MidolmanConfig;
import org.midonet.midolman.config.ZookeeperConfig;
import org.midonet.midolman.guice.CacheModule;
import org.midonet.midolman.guice.config.ConfigProviderModule;
import org.midonet.midolman.guice.config.TypedConfigModule;
import org.midonet.midolman.guice.serialization.SerializationModule;
import org.midonet.midolman.guice.zookeeper.MockZookeeperConnectionModule;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.InvalidStateOperationException;
import org.midonet.midolman.state.PoolHealthMonitorMappingStatus;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.l4lb.MappingViolationException;
import org.midonet.midolman.state.l4lb.MappingStatusException;
import org.midonet.midolman.state.l4lb.PoolLBMethod;
import org.midonet.midolman.state.l4lb.PoolProtocol;
import org.midonet.midolman.state.l4lb.VipSessionPersistence;
import org.midonet.midolman.state.zkManagers.HealthMonitorZkManager.HealthMonitorConfig;
import org.midonet.midolman.state.zkManagers.LoadBalancerZkManager;
import org.midonet.midolman.state.zkManagers.PoolZkManager;
import org.midonet.midolman.state.zkManagers.PoolMemberZkManager.PoolMemberConfig;
import org.midonet.midolman.state.zkManagers.PoolZkManager.PoolHealthMonitorMappingConfig;
import org.midonet.midolman.state.zkManagers.VipZkManager.VipConfig;
import org.midonet.midolman.version.guice.VersionModule;

import java.util.Arrays;
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PoolHealthMonitorMappingsTest {

    private PoolZkManager poolZkManager;
    private UUID loadBalancerId;
    private HealthMonitor healthMonitor;
    private UUID healthMonitorId;
    private Pool pool;
    private UUID poolId;

    @Inject protected LBaaSApi api;
    Injector injector = null;
    String zkRoot = "/test/v3/midolman";

    protected HealthMonitor getStockHealthMonitor() {
        return new HealthMonitor()
                .setDelay(100)
                .setMaxRetries(100)
                .setTimeout(1000);
    }

    protected UUID createStockHealthMonitor()
            throws SerializationException, StateAccessException {
        return api.healthMonitorCreate(getStockHealthMonitor());
    }

    protected LoadBalancer getStockLoadBalancer() {
        return new LoadBalancer();
    }

    protected UUID createStockLoadBalancer()
            throws InvalidStateOperationException, SerializationException,
            StateAccessException {
        return api.loadBalancerCreate(getStockLoadBalancer());
    }

    protected Pool getStockPool(UUID loadBalancerId) {
        return new Pool().setLoadBalancerId(loadBalancerId)
                .setLbMethod(PoolLBMethod.ROUND_ROBIN)
                .setProtocol(PoolProtocol.TCP);
    }

    protected PoolZkManager getPoolZkManager() {
        return injector.getInstance(PoolZkManager.class);
    }

    protected UUID createStockPool(UUID loadBalancerId)
            throws MappingStatusException, SerializationException,
            StateAccessException {
        return api.poolCreate(getStockPool(loadBalancerId));
    }

    protected PoolMember getStockPoolMember(UUID poolId) {
        return new PoolMember()
                .setPoolId(poolId)
                .setAddress("192.168.10.1")
                .setProtocolPort(80)
                .setWeight(100);
    }

    protected UUID createStockPoolMember(UUID poolId)
            throws MappingStatusException, SerializationException,
            StateAccessException {
        return api.poolMemberCreate(getStockPoolMember(poolId));
    }

    protected VIP getStockVip(UUID poolId) {
        return new VIP()
                .setAddress("192.168.100.1")
                .setPoolId(poolId)
                .setProtocolPort(80)
                .setSessionPersistence(VipSessionPersistence.SOURCE_IP);
    }

    protected UUID createStockVip(UUID poolId)
            throws MappingStatusException, SerializationException,
            StateAccessException {
        return api.vipCreate(getStockVip(poolId));
    }

    HierarchicalConfiguration fillConfig(HierarchicalConfiguration config) {
        config.addNodes(ZookeeperConfig.GROUP_NAME,
                Arrays.asList(new HierarchicalConfiguration.Node
                        ("midolman_root_key", zkRoot)));
        return config;
    }

    Directory zkDir() {
        return injector.getInstance(Directory.class);
    }

    @Before
    public void setUp()
            throws InvalidStateOperationException, MappingStatusException,
            SerializationException, StateAccessException,
            InterruptedException, KeeperException {
        HierarchicalConfiguration config = fillConfig(
                new HierarchicalConfiguration());
        injector = Guice.createInjector(
                new VersionModule(),
                new SerializationModule(),
                new ConfigProviderModule(config),
                new MockZookeeperConnectionModule(),
                new TypedConfigModule<>(MidolmanConfig.class),
                new CacheModule(),
                new NeutronClusterModule()
        );

        injector.injectMembers(this);
        Setup.ensureZkDirectoryStructureExists(zkDir(), zkRoot);
        poolZkManager = getPoolZkManager();
        loadBalancerId = createStockLoadBalancer();
        // Add a health monitor
        healthMonitor = getStockHealthMonitor();
        healthMonitorId = api.healthMonitorCreate(healthMonitor);
        // Add a pool
        pool = getStockPool(loadBalancerId);
        poolId = api.poolCreate(pool);
    }

    @After
    public void tearDown()
            throws  MappingStatusException, SerializationException,
            StateAccessException {
        // Delete the pool
        pool = emulateHealthMonitorActivation(pool);
        api.poolDelete(poolId);
        assertFalse(poolZkManager.existsPoolHealthMonitorMapping(
                poolId, healthMonitorId));
    }

    private Pool emulateHealthMonitor(
            Pool pool,
            PoolHealthMonitorMappingStatus mappingStatus)
            throws MappingStatusException, SerializationException,
            StateAccessException {
        api.poolSetMapStatus(pool.getId(), mappingStatus);
        Pool updatedPool = api.poolGet(pool.getId());
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

    private void associatePoolHealthMonitorWithoutActivation(Pool pool)
            throws MappingStatusException, MappingViolationException,
            SerializationException, StateAccessException {
        // Associate the pool with the health monitor
        pool.setHealthMonitorId(healthMonitorId);
        api.poolUpdate(pool);
        assertTrue(poolZkManager.existsPoolHealthMonitorMapping(
                poolId, healthMonitorId));

        pool = api.poolGet(pool.getId());
        assertThat(pool.getMappingStatus(),
                equalTo(PoolHealthMonitorMappingStatus.PENDING_CREATE));
    }

    private void associatePoolHealthMonitor(Pool pool)
            throws MappingStatusException, MappingViolationException,
            SerializationException, StateAccessException {
        associatePoolHealthMonitorWithoutActivation(pool);
        pool = emulateHealthMonitorActivation(pool);
    }

    private void disassociatePoolHealthMonitor(Pool pool)
            throws MappingStatusException, MappingViolationException,
            SerializationException, StateAccessException {
        pool = emulateHealthMonitorActivation(pool);
        // Disassociate the pool from the health monitor.
        pool.setHealthMonitorId(null);
        api.poolUpdate(pool);

        pool = api.poolGet(pool.getId());
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
        HealthMonitor healthMonitor = api.healthMonitorGet(healthMonitorId);
        HealthMonitorConfig healthMonitorConfig =
                config.healthMonitorConfig.config;
        assertThat(Converter.toHealthMonitorConfig(healthMonitor),
                equalTo(healthMonitorConfig));
        LoadBalancer loadBalancer = api.loadBalancerGet(loadBalancerId);
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
        UUID newPoolId = api.poolCreate(newPool);
        newPool = api.poolGet(newPoolId);
        assertThat(newPool.getHealthMonitorId(),
                equalTo(healthMonitorId));
        assertThat(newPool.getMappingStatus(),
                equalTo(PoolHealthMonitorMappingStatus.PENDING_CREATE));
    }

    @Test(expected = MappingViolationException.class)
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
        api.poolUpdate(pool);
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
        api.poolUpdate(pool);

        // Even after the Pool-HealthMonitor mapping is disassociated, if the
        // mapping status is not ACTIVE or INACTIVE, a MappingStatusException
        // is thrown in this layer. The resource handler catches it and returns
        // 503 ServiceUnavailable to the users.
        UUID anotherHealthMonitorId = createStockHealthMonitor();
        pool.setHealthMonitorId(anotherHealthMonitorId);
        api.poolUpdate(pool);
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
        api.healthMonitorDelete(healthMonitorId);
        assertFalse(poolZkManager.existsPoolHealthMonitorMapping(
                poolId, healthMonitorId));
        pool = api.poolGet(poolId);
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
        PoolMember poolMember = api.poolMemberGet(poolMemberId);
        PoolMemberConfig poolMemberConfig =
                Converter.toPoolMemberConfig(poolMember);
        PoolMemberConfig addedPoolMemberConfig =
                config.poolMemberConfigs.get(0).config;
        assertThat(poolMemberConfig, equalTo(addedPoolMemberConfig));
        pool = api.poolGet(poolId);
        assertThat(pool.getMappingStatus(),
                equalTo(PoolHealthMonitorMappingStatus.PENDING_UPDATE));
        pool = emulateHealthMonitorActivation(pool);

        // Delete the pool member
        api.poolMemberDelete(poolMemberId);
        config = poolZkManager.getPoolHealthMonitorMapping(
                poolId, healthMonitorId);
        assertThat(config, notNullValue());
        assertThat(config.poolMemberConfigs.size(), equalTo(0));
        pool = api.poolGet(poolId);
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
        VIP vip = api.vipGet(vipId);
        VipConfig vipConfig = Converter.toVipConfig(vip);
        VipConfig addedVipConfig = config.vipConfigs.get(0).config;
        assertThat(vipConfig, equalTo(addedVipConfig));
        pool = api.poolGet(poolId);
        assertThat(pool.getMappingStatus(),
                equalTo(PoolHealthMonitorMappingStatus.PENDING_UPDATE));
        pool = emulateHealthMonitorActivation(pool);

        // Delete the VIP
        api.vipDelete(vipId);
        config = poolZkManager.getPoolHealthMonitorMapping(
                poolId, healthMonitorId);
        assertThat(config, notNullValue());
        assertThat(config.vipConfigs.size(), equalTo(0));
        pool = api.poolGet(poolId);
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
        UUID anotherHealthMonitorId = api.healthMonitorCreate(
                new HealthMonitor().setDelay(200)
                        .setMaxRetries(200).setTimeout(2000));
        assertFalse(poolZkManager.existsPoolHealthMonitorMapping(
                poolId, healthMonitorId));

        associatePoolHealthMonitor(pool);

        // Disassociate the existing mapping first.
        pool.setHealthMonitorId(null);
        api.poolUpdate(pool);
        pool = emulateHealthMonitorDeactivation(pool);

        // Update the pool with another health monitor
        pool.setHealthMonitorId(anotherHealthMonitorId);
        api.poolUpdate(pool);
        assertFalse(poolZkManager.existsPoolHealthMonitorMapping(
                poolId, healthMonitorId));
        pool = api.poolGet(poolId);
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
        api.healthMonitorUpdate(healthMonitor);
        assertThat(healthMonitor.getDelay(), equalTo(42));
        pool = api.poolGet(poolId);
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

        // Create a VIP with the pool ID
        VIP vip = getStockVip(poolId);
        UUID vipId = api.vipCreate(vip);
        PoolHealthMonitorMappingConfig config =
                poolZkManager.getPoolHealthMonitorMapping(
                        poolId, healthMonitorId);
        assertThat(config, notNullValue());
        assertThat(config.vipConfigs.size(), equalTo(1));
        vip = api.vipGet(vipId);
        VipConfig vipConfig = Converter.toVipConfig(vip);
        VipConfig addedVipConfig = config.vipConfigs.get(0).config;
        assertThat(vipConfig, equalTo(addedVipConfig));
        pool = api.poolGet(poolId);
        assertThat(pool.getMappingStatus(),
                equalTo(PoolHealthMonitorMappingStatus.PENDING_UPDATE));
        pool = emulateHealthMonitorActivation(pool);

        // Update the VIP with the ID of another pool
        Pool anotherPool = getStockPool(loadBalancerId);
        anotherPool.setHealthMonitorId(healthMonitorId);
        UUID anotherPoolId = api.poolCreate(anotherPool);
        anotherPool = emulateHealthMonitorActivation(anotherPool);

        vip.setPoolId(anotherPoolId);
        api.vipUpdate(vip);
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
        vip = api.vipGet(vipId);
        vipConfig = Converter.toVipConfig(vip);
        VipConfig updatedVipConfig = anotherConfig.vipConfigs.get(0).config;
        assertThat(vipConfig, equalTo(updatedVipConfig));
        anotherPool = api.poolGet(anotherPoolId);
        assertThat(anotherPool.getMappingStatus(),
                equalTo(PoolHealthMonitorMappingStatus.PENDING_UPDATE));

        disassociatePoolHealthMonitor(anotherPool);
    }

    @Test(expected = MappingStatusException.class)
    public void createVIPWhenMappingStatusIsNotStable() throws Exception {
        associatePoolHealthMonitorWithoutActivation(pool);

        // Create a VIP with the pool ID
        VIP vip = getStockVip(poolId);
        api.vipCreate(vip);
    }

    @Test(expected = MappingStatusException.class)
    public void updateVIPWhenMappingStatusIsNotStable() throws Exception {
        associatePoolHealthMonitor(pool);

        // Create a VIP with the pool ID
        VIP vip = getStockVip(poolId);
        UUID vipId = api.vipCreate(vip);
        PoolHealthMonitorMappingConfig config =
                poolZkManager.getPoolHealthMonitorMapping(
                        poolId, healthMonitorId);
        assertThat(config, notNullValue());
        assertThat(config.vipConfigs.size(), equalTo(1));
        vip = api.vipGet(vipId);
        VipConfig vipConfig = Converter.toVipConfig(vip);
        VipConfig addedVipConfig = config.vipConfigs.get(0).config;
        assertThat(vipConfig, equalTo(addedVipConfig));
        pool = api.poolGet(poolId);
        assertThat(pool.getMappingStatus(),
                equalTo(PoolHealthMonitorMappingStatus.PENDING_UPDATE));

        // Try to update the VIP when the mapping status is PENDING_UPDATE and
        // MappingStatusException is thrown.
        vip.setProtocolPort(443);
        api.vipUpdate(vip);
    }

    @Test
    public void updatePoolMemberTest()
            throws InvalidStateOperationException, MappingStatusException,
            MappingViolationException, SerializationException,
            StateAccessException {
        associatePoolHealthMonitor(pool);

        // Create a pool member with the pool ID
        PoolMember poolMember = getStockPoolMember(poolId);
        UUID poolMemberId = api.poolMemberCreate(poolMember);
        PoolHealthMonitorMappingConfig config =
                poolZkManager.getPoolHealthMonitorMapping(
                        poolId, healthMonitorId);
        assertThat(config, notNullValue());
        assertThat(config.poolMemberConfigs.size(), equalTo(1));
        poolMember = api.poolMemberGet(poolMemberId);
        PoolMemberConfig poolMemberConfig =
                Converter.toPoolMemberConfig(poolMember);
        PoolMemberConfig addedPoolMemberConfig =
                config.poolMemberConfigs.get(0).config;
        assertThat(poolMemberConfig, equalTo(addedPoolMemberConfig));
        pool = api.poolGet(poolId);
        assertThat(pool.getMappingStatus(),
                equalTo(PoolHealthMonitorMappingStatus.PENDING_UPDATE));
        pool = emulateHealthMonitorActivation(pool);

        // Update the pool member with the ID of another pool
        Pool anotherPool = getStockPool(loadBalancerId);
        anotherPool.setHealthMonitorId(healthMonitorId);
        UUID anotherPoolId = api.poolCreate(anotherPool);
        anotherPool = emulateHealthMonitorActivation(anotherPool);

        poolMember.setPoolId(anotherPoolId);
        api.poolMemberUpdate(poolMember);
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
        poolMember = api.poolMemberGet(poolMemberId);
        poolMemberConfig = Converter.toPoolMemberConfig(poolMember);
        addedPoolMemberConfig = anotherConfig.poolMemberConfigs.get(0).config;
        assertThat(poolMemberConfig, equalTo(addedPoolMemberConfig));
        anotherPool = api.poolGet(anotherPoolId);
        assertThat(anotherPool.getMappingStatus(),
                equalTo(PoolHealthMonitorMappingStatus.PENDING_UPDATE));

        disassociatePoolHealthMonitor(anotherPool);
    }

    @Test(expected = MappingStatusException.class)
    public void createPoolMemberWhenMappingStatusIsNotStable()
            throws Exception {
        associatePoolHealthMonitorWithoutActivation(pool);

        // Create a VIP with the pool ID
        PoolMember poolMember = getStockPoolMember(poolId);
        api.poolMemberCreate(poolMember);
    }

    @Test(expected = MappingStatusException.class)
    public void updatePoolMemberWhenMappingStatusIsNotStable()
            throws Exception {
        associatePoolHealthMonitor(pool);

        // Create a VIP with the pool ID
        PoolMember poolMember = getStockPoolMember(poolId);
        UUID poolMemberId = api.poolMemberCreate(poolMember);
        PoolHealthMonitorMappingConfig config =
                poolZkManager.getPoolHealthMonitorMapping(
                        poolId, healthMonitorId);
        assertThat(config, notNullValue());
        assertThat(config.poolMemberConfigs.size(), equalTo(1));
        poolMember = api.poolMemberGet(poolMemberId);
        PoolMemberConfig poolMemberConfig =
                Converter.toPoolMemberConfig(poolMember);
        PoolMemberConfig addedPoolMemberConfig =
                config.poolMemberConfigs.get(0).config;
        assertThat(poolMemberConfig, equalTo(addedPoolMemberConfig));
        pool = api.poolGet(poolId);
        assertThat(pool.getMappingStatus(),
                equalTo(PoolHealthMonitorMappingStatus.PENDING_UPDATE));

        // Try to update the VIP when the mapping status is PENDING_UPDATE and
        // MappingStatusException is thrown.
        poolMember.setProtocolPort(443);
        api.poolMemberUpdate(poolMember);
    }
}
