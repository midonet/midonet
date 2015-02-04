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
package org.midonet.api.neutron.loadbalancer;

import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import com.google.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.cluster.data.neutron.LoadBalancerApi;
import org.midonet.event.neutron.PoolHealthMonitorEvent;

public class PoolHealthMonitorResource extends AbstractResource {

    private static final Logger LOG = LoggerFactory.getLogger(
        PoolHealthMonitorResource.class);
    private static final PoolHealthMonitorEvent POOL_HEALTH_MONITOR_EVENT
        = new PoolHealthMonitorEvent();

    private final LoadBalancerApi api;

    @Inject
    public PoolHealthMonitorResource(RestApiConfig config, UriInfo uriInfo,
                                     SecurityContext context,
                                     LoadBalancerApi api) {
        super(config, uriInfo, context, null);
        this.api = api;
    }
}
