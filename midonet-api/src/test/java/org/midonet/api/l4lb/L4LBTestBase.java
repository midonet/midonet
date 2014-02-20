/*
 * Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.api.l4lb;

import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import com.sun.jersey.api.client.ClientResponse;
import org.midonet.api.rest_api.DtoWebResource;
import org.midonet.api.rest_api.FuncTest;
import org.midonet.api.rest_api.RestApiTestBase;
import org.midonet.api.rest_api.Topology;
import org.midonet.client.dto.DtoApplication;
import org.midonet.client.dto.DtoHealthMonitor;
import org.midonet.client.dto.DtoLoadBalancer;
import org.midonet.client.dto.DtoPool;
import org.midonet.client.dto.DtoPoolMember;
import org.midonet.client.dto.DtoRouter;
import org.midonet.client.dto.DtoVip;

import static org.junit.Assert.assertEquals;
import static org.midonet.api.VendorMediaType.*;
import static org.midonet.cluster.data.l4lb.VIP.VIP_SOURCE_IP;

public class L4LBTestBase extends RestApiTestBase {

    protected DtoWebResource dtoWebResource;
    protected Topology topology;
    protected DtoApplication app;

    protected URI topLevelRoutersUri;
    protected URI topLevelLoadBalancersUri;
    protected URI topLevelHealthMonitorsUri;
    protected URI topLevelVipsUri;
    protected URI topLevelPoolsUri;
    protected URI topLevelPoolMembersUri;

    public L4LBTestBase() {
        super(FuncTest.appDesc);
    }

    public void setUp() {
        dtoWebResource = new DtoWebResource(resource());
        topology = new Topology.Builder(dtoWebResource).build();
        app = topology.getApplication();

        topLevelRoutersUri = app.getRouters();
        topLevelLoadBalancersUri = app.getLoadBalancers();
        topLevelHealthMonitorsUri = app.getHealthMonitors();
        topLevelVipsUri = app.getVips();
        topLevelPoolsUri = app.getPools();
        topLevelPoolMembersUri = app.getPoolMembers();
    }

    protected DtoRouter getRouterV2(URI uri) {
        return dtoWebResource.getAndVerifyOk(
                uri, APPLICATION_ROUTER_JSON, DtoRouter.class);
    }

    protected DtoRouter postRouter(DtoRouter router) {
        return dtoWebResource.postAndVerifyCreated(topLevelRoutersUri,
                APPLICATION_ROUTER_JSON, router, DtoRouter.class);
    }

    protected DtoRouter updateRouterV2(DtoRouter router) {
        return dtoWebResource.putAndVerifyNoContent(router.getUri(),
                APPLICATION_ROUTER_JSON_V2, router, DtoRouter.class);
    }

    protected DtoRouter getStockRouter() {
        DtoRouter router = new DtoRouter();
        router.setId(UUID.randomUUID());
        router.setName("lb_test_router" + new Random().nextInt());
        router.setTenantId("dummy_tenant");
        return router;
    }

    protected DtoRouter createStockRouter() {
        return postRouter(getStockRouter());
    }

    protected DtoLoadBalancer getLoadBalancer(URI loadBalancerUri) {
        return dtoWebResource.getAndVerifyOk(loadBalancerUri,
                APPLICATION_LOAD_BALANCER_JSON, DtoLoadBalancer.class);
    }

    protected DtoLoadBalancer postLoadBalancer(DtoLoadBalancer loadBalancer) {
        return dtoWebResource.postAndVerifyCreated(topLevelLoadBalancersUri,
                APPLICATION_LOAD_BALANCER_JSON,
                loadBalancer, DtoLoadBalancer.class);
    }

    protected DtoLoadBalancer updateLoadBalancer(
            DtoLoadBalancer loadBalancer) {
        return dtoWebResource.putAndVerifyNoContent(loadBalancer.getUri(),
                APPLICATION_LOAD_BALANCER_JSON,
                loadBalancer, DtoLoadBalancer.class);
    }

    protected void deleteLoadBalancer(URI uri) {
        dtoWebResource.deleteAndVerifyNoContent(
                uri, APPLICATION_LOAD_BALANCER_JSON);
    }

    protected DtoLoadBalancer getStockLoadBalancer() {
        DtoLoadBalancer loadBalancer = new DtoLoadBalancer();
        // NOTE(tfukushima): Populating UUID of the load balancer because
        //   the API can create the resource with the specified UUID,
        //   which is very useful for the identical checks.
        loadBalancer.setId(UUID.randomUUID());
        loadBalancer.setAdminStateUp(true);
        return loadBalancer;
    }

    protected DtoLoadBalancer createStockLoadBalancer() {
        return postLoadBalancer(getStockLoadBalancer());
    }

    protected DtoHealthMonitor getHealthMonitor(URI healthMonitorUri) {
        return dtoWebResource.getAndVerifyOk(
                healthMonitorUri,
                APPLICATION_HEALTH_MONITOR_JSON,
                DtoHealthMonitor.class);
    }

    protected DtoHealthMonitor postHealthMonitor(DtoHealthMonitor healthMonitor) {
        return dtoWebResource.postAndVerifyCreated(topLevelHealthMonitorsUri,
                APPLICATION_HEALTH_MONITOR_JSON,
                healthMonitor, DtoHealthMonitor.class);
    }

    protected DtoHealthMonitor updateHealthMonitor(
            DtoHealthMonitor healthMonitor) {
        return dtoWebResource.putAndVerifyNoContent(healthMonitor.getUri(),
                APPLICATION_HEALTH_MONITOR_JSON,
                healthMonitor,
                DtoHealthMonitor.class);
    }

    protected void deleteHealthMonitor(URI healthMonitorUri) {
        dtoWebResource.deleteAndVerifyNoContent(healthMonitorUri,
                APPLICATION_HEALTH_MONITOR_JSON);
    }

    protected DtoHealthMonitor getStockHealthMonitor() {
        DtoHealthMonitor healthMonitor = new DtoHealthMonitor();
        // NOTE(tfukushima): Populating UUID of the healthe monitor because
        //   the API can create the resource with the specified UUID,
        //   which is very useful for the identical checks.
        healthMonitor.setId(UUID.randomUUID());
        healthMonitor.setType("TCP");
        healthMonitor.setDelay(5);
        healthMonitor.setTimeout(10);
        healthMonitor.setMaxRetries(10);
        healthMonitor.setAdminStateUp(true);
        healthMonitor.setStatus("ACTIVE");
        return healthMonitor;
    }

    protected DtoHealthMonitor createStockHealthMonitor() {
        return postHealthMonitor(getStockHealthMonitor());
    }

    protected DtoVip getVip(URI vipUri) {
        return dtoWebResource.getAndVerifyOk(
                vipUri, APPLICATION_VIP_JSON, DtoVip.class);
    }

    protected DtoVip[] getVips(URI vipsUri) {
        return dtoWebResource.getAndVerifyOk(
                vipsUri, APPLICATION_VIP_COLLECTION_JSON, DtoVip[].class);
    }

    protected DtoVip postVip(DtoVip vip) {
        return dtoWebResource.postAndVerifyCreated(topLevelVipsUri,
                APPLICATION_VIP_JSON, vip, DtoVip.class);
    }

    protected DtoVip updateVip(DtoVip vip) {
        return dtoWebResource.putAndVerifyNoContent(vip.getUri(),
                APPLICATION_VIP_JSON, vip, DtoVip.class);
    }

    protected void deleteVip(URI vipUri) {
        dtoWebResource.deleteAndVerifyNoContent(
                vipUri, APPLICATION_VIP_JSON);
    }

    protected DtoVip getStockVip(UUID poolId) {
        DtoVip vip = new DtoVip();
        // NOTE(tfukushima): Populating UUID of the load balancer because
        //   the API can create the resource with the specified UUID,
        //   which is very useful for the identical checks.
        vip.setId(UUID.randomUUID());
        vip.setPoolId(poolId);
        vip.setAddress("192.168.100.1");
        vip.setProtocolPort(80);
        vip.setSessionPersistence(VIP_SOURCE_IP);
        vip.setAdminStateUp(true);

        return vip;
    }

    protected DtoVip createStockVip(UUID poolId) {
        return postVip(getStockVip((poolId)));
    }

    protected DtoPool[] getPools(URI poolsUri) {
        return dtoWebResource.getAndVerifyOk(
                poolsUri, APPLICATION_POOL_COLLECTION_JSON, DtoPool[].class);
    }

    protected DtoPool getPool(URI poolUri) {
        ClientResponse response = resource().uri(poolUri)
                .type(APPLICATION_POOL_JSON)
                .get(ClientResponse.class);
        assertEquals(200, response.getStatus());
        return response.getEntity(DtoPool.class);
    }

    protected DtoPool postPool(DtoPool pool) {
        return dtoWebResource.postAndVerifyCreated(topLevelPoolsUri,
                APPLICATION_POOL_JSON, pool, DtoPool.class);
    }

    protected DtoPool updatePool(DtoPool pool) {
        return dtoWebResource.putAndVerifyNoContent(pool.getUri(),
                APPLICATION_POOL_JSON,
                pool, DtoPool.class);
    }

    protected void deletePool(URI poolUri) {
        ClientResponse response = resource().uri(poolUri)
                .type(APPLICATION_POOL_JSON)
                .delete(ClientResponse.class);
        assertEquals(204, response.getStatus());
    }

    protected DtoPool getStockPool(UUID loadBalancerId) {
        DtoPool pool = new DtoPool();
        // NOTE(tfukushima): Populating UUID of the pool because the API
        //   can create the resource with the specified UUID, which is
        //   very useful for the identical checks.
        pool.setId(UUID.randomUUID());
        pool.setLoadBalancerId(loadBalancerId);
        pool.setAdminStateUp(true);
        pool.setDescription("a big ol pool");
        pool.setName("BIGPOOL");
        pool.setProtocol("TCP");
        return pool;
    }

    protected DtoPool createStockPool(UUID loadBalancerId) {
        DtoPool pool = getStockPool(loadBalancerId);
        return postPool(pool);
    }

    protected DtoPoolMember getPoolMember(URI uri) {
        return dtoWebResource.getAndVerifyOk(uri,
                APPLICATION_POOL_MEMBER_JSON,
                DtoPoolMember.class);
    }

    protected DtoPoolMember[] getPoolMembers(URI uri) {
        return dtoWebResource.getAndVerifyOk(uri,
                APPLICATION_POOL_MEMBER_COLLECTION_JSON,
                DtoPoolMember[].class);
    }

    protected DtoPoolMember postPoolMember(DtoPoolMember poolMember) {
        return dtoWebResource.postAndVerifyCreated(topLevelPoolMembersUri,
                APPLICATION_POOL_MEMBER_JSON,
                poolMember, DtoPoolMember.class);
    }

    protected DtoPoolMember updatePoolMember(DtoPoolMember poolMember) {
        return dtoWebResource.putAndVerifyNoContent(poolMember.getUri(),
                APPLICATION_POOL_MEMBER_JSON,
                poolMember, DtoPoolMember.class);
    }

    protected void deletePoolMember(URI poolMemberUri) {
        dtoWebResource.deleteAndVerifyNoContent(
                poolMemberUri, APPLICATION_POOL_MEMBER_JSON);
    }

    protected DtoPoolMember getStockPoolMember() {
        return getStockPoolMember(null);
    }

    protected DtoPoolMember getStockPoolMember(UUID poolId) {
        DtoPoolMember poolMember = new DtoPoolMember();
        // NOTE(tfukushima): Populating UUID of the pool member because
        //   the API can create the resource with the specified UUID,
        //   which is very useful for the identical checks.
        poolMember.setId(UUID.randomUUID());
        poolMember.setAddress("10.0.0.1");
        poolMember.setPoolId(poolId);
        poolMember.setProtocolPort(80);
        poolMember.setStatus(DtoPoolMember.Status.UP);
        poolMember.setWeight(100);
        poolMember.setAdminStateUp(true);
        return poolMember;
    }

    protected DtoPoolMember createStockPoolMember(UUID poolId) {
        return postPoolMember(getStockPoolMember(poolId));
    }

    /**
     * Asserts that the two specified arrays have the same members,
     * but ignores order.
     *
     * @param expected An expected array.
     * @param actual An actual array to be asserted.
     * @param <T> A type of elements in the arrays.
     */
    protected <T> void assertArrayMembersEqual(T[] expected, T[] actual) {
        assertEquals(expected.length, actual.length);
        Set<T> expectedSet = new HashSet<>(Arrays.asList(expected));
        Set<T> actualSet = new HashSet<>(Arrays.asList(actual));
        assertEquals(expectedSet, actualSet);
    }
}
