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

package org.midonet.api.l4lb;

import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.UriResource;

import javax.xml.bind.annotation.XmlRootElement;
import java.net.URI;
import java.util.UUID;

@XmlRootElement
public class LoadBalancer extends UriResource {
    private UUID id;
    private UUID routerId;
    private boolean adminStateUp = true;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getRouterId() {
        return routerId;
    }

    public void setRouterId(UUID routerId) {
        this.routerId = routerId;
    }

    public boolean isAdminStateUp() {
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
        this.adminStateUp = loadBalancer.isAdminStateUp();
        this.routerId = loadBalancer.getRouterId();
        this.id = loadBalancer.getId();
    }

    public org.midonet.cluster.data.l4lb.LoadBalancer toData() {
        return new org.midonet.cluster.data.l4lb.LoadBalancer()
                .setId(this.id)
                .setRouterId(this.routerId)
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

    public URI getRouter() {
        if (getBaseUri() != null && routerId != null) {
            return ResourceUriBuilder.getRouter(getBaseUri(), routerId);
        } else {
            return null;
        }
    }

    public URI getPools() {
        return (getBaseUri() == null || id == null) ? null :
                ResourceUriBuilder.getLoadBalancerPools(getBaseUri(), id);
    }

    public URI getVips() {
        return (getBaseUri() == null || id == null) ? null :
                ResourceUriBuilder.getLoadBalancerVips(getBaseUri(), id);
    }
}
