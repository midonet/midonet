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
package org.midonet.brain.services.rest_api;

import java.net.URI;

import javax.xml.bind.annotation.XmlRootElement;

import org.midonet.brain.services.rest_api.neutron.NeutronUriBuilder;
import org.midonet.util.version.Since;

@XmlRootElement
public class Application extends UriResource {

    private String version = null;

    public Application() {
    }

    public Application(URI baseUri) {
        super(baseUri);
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public URI getHosts() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getHosts(getBaseUri());
        } else {
            return null;
        }
    }

    public URI getTenants() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getTenants(getBaseUri());
        } else {
            return null;
        }
    }

    public URI getTunnelZones() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getTunnelZones(getBaseUri());
        } else {
            return null;
        }
    }

    public URI getBridges() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getBridges(getBaseUri());
        } else {
            return null;
        }
    }

    public URI getLoadBalancers() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getLoadBalancers(getBaseUri());
        } else {
            return null;
        }
    }

    public URI getHealthMonitors() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getHealthMonitors(getBaseUri());
        } else {
            return null;
        }
    }

    public URI getPoolMembers() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getPoolMembers(getBaseUri());
        } else {
            return null;
        }
    }

    public URI getPools() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getPools(getBaseUri());
        } else {
            return null;
        }
    }

    @Since("5")
    public URI getPorts() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getPorts(getBaseUri());
        } else {
            return null;
        }
    }

    public URI getVips() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getVips(getBaseUri());
        } else {
            return null;
        }
    }

    public URI getChains() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getChains(getBaseUri());
        } else {
            return null;
        }
    }

    public URI getPortGroups() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getPortGroups(getBaseUri());
        } else {
            return null;
        }
    }

    public URI getIpAddrGroups() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getIpAddrGroups(getBaseUri());
        } else {
            return null;
        }
    }

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

    public String getAdRouteTemplate() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getAdRouteTemplate(getBaseUri());
        } else {
            return null;
        }
    }

    public String getBgpTemplate() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getBgpTemplate(getBaseUri());
        } else {
            return null;
        }
    }

    public String getBridgeTemplate() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getBridgeTemplate(getBaseUri());
        } else {
            return null;
        }
    }

    public String getHealthMonitorTemplate() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getHealthMonitorTemplate(getBaseUri());
        } else {
            return null;
        }
    }

    public String getLoadBalancerTemplate() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getLoadBalancerTemplate(getBaseUri());
        } else {
            return null;
        }
    }

    public String getPoolMemberTemplate() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getPoolMemberTemplate(getBaseUri());
        } else {
            return null;
        }
    }

    public String getPoolTemplate() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getPoolTemplate(getBaseUri());
        } else {
            return null;
        }
    }

    public String getVipTemplate() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getVipTemplate(getBaseUri());
        } else {
            return null;
        }
    }

    public String getChainTemplate() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getChainTemplate(getBaseUri());
        } else {
            return null;
        }
    }

    public String getHostTemplate() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getHostTemplate(getBaseUri());
        } else {
            return null;
        }
    }

    public String getPortTemplate() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getPortTemplate(getBaseUri());
        } else {
            return null;
        }
    }

    public String getPortGroupTemplate() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getPortGroupTemplate(getBaseUri());
        } else {
            return null;
        }
    }

    public String getIpAddrGroupTemplate() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getIpAddrGroupTemplate(getBaseUri());
        } else {
            return null;
        }
    }

    public String getRouteTemplate() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getRouteTemplate(getBaseUri());
        } else {
            return null;
        }
    }

    public String getRouterTemplate() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getRouterTemplate(getBaseUri());
        } else {
            return null;
        }
    }

    public String getRuleTemplate() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getRuleTemplate(getBaseUri());
        } else {
            return null;
        }
    }
    public String getTenantTemplate() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getTenantTemplate(getBaseUri());
        } else {
            return null;
        }
    }

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

    @Override
    public URI getUri() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getRoot(getBaseUri());
        } else {
            return null;
        }
    }

    public URI getWriteVersion() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getWriteVersion(getBaseUri());
        } else {
            return null;
        }
    }

    public URI getSystemState() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getSystemState(getBaseUri());
        } else {
            return null;
        }
    }

    public URI getHostVersions() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getHostVersions(getBaseUri());
        } else {
            return null;
        }
    }

    public URI getTraceRequests() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getTraceRequests(getBaseUri());
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

    @Since("5")
    public URI getLicenses() {
        // TODO (ernest): by now, returning null instead of removing
        // (to be removed in next release)
        return null;
    }

    @Since("5")
    public String getLicenseTemplate() {
        // TODO (ernest): by now, returning null instead of removing
        // (to be removed in next release)
        return null;
    }

    @Since("5")
    public URI getLicenseStatus() {
        // TODO (ernest): by now, returning null instead of removing
        // (to be removed in next release)
        return null;
    }
}
