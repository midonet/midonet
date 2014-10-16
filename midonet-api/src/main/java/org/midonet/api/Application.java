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
package org.midonet.api;

import org.midonet.api.neutron.NeutronUriBuilder;
import org.midonet.util.version.Since;

import javax.xml.bind.annotation.XmlRootElement;
import java.net.URI;

/**
 * Application DTO.
 */
@XmlRootElement
public class Application extends UriResource {

    private String version = null;

    public Application() {
    }

    /**
     * Constructor
     *
     * @param baseUri
     *            The base URI to construct all the URIs from.
     */
    public Application(URI baseUri) {
        super(baseUri);
    }

    /**
     * @return the version
     */
    public String getVersion() {
        return version;
    }

    /**
     * @param version
     *            the version to set
     */
    public void setVersion(String version) {
        this.version = version;
    }

    /**
     * @return the hosts URI
     */
    public URI getHosts() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getHosts(getBaseUri());
        } else {
            return null;
        }
    }

    /**
     * @return the tenants URI
     */
    public URI getTenants() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getTenants(getBaseUri());
        } else {
            return null;
        }
    }

    /**
     * @return the tunnel zones URI
     */
    public URI getTunnelZones() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getTunnelZones(getBaseUri());
        } else {
            return null;
        }
    }

    /**
     * @return the bridges URI
     */
    public URI getBridges() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getBridges(getBaseUri());
        } else {
            return null;
        }
    }

    /**
     * @return the loadbalancers URI
     */
    public URI getLoadBalancers() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getLoadBalancers(getBaseUri());
        } else {
            return null;
        }
    }

    /**
     * @return the health monitors URI
     */
    public URI getHealthMonitors() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getHealthMonitors(getBaseUri());
        } else {
            return null;
        }
    }

    /**
     * @return the pool members URI
     */
    public URI getPoolMembers() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getPoolMembers(getBaseUri());
        } else {
            return null;
        }
    }

    /**
     * @return the pools URI
     */
    public URI getPools() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getPools(getBaseUri());
        } else {
            return null;
        }
    }

    /**
     * @return the ports URI
     */
    @Since("5")
    public URI getPorts() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getPorts(getBaseUri());
        } else {
            return null;
        }
    }

    /**
     * @return the VIP URI
     */
    public URI getVips() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getVips(getBaseUri());
        } else {
            return null;
        }
    }

    /**
     * @return the chains URI
     */
    public URI getChains() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getChains(getBaseUri());
        } else {
            return null;
        }
    }

    /**
     * @return the port groups URI
     */
    public URI getPortGroups() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getPortGroups(getBaseUri());
        } else {
            return null;
        }
    }

    /**
     * @return the IP addr groups URI
     */
    public URI getIpAddrGroups() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getIpAddrGroups(getBaseUri());
        } else {
            return null;
        }
    }

    /**
     * @return the routers URI
     */
    public URI getRouters() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getRouters(getBaseUri());
        } else {
            return null;
        }
    }

    public URI getVteps() {
        return getBaseUri() == null ? null :
                ResourceUriBuilder.getVteps(getBaseUri());
    }

    /**
     * @return the Ad route URI template
     */
    public String getAdRouteTemplate() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getAdRouteTemplate(getBaseUri());
        } else {
            return null;
        }
    }

    /**
     * @return the BGP URI template
     */
    public String getBgpTemplate() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getBgpTemplate(getBaseUri());
        } else {
            return null;
        }
    }

    /**
     * @return the bridge URI template
     */
    public String getBridgeTemplate() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getBridgeTemplate(getBaseUri());
        } else {
            return null;
        }
    }

    /**
     * @return the health monitor URI template
     */
    public String getHealthMonitorTemplate() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getHealthMonitorTemplate(getBaseUri());
        } else {
            return null;
        }
    }

    /**
     * @return the loadbalancer URI template
     */
    public String getLoadBalancerTemplate() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getLoadBalancerTemplate(getBaseUri());
        } else {
            return null;
        }
    }

    /**
     * @return the pool member URI template
     */
    public String getPoolMemberTemplate() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getPoolMemberTemplate(getBaseUri());
        } else {
            return null;
        }
    }

    /**
     * @return the pool URI template
     */
    public String getPoolTemplate() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getPoolTemplate(getBaseUri());
        } else {
            return null;
        }
    }

    /**
     * @return the VIP URI template
     */
    public String getVipTemplate() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getVipTemplate(getBaseUri());
        } else {
            return null;
        }
    }

    /**
     * @return the chain URI template
     */
    public String getChainTemplate() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getChainTemplate(getBaseUri());
        } else {
            return null;
        }
    }

    /**
     * @return the host URI template
     */
    public String getHostTemplate() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getHostTemplate(getBaseUri());
        } else {
            return null;
        }
    }

    /**
     * @return the port URI template
     */
    public String getPortTemplate() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getPortTemplate(getBaseUri());
        } else {
            return null;
        }
    }

    /**
     * @return the port group URI template
     */
    public String getPortGroupTemplate() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getPortGroupTemplate(getBaseUri());
        } else {
            return null;
        }
    }

    /**
     * @return the ip addr group URI template
     */
    public String getIpAddrGroupTemplate() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getIpAddrGroupTemplate(getBaseUri());
        } else {
            return null;
        }
    }

    /**
     * @return the route URI template
     */
    public String getRouteTemplate() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getRouteTemplate(getBaseUri());
        } else {
            return null;
        }
    }

    /**
     * @return the router URI template
     */
    public String getRouterTemplate() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getRouterTemplate(getBaseUri());
        } else {
            return null;
        }
    }

    /**
     * @return the rule URI template
     */
    public String getRuleTemplate() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getRuleTemplate(getBaseUri());
        } else {
            return null;
        }
    }
    /**
     * @return the tenant URI template
     */
    public String getTenantTemplate() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getTenantTemplate(getBaseUri());
        } else {
            return null;
        }
    }

    /**
     * @return the tunnel zone URI template
     */
    public String getTunnelZoneTemplate() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getTunnelZoneTemplate(getBaseUri());
        } else {
            return null;
        }
    }

    public String getVtepTemplate() {
        return getBaseUri() == null ? null :
                ResourceUriBuilder.getVtepTemplate(getBaseUri());
    }

    /**
     * @return the self URI
     */
    @Override
    public URI getUri() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getRoot(getBaseUri());
        } else {
            return null;
        }
    }

    /**
     * @return Write Version URI
     */
    public URI getWriteVersion() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getWriteVersion(getBaseUri());
        } else {
            return null;
        }
    }

    /**
     * @return System State URI
     */
    public URI getSystemState() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getSystemState(getBaseUri());
        } else {
            return null;
        }
    }

    /**
     * @return URI host versions URI
     */
    public URI getHostVersions() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getHostVersions(getBaseUri());
        } else {
            return null;
        }
    }

    @Since("5")
    public URI getNeutron() {
        if (getBaseUri() != null) {
            return NeutronUriBuilder.getNeutron(getBaseUri());
        } else {
            return null;
        }
    }

    /**
     * @return Licenses URI
     * @deprecated
     */
    @Since("5")
    public URI getLicenses() {
        // TODO (ernest): by now, returning null instead of removing
        // (to be removed in next release)
        return null;
    }

    /**
     * @return License URI template
     * @deprecated
     */
    @Since("5")
    public String getLicenseTemplate() {
        // TODO (ernest): by now, returning null instead of removing
        // (to be removed in next release)
        return null;
    }

    /**
     * @return License status URI
     * @deprecated
     */
    @Since("5")
    public URI getLicenseStatus() {
        // TODO (ernest): by now, returning null instead of removing
        // (to be removed in next release)
        return null;
    }
}
