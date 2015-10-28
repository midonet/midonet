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

package org.midonet.cluster.rest_api.models;

import java.io.IOException;
import java.net.URI;

import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;

import scala.Tuple3;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import org.apache.commons.lang3.StringUtils;

import org.midonet.cluster.rest_api.ResourceUris;
import org.midonet.cluster.rest_api.annotation.ApiResource;
import org.midonet.cluster.rest_api.neutron.NeutronUriBuilder;
import org.midonet.cluster.rest_api.version.Version;
import org.midonet.cluster.services.rest_api.ResourceProvider;

import static org.midonet.cluster.services.rest_api.resources.MidonetResource.ResourceContext;

@JsonSerialize(using = Application.Serializer.class)
public class Application {

    public static class Serializer extends JsonSerializer<Application> {
        @Override
        public void serialize(Application app, JsonGenerator gen,
                              SerializerProvider serializers) throws IOException {
            UriInfo uriInfo = app.resContext.uriInfo();
            gen.writeStartObject();
            gen.writeStringField("uri", uriInfo.getBaseUri().toString());
            gen.writeStringField("version", Version.CURRENT);
            for (Tuple3<String, ApiResource, Class<Object>> entry : app.resProvider.all()) {
                if (StringUtils.isNotBlank(entry._2().name())) {
                    gen.writeStringField(entry._2().name(),
                                         uriFor(uriInfo, entry._1()));
                }
                if (StringUtils.isNotBlank(entry._2().template())) {
                    gen.writeStringField(entry._2().template(),
                                         templateFor(uriInfo, entry._1()));
                }
            }
            gen.writeEndObject();
        }

        private String uriFor(UriInfo uriInfo, String segment) {
            return uriInfo.getAbsolutePathBuilder().segment(segment)
                          .build().toString();
        }

        private String templateFor(UriInfo uriInfo, String segment) {
            return uriInfo.getAbsolutePathBuilder().segment(segment)
                          .build().toString() + "/{id}";
        }
    }

    @JsonIgnore
    private final ResourceProvider resProvider;

    @JsonIgnore
    private final ResourceContext resContext;

    public Application(ResourceProvider resProvider, ResourceContext resContext) {
        this.resProvider = resProvider;
        this.resContext = resContext;
    }

