/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt;

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
    public String getAdRoute() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getAdRouteTemplate(getBaseUri());
        } else {
            return null;
        }
    }

    /**
     * @return the BGP URI template
     */
    public String getBgp() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getBgpTemplate(getBaseUri());
        } else {
            return null;
        }
    }

    /**
     * @return the bridge URI template
     */
    public String getBridge() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getBridgeTemplate(getBaseUri());
        } else {
            return null;
        }
    }

    /**
     * @return the chain URI template
     */
    public String getChain() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getChainTemplate(getBaseUri());
        } else {
            return null;
        }
    }

    /**
     * @return the host URI template
     */
    public String getHost() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getHostTemplate(getBaseUri());
        } else {
            return null;
        }
    }

    /**
     * @return the port URI template
     */
    public String getPort() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getPortTemplate(getBaseUri());
        } else {
            return null;
        }
    }

    /**
     * @return the port group URI template
     */
    public String getPortGroup() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getPortGroupTemplate(getBaseUri());
        } else {
            return null;
        }
    }

    /**
     * @return the route URI template
     */
    public String getRoute() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getRouteTemplate(getBaseUri());
        } else {
            return null;
        }
    }

    /**
     * @return the router URI template
     */
    public String getRouter() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getRouterTemplate(getBaseUri());
        } else {
            return null;
        }
    }

    /**
     * @return the rule URI template
     */
    public String getRule() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getRuleTemplate(getBaseUri());
        } else {
            return null;
        }
    }

    /**
     * @return the tunnel zone URI template
     */
    public String getTunnelZone() {
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
}
