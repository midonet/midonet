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

import java.net.URI;

import javax.ws.rs.core.UriBuilder;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;

import com.fasterxml.jackson.annotation.JsonIgnore;

import org.midonet.cluster.rest_api.ResourceUris;
import org.midonet.cluster.rest_api.neutron.NeutronUriBuilder;
import org.midonet.cluster.rest_api.version.Version;

@XmlRootElement
public class Application {

    @XmlTransient
    private final URI root;

    public Application(URI root)  {
        this.root = root;
    }

    private URI uriFor(String s) {
        return UriBuilder.fromUri(root).segment(s).build();
    }

    private String templateFor(String s) {
        return uriFor(s).toString() + "/{id}";
    }

    public URI getUri() {
        return root;
    }

    @XmlElement(name = "version")
    public String getVersion() {
        return Version.CURRENT;
    }

    @XmlElement(name = "bridges")
    public URI getBridges() {
        return uriFor(ResourceUris.BRIDGES);
    }

    @XmlElement(name = "l2insertions")
    public URI getL2Insertions() {
        return uriFor(ResourceUris.L2INSERTIONS);
    }

    @XmlElement(name = "l2services")
    public URI getL2Services() {
        return uriFor(ResourceUris.L2SERVICES);
    }

    @XmlElement(name = "routers")
    public URI getRouters() {
        return uriFor(ResourceUris.ROUTERS);
    }

    @XmlElement(name = "systemState")
    public URI getSystemState() {
        return uriFor(ResourceUris.SYSTEM_STATE);
    }

    @XmlElement(name = "hosts")
    public URI getHosts() {
        return uriFor(ResourceUris.HOSTS);
    }

    @XmlElement(name = "tenants")
    public URI getTenants() {
        return uriFor(ResourceUris.TENANTS);
    }

    @XmlElement(name = "tunnelZones")
    public URI getTunnelZones() {
        return uriFor(ResourceUris.TUNNEL_ZONES);
    }

    @XmlElement(name = "loadBalancers")
    public URI getLoadBalancers() {
        return uriFor(ResourceUris.LOAD_BALANCERS);
    }

    @XmlElement(name = "healthMonitors")
    public URI getHealthMonitors() {
        return uriFor(ResourceUris.HEALTH_MONITORS);
    }

    @XmlElement(name = "neutron")
    public URI getNeutron() {
        return uriFor(NeutronUriBuilder.NEUTRON);
    }

    @XmlElement(name = "poolMembers")
    public URI getPoolMembers() {
        return uriFor(ResourceUris.POOL_MEMBERS);
    }

    @XmlElement(name = "pools")
    public URI getPools() {
        return uriFor(ResourceUris.POOLS);
    }

    @XmlElement(name = "ports")
    public URI getPorts() {
        return uriFor(ResourceUris.PORTS);
    }

    @XmlElement(name = "vips")
    public URI getVips() {
        return uriFor(ResourceUris.VIPS);
    }

    @XmlElement(name = "chains")
    public URI getChains() {
        return uriFor(ResourceUris.CHAINS);
    }

    @XmlElement(name = "portGroups")
    public URI getPortGroups() {
        return uriFor(ResourceUris.PORT_GROUPS);
    }

    @XmlElement(name = "ipAddrGroups")
    public URI getIpAddrGroups() {
        return uriFor(ResourceUris.IP_ADDR_GROUPS);
    }

    @XmlElement(name = "vteps")
    public URI getVteps() {
        return uriFor(ResourceUris.VTEPS);
    }

    @XmlElement(name = "bridgeTemplate")
    public String getBridgeTemplate() {
        return templateFor(ResourceUris.BRIDGES);
    }

    @XmlElement(name = "portTemplate")
    public String getPortTemplate() {
        return templateFor(ResourceUris.PORTS);
    }

    @XmlElement(name = "hostTemplate")
    public String getHostTemplate() {
        return templateFor(ResourceUris.HOSTS);
    }

    @XmlElement(name = "tunnelZoneTemplate")
    public String getTunnelZoneTemplate() {
        return templateFor(ResourceUris.TUNNEL_ZONES);
    }

    @XmlElement(name = "adRouteTemplate")
    public String getAdRouteTemplate() {
        return templateFor(ResourceUris.AD_ROUTES);
    }

    @XmlElement(name = "bgpTemplate")
    public String getBgpTemplate() {
        return templateFor(ResourceUris.BGP);
    }

    @XmlElement(name = "healthMonitorTemplate")
    public String getHealthMonitorTemplate() {
        return templateFor(ResourceUris.HEALTH_MONITORS);
    }

    @XmlElement(name = "loadBalancerTemplate")
    public String getLoadBalancerTemplate() {
        return templateFor(ResourceUris.LOAD_BALANCERS);
    }

    @XmlElement(name = "poolMemberTemplate")
    public String getPoolMemberTemplate() {
        return templateFor(ResourceUris.POOL_MEMBERS);
    }

    @XmlElement(name = "poolTemplate")
    public String getPoolTemplate() {
        return templateFor(ResourceUris.POOLS);
    }

    @XmlElement(name = "vipTemplate")
    public String getVipTemplate() {
        return templateFor(ResourceUris.VIPS);
    }

    @XmlElement(name = "chainTemplate")
    public String getChainTemplate() {
        return templateFor(ResourceUris.CHAINS);
    }

    @XmlElement(name = "portGroupTemplate")
    public String getPortGroupTemplate() {
        return templateFor(ResourceUris.PORT_GROUPS);
    }

    @XmlElement(name = "ipAddrGroupTemplate")
    public String getIpAddrGroupTemplate() {
        return templateFor(ResourceUris.IP_ADDR_GROUPS);
    }

    @XmlElement(name = "routeTemplate")
    public String getRouteTemplate() {
        return templateFor(ResourceUris.ROUTES);
    }

    @XmlElement(name = "routerTemplate")
    public String getRouterTemplate() {
        return templateFor(ResourceUris.ROUTERS);
    }

    @XmlElement(name = "l2InsertionTemplate")
    public String getL2InsertionTemplate() {
        return templateFor(ResourceUris.L2INSERTIONS);
    }

    @XmlElement(name = "l2ServiceTemplate")
    public String getL2ServiceTemplate() {
        return templateFor(ResourceUris.L2SERVICES);
    }

    @XmlElement(name = "ruleTemplate")
    public String getRuleTemplate() {
        return templateFor(ResourceUris.RULES);
    }

    @XmlElement(name = "tenantTemplate")
    public String getTenantTemplate() {
        return templateFor(ResourceUris.TENANTS);
    }

    @XmlElement(name = "vtepTemplate")
    public String getVtepTemplate() {
        return templateFor(ResourceUris.VTEPS);
    }

    @XmlElement(name = "writeVersion")
    public URI getWriteVersion() {
        return uriFor(ResourceUris.WRITE_VERSION);
    }

    @XmlElement(name = "hostVersions")
    public URI getHostVersions() {
        return uriFor(ResourceUris.HOSTS);
    }

    @XmlElement(name = "traceRequests")
    public URI getTraceRequests() {
        return uriFor(ResourceUris.TRACE_REQUESTS);
    }

}
