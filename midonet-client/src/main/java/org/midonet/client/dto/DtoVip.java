/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.client.dto;

import com.google.common.base.Objects;

import java.net.URI;
import java.util.UUID;
import javax.xml.bind.annotation.XmlRootElement;

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

        return Objects.equal(id, that.getId()) &&
                Objects.equal(loadBalancerId,
                        that.getLoadBalancerId()) &&
                Objects.equal(poolId, that.getPoolId()) &&
                Objects.equal(address, that.getAddress()) &&
                protocolPort == that.getProtocolPort() &&
                Objects.equal(sessionPersistence,
                        that.getSessionPersistence()) &&
                adminStateUp == that.isAdminStateUp();
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id, loadBalancerId, poolId, address,
                protocolPort, sessionPersistence, adminStateUp);
    }
}
