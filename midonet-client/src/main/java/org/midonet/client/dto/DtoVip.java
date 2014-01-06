/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.client.dto;

import com.google.common.base.Objects;

import javax.xml.bind.annotation.XmlRootElement;
import java.net.URI;
import java.util.UUID;

@XmlRootElement
public class DtoVip {
    private UUID id;
    private URI uri;
    private UUID loadBalancerId;
    private URI loadBalancer;
    private UUID poolId;
    private URI pool;
    private String address;
    private int protocolPort;
    private String sessionPersistence;
    private boolean adminStateUp = true;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public URI getUri() {
        return uri;
    }

    public void setUri(URI uri) {
        this.uri = uri;
    }

    public UUID getLoadBalancerId() {
        return loadBalancerId;
    }

    public void setLoadBalancerId(UUID loadBalancerId) {
        this.loadBalancerId = loadBalancerId;
    }

    public URI getLoadBalancer() {
        return loadBalancer;
    }

    public void setLoadBalancer(URI loadBalancer) {
        this.loadBalancer = loadBalancer;
    }

    public UUID getPoolId() {
        return poolId;
    }

    public void setPoolId(UUID poolId) {
        this.poolId = poolId;
    }

    public URI getPool() {
        return pool;
    }

    public void setPool(URI pool) {
        this.pool = pool;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public int getProtocolPort() {
        return protocolPort;
    }

    public void setProtocolPort(int protocolPort) {
        this.protocolPort = protocolPort;
    }

    public String getSessionPersistence() {
        return sessionPersistence;
    }

    public void setSessionPersistence(String sessionPersistence) {
        this.sessionPersistence = sessionPersistence;
    }

    public boolean isAdminStateUp() {
        return adminStateUp;
    }

    public void setAdminStateUp(boolean adminStateUp) {
        this.adminStateUp = adminStateUp;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        DtoVip that = (DtoVip) o;

        if (!Objects.equal(this.id, that.getId()))
            return false;
        if (!Objects.equal(this.loadBalancerId, that.getLoadBalancerId()))
            return false;
        if (!Objects.equal(this.poolId, that.getPoolId()))
            return false;
        if (!Objects.equal(this.address, that.getAddress()))
            return false;
        if (this.protocolPort != that.getProtocolPort())
            return false;
        if (!Objects.equal(this.sessionPersistence,
                that.getSessionPersistence()))
            return false;
        if (this.adminStateUp != that.isAdminStateUp())
            return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = id != null ? id.hashCode() : 0;
        result = 31 * result
                + (loadBalancerId != null ? loadBalancerId.hashCode() : 0);
        result = 31 * result
                + (poolId != null ? poolId.hashCode() : 0);
        result = 31 * result
                + (address != null ? address.hashCode() : 0);
        result = 31 * result
                + protocolPort;
        result = 31 * result + (sessionPersistence != null ?
                sessionPersistence.hashCode() : 0);
        result = 31 * result
                + (adminStateUp ? 1 : 0);

        return result;
    }
}
