/*
 * Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.client.dto;

import com.google.common.base.Objects;

import java.net.URI;
import java.util.UUID;
import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement
public class DtoLoadBalancer {
    private UUID id;
    private UUID routerId;
    private boolean adminStateUp = true;
    private URI uri;
    private URI router;
    private URI pools;
    private URI vips;

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

    public URI getUri() {
        return uri;
    }

    public void setUri(URI uri) {
        this.uri = uri;
    }

    public URI getRouter() {
        return router;
    }

    public void setRouter(URI router) {
        this.router = router;
    }

    public URI getPools() {
        return pools;
    }

    public void setPools(URI pools) {
        this.pools = pools;
    }

    public URI getVips() {
        return vips;
    }

    public void setVips(URI vips) {
        this.vips = vips;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        DtoLoadBalancer that = (DtoLoadBalancer) o;

        return Objects.equal(id, that.getId()) &&
                Objects.equal(routerId, that.routerId) &&
                adminStateUp == that.isAdminStateUp();
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id, routerId, adminStateUp);
    }
}
