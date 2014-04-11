/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.api.l4lb;

import org.junit.Before;
import org.junit.runner.RunWith;
import org.midonet.api.l4lb.rest_api.HealthMonitorResource;
import org.midonet.api.l4lb.rest_api.LoadBalancerResource;
import org.midonet.api.l4lb.rest_api.PoolMemberResource;
import org.midonet.api.l4lb.rest_api.PoolResource;
import org.midonet.api.l4lb.rest_api.VipResource;
import org.midonet.api.network.Router;
import org.midonet.api.rest_api.ResourceFactory;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.cluster.DataClient;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.InvalidStateOperationException;
import org.midonet.midolman.state.StateAccessException;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import java.util.Random;
import java.util.UUID;
import javax.validation.Validator;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import static org.junit.Assert.assertEquals;

/**
 * The test class for the L4LB resource handlers.
 *
 * You should inherit this class and test cases, where methods of the resource
 * handlers MUST BE intercepted by Mockito.
 *
 * This class tests only the thin REST API layer and the end-to-end tests
 * through the client calls to the data store logic should be done by other
 * tests in `e2e` package.
 */
@RunWith(MockitoJUnitRunner.class)
public class L4LBResourceTestBase {

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    protected RestApiConfig config;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    protected SecurityContext context;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    protected ResourceFactory factory;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    protected Validator validator;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    protected UriInfo uriInfo;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    protected DataClient dataClient;

    protected LoadBalancerResource loadBalancerResource;
    protected HealthMonitorResource healthMonitorResource;
    protected PoolResource poolResource;
    protected VipResource vipResource;
    protected PoolMemberResource poolMemberResource;


    @Before
    public void setUp() throws Exception {
        loadBalancerResource = new LoadBalancerResource(config, uriInfo,
                context, dataClient, factory);
        healthMonitorResource = new HealthMonitorResource(config, uriInfo,
                context, dataClient, validator);
        poolResource = new PoolResource(config, uriInfo, context, dataClient,
                factory, validator);
        vipResource = new VipResource(config, uriInfo, context, dataClient,
                validator);
        poolMemberResource = new PoolMemberResource(config, uriInfo, context,
                dataClient, validator);
    }

    private static void assertCreated(Response response) {
        assertEquals(response.getStatus(),
                Response.Status.CREATED.getStatusCode());
    }

    protected static Router getStockRouter() {
        Router router = new Router();
        router.setId(UUID.randomUUID());
        router.setName("lb_test_router" + new Random().nextInt());
        router.setTenantId("dummy_tenant");
        return router;
    }

    protected static LoadBalancer getStockLoadBalancer() {
        LoadBalancer loadBalancer = new LoadBalancer();
        loadBalancer.setId(UUID.randomUUID());
        loadBalancer.setAdminStateUp(true);
        return loadBalancer;
    }

    protected LoadBalancer createStockLoadBalancer()
            throws InvalidStateOperationException, SerializationException,
            StateAccessException {
        LoadBalancer loadBalancer = getStockLoadBalancer();
        Response response = loadBalancerResource.create(loadBalancer);
        assertCreated(response);
        return loadBalancerResource.get(loadBalancer.getId());
    }

    protected LoadBalancer updateLoadBalancer(LoadBalancer loadBalancer)
            throws InvalidStateOperationException, SerializationException,
            StateAccessException {
        loadBalancerResource.update(loadBalancer.getId(), loadBalancer);
        return loadBalancerResource.get(loadBalancer.getId());
    }

    protected static HealthMonitor getStockHealthMonitor() {
        HealthMonitor healthMonitor = new HealthMonitor();
        healthMonitor.setId(UUID.randomUUID());
        healthMonitor.setType("TCP");
        healthMonitor.setDelay(5);
        healthMonitor.setTimeout(10);
        healthMonitor.setMaxRetries(10);
        healthMonitor.setAdminStateUp(true);
        return healthMonitor;
    }

    protected HealthMonitor createStockHealthMonitor()
            throws SerializationException, StateAccessException {
        HealthMonitor healthMonitor = getStockHealthMonitor();
        Response response =  healthMonitorResource.create(healthMonitor);
        assertCreated(response);
        return healthMonitorResource.get(healthMonitor.getId());
    }

    protected HealthMonitor updateHealthMonitor(
            HealthMonitor healthMonitor)
            throws SerializationException, StateAccessException {
        healthMonitorResource.update(healthMonitor.getId(), healthMonitor);
        return healthMonitorResource.get(healthMonitor.getId());
    }

    protected static VIP getStockVip(UUID poolId) {
        VIP vip = new VIP();
        vip.setId(UUID.randomUUID());
        vip.setPoolId(poolId);
        vip.setAddress("192.168.100.1");
        vip.setProtocolPort(80);
        vip.setSessionPersistence("SOURCE_IP");
        vip.setAdminStateUp(true);
        return vip;
    }

    protected VIP createStockVip(UUID poolId)
            throws InvalidStateOperationException, SerializationException,
            StateAccessException {
        VIP vip = getStockVip(poolId);
        Response response = vipResource.create(vip);
        assertCreated(response);
        return vipResource.get(vip.getId());
    }

    protected VIP updateVip(VIP vip)
            throws InvalidStateOperationException, SerializationException,
            StateAccessException {
        vipResource.update(vip.getId(), vip);
        return vipResource.get(vip.getId());
    }

    protected static Pool getStockPool(UUID loadBalancerId) {
        Pool pool = new Pool();
        pool.setId(UUID.randomUUID());
        pool.setLoadBalancerId(loadBalancerId);
        pool.setProtocol("TCP");
        pool.setAdminStateUp(true);
        pool.setLbMethod("ROUND_ROBIN");
        pool.setStatus("ACTIVE");
        return pool;
    }

    protected Pool createStockPool(UUID loadBalancerId)
            throws SerializationException, StateAccessException {
        Pool pool = getStockPool(loadBalancerId);
        Response response = poolResource.create(pool);
        assertCreated(response);
        return poolResource.get(pool.getId());
    }

    protected Pool updatePool(Pool pool)
            throws SerializationException, StateAccessException {
        poolResource.update(pool.getId(), pool);
        return poolResource.get(pool.getId());
    }

    protected static PoolMember getStockPoolMember(UUID poolId) {
        PoolMember poolMember = new PoolMember();
        poolMember.setId(UUID.randomUUID());
        poolMember.setAddress("192.168.10.1");
        poolMember.setPoolId(poolId);
        poolMember.setProtocolPort(80);
        poolMember.setStatus("ACTIVE");
        poolMember.setWeight(100);
        poolMember.setAdminStateUp(true);
        return poolMember;
    }

    protected PoolMember createStockPoolMember(UUID poolId)
            throws SerializationException, StateAccessException {
        PoolMember poolMember = getStockPoolMember(poolId);
        Response response = poolMemberResource.create(poolMember);
        assertCreated(response);
        return poolMemberResource.get(poolMember.getId());
    }

    protected PoolMember updatePoolMember(PoolMember poolMember)
            throws InvalidStateOperationException, SerializationException,
            StateAccessException {
        poolMemberResource.update(poolMember.getId(), poolMember);
        return poolMemberResource.get(poolMember.getId());
    }
}
