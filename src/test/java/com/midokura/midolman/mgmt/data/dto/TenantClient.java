package com.midokura.midolman.mgmt.data.dto;

import java.net.URI;
import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement
public class TenantClient extends Tenant {

    // for some reason JAXB want these to be public
    public URI bridges = null;
    public URI routers = null;
    public URI uri;
    /**
     * @return the bridges URI
     */
    @Override
    public URI getBridges() {
        return bridges;
    }

    /**
     * @return the routers URI
     */
    @Override
    public URI getRouters() {
        return routers;
    }

    @Override
    public URI getUri() {
        return uri;
    }

}