    /*


    @JsonProperty("l2insertions")
    public URI getL2Insertions() {
        return uriFor(ResourceUris.L2INSERTIONS);
    }

    @JsonProperty("l2services")
    public URI getL2Services() {
        return uriFor(ResourceUris.L2SERVICES);
    }

    @JsonProperty("routers")
    public URI getRouters() {
        return uriFor(ResourceUris.ROUTERS);
    }

    @JsonProperty("systemState")
    public URI getSystemState() {
        return uriFor(ResourceUris.SYSTEM_STATE);
    }

    @JsonProperty("hosts")
    public URI getHosts() {
        return uriFor(ResourceUris.HOSTS);
    }

    @JsonProperty("mirrors")
    public URI getMirrors() {
        return uriFor(ResourceUris.MIRRORS);
    }

    @JsonProperty("tenants")
    public URI getTenants() {
        return uriFor(ResourceUris.TENANTS);
    }

    @JsonProperty("tunnelZones")
    public URI getTunnelZones() {
        return uriFor(ResourceUris.TUNNEL_ZONES);
    }

    @JsonProperty("loadBalancers")
    public URI getLoadBalancers() {
        return uriFor(ResourceUris.LOAD_BALANCERS);
    }

    @JsonProperty("healthMonitors")
    public URI getHealthMonitors() {
        return uriFor(ResourceUris.HEALTH_MONITORS);
    }

    @JsonProperty("neutron")
    public URI getNeutron() {
        return uriFor(NeutronUriBuilder.NEUTRON);
    }

    @JsonProperty("poolMembers")
    public URI getPoolMembers() {
        return uriFor(ResourceUris.POOL_MEMBERS);
    }

    @JsonProperty("pools")
    public URI getPools() {
        return uriFor(ResourceUris.POOLS);
    }

    @JsonProperty("ports")
    public URI getPorts() {
        return uriFor(ResourceUris.PORTS);
    }

    @JsonProperty("vips")
    public URI getVips() {
        return uriFor(ResourceUris.VIPS);
    }

    @JsonProperty("chains")
    public URI getChains() {
        return uriFor(ResourceUris.CHAINS);
    }

    @JsonProperty("portGroups")
    public URI getPortGroups() {
        return uriFor(ResourceUris.PORT_GROUPS);
    }

    @JsonProperty("ipAddrGroups")
    public URI getIpAddrGroups() {
        return uriFor(ResourceUris.IP_ADDR_GROUPS);
    }

    @JsonProperty("vteps")
    public URI getVteps() {
        return uriFor(ResourceUris.VTEPS);
    }

    @JsonProperty("bridgeTemplate")
    public String getBridgeTemplate() {
        return templateFor(ResourceUris.BRIDGES);
    }

    @JsonProperty("mirrorTemplate")
    public String getMirrorTemplate() {
        return templateFor(ResourceUris.MIRRORS);
    }

    @JsonProperty("portTemplate")
    public String getPortTemplate() {
        return templateFor(ResourceUris.PORTS);
    }

    @JsonProperty("hostTemplate")
    public String getHostTemplate() {
        return templateFor(ResourceUris.HOSTS);
    }

    @JsonProperty("tunnelZoneTemplate")
    public String getTunnelZoneTemplate() {
        return templateFor(ResourceUris.TUNNEL_ZONES);
    }

    @JsonProperty("bgpNetworkTemplate")
    public String getBgpNetworkTemplate() {
        return templateFor(ResourceUris.BGP_NETWORKS);
    }

    @JsonProperty("bgpPeerTemplate")
    public String getBgpPeerTemplate() {
        return templateFor(ResourceUris.BGP_PEERS);
    }

    @JsonProperty("healthMonitorTemplate")
    public String getHealthMonitorTemplate() {
        return templateFor(ResourceUris.HEALTH_MONITORS);
    }

    @JsonProperty("loadBalancerTemplate")
    public String getLoadBalancerTemplate() {
        return templateFor(ResourceUris.LOAD_BALANCERS);
    }

    @JsonProperty("poolMemberTemplate")
    public String getPoolMemberTemplate() {
        return templateFor(ResourceUris.POOL_MEMBERS);
    }

    @JsonProperty("poolTemplate")
    public String getPoolTemplate() {
        return templateFor(ResourceUris.POOLS);
    }

    @JsonProperty("vipTemplate")
    public String getVipTemplate() {
        return templateFor(ResourceUris.VIPS);
    }

    @JsonProperty("chainTemplate")
    public String getChainTemplate() {
        return templateFor(ResourceUris.CHAINS);
    }

    @JsonProperty("portGroupTemplate")
    public String getPortGroupTemplate() {
        return templateFor(ResourceUris.PORT_GROUPS);
    }

    @JsonProperty("ipAddrGroupTemplate")
    public String getIpAddrGroupTemplate() {
        return templateFor(ResourceUris.IP_ADDR_GROUPS);
    }

    @JsonProperty("routeTemplate")
    public String getRouteTemplate() {
        return templateFor(ResourceUris.ROUTES);
    }

    @JsonProperty("routerTemplate")
    public String getRouterTemplate() {
        return templateFor(ResourceUris.ROUTERS);
    }

    @JsonProperty("l2InsertionTemplate")
    public String getL2InsertionTemplate() {
        return templateFor(ResourceUris.L2INSERTIONS);
    }

    @JsonProperty("l2ServiceTemplate")
    public String getL2ServiceTemplate() {
        return templateFor(ResourceUris.L2SERVICES);
    }

    @JsonProperty("ruleTemplate")
    public String getRuleTemplate() {
        return templateFor(ResourceUris.RULES);
    }

    @JsonProperty("tenantTemplate")
    public String getTenantTemplate() {
        return templateFor(ResourceUris.TENANTS);
    }

    @JsonProperty("vtepTemplate")
    public String getVtepTemplate() {
        return templateFor(ResourceUris.VTEPS);
    }

    @JsonProperty("writeVersion")
    public URI getWriteVersion() {
        return uriFor(ResourceUris.WRITE_VERSION);
    }

    @JsonProperty("hostVersions")
    public URI getHostVersions() {
        return uriFor(ResourceUris.HOSTS);
    }

    @JsonProperty("traceRequests")
    public URI getTraceRequests() {
        return uriFor(ResourceUris.TRACE_REQUESTS);
    }

    @JsonProperty("traceRequestTemplate")
    public String getTraceRequestTemplate() {
        return templateFor(ResourceUris.TRACE_REQUESTS);
    }*/
}
