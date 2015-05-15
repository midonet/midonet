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

package org.midonet.api.l4lb;

import java.util.UUID;

import javax.validation.Validator;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import org.junit.Before;
import org.mockito.Answers;
import org.mockito.Mock;

import org.midonet.api.l4lb.rest_api.HealthMonitorResource;
import org.midonet.api.l4lb.rest_api.LoadBalancerResource;
import org.midonet.api.l4lb.rest_api.PoolMemberResource;
import org.midonet.api.l4lb.rest_api.PoolResource;
import org.midonet.api.l4lb.rest_api.VipResource;
import org.midonet.api.rest_api.ResourceFactory;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.rest_api.models.HealthMonitor;
import org.midonet.cluster.rest_api.models.Pool;
import org.midonet.midolman.state.l4lb.LBStatus;
import org.midonet.midolman.state.l4lb.PoolLBMethod;
import org.midonet.midolman.state.l4lb.PoolProtocol;

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

    protected static HealthMonitor getStockHealthMonitor() {
        HealthMonitor healthMonitor = new HealthMonitor();
        healthMonitor.id = UUID.randomUUID();
        healthMonitor.type = "TCP";
        healthMonitor.delay = 5;
        healthMonitor.timeout = 10;
        healthMonitor.maxRetries = 10;
        healthMonitor.adminStateUp = true;
        return healthMonitor;
    }

    protected static Pool getStockPool(UUID loadBalancerId) {
        Pool pool = new Pool();
        pool.id = UUID.randomUUID();
        pool.loadBalancerId = loadBalancerId;
        pool.protocol = PoolProtocol.TCP;
        pool.adminStateUp = true;
        pool.lbMethod = PoolLBMethod.ROUND_ROBIN;
        pool.status = LBStatus.ACTIVE;
        return pool;
    }

}
