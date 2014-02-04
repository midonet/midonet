/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.api.l4lb;

import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.UriResource;

import javax.xml.bind.annotation.XmlRootElement;
import java.net.URI;
import java.util.UUID;

@XmlRootElement
public class LoadBalancer extends UriResource {
    private UUID id;
    private boolean adminStateUp = true;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public boolean getAdminStateUp() {
        return adminStateUp;
    }

    public void setAdminStateUp(boolean adminStateUp) {
        this.adminStateUp = adminStateUp;
    }

    public LoadBalancer() {
        super();
    }

    public LoadBalancer(
            org.midonet.cluster.data.l4lb.LoadBalancer loadBalancer) {
        super();
        this.adminStateUp = loadBalancer.getAdminStateUp();
        this.id = loadBalancer.getId();
    }

    public org.midonet.cluster.data.l4lb.LoadBalancer toData() {
        return new org.midonet.cluster.data.l4lb.LoadBalancer()
                .setId(this.id)
                .setAdminStateUp(this.adminStateUp);
    }

    /**
     * @return the self URI
     */
    @Override
    public URI getUri() {
        if (getBaseUri() != null && id != null) {
            return ResourceUriBuilder.getLoadBalancer(getBaseUri(), id);
        } else {
            return null;
        }
    }
}
