/*
 * Copyright 2015 Midokura SARL
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

package org.midonet.cluster.rest_api.neutron;

import java.net.URI;
import java.util.UUID;

import javax.ws.rs.core.UriBuilder;

public class NeutronResourceUris {

    // TODO: move the templating to the DTOs, not done yet to keep the
    // changes made to models limited for now.
    public static final String ID_TOKEN = "/{id}";

    public final static String NEUTRON = "/neutron";
    public final static String NETWORKS = "/networks";
    public final static String SUBNETS = "/subnets";
    public final static String PORTS = "/ports";
    public final static String ROUTERS = "/routers";
    public final static String ADD_ROUTER_INTF = "/add_router_interface";
    public final static String REMOVE_ROUTER_INTF = "/remove_router_interface";
    public final static String FLOATING_IPS = "/floating_ips";
    public final static String SECURITY_GROUPS = "/security_groups";
    public final static String SECURITY_GROUP_RULES = "/security_group_rules";

    // LBaaS resources
    public static final String LB = "/lb";
    public static final String VIPS = "/vips";
    public static final String POOLS = "/pools";
    public static final String MEMBERS = "/members";
    public static final String HEALTH_MONITORS = "/health_monitors";
    public static final String POOL_HEALTH_MONITOR = "/pool_health_monitor";

    public static URI getUri(URI baseUri, String what) {
        return UriBuilder.fromUri(baseUri).segment(what).build();
    }

    public static URI getUri(URI baseUri, String what, UUID id) {
        return UriBuilder.fromUri(baseUri)
                         .segment(what)
                         .segment(id.toString()).build();
    }

    public static URI getUri(URI baseUri, String what, String id) {
        return UriBuilder.fromUri(baseUri)
            .segment(what)
            .segment(id).build();
    }

}
