/*
 * Copyright 2011 Midokura KK
 * Copyright 2012-2013 Midokura PTE LTD.
 */
package org.midonet.api;

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
    @Since("2")
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
     * @return the metrics filter URI
     */
    public URI getMetricsFilter() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getMetricsFilter(getBaseUri());
        } else {
            return null;
        }
    }

    /**
     * @return the metrics query URI
     */
    public URI getMetricsQuery() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getMetricsQuery(getBaseUri());
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
    @Since("3")
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
    @Since("3")
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
    @Since("2")
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
     * @return Trace conditions URI
     */
    public URI getTraceConditions() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getTraceConditions(getBaseUri());
        } else {
            return null;
        }
    }

    /**
     * @return Trace conditions template URI
     */
    public String getTraceConditionTemplate() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getTraceConditionTemplate(getBaseUri());
        } else {
            return null;
        }
    }

    /**
     * @return Trace URI
     */
    public URI getTraces() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getTraces(getBaseUri());
        } else {
            return null;
        }
    }

    /**
     * @return Trace template URI
     */
    public String getTraceTemplate() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getTraceTemplate(getBaseUri());
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
}
