/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.client.dto;

import com.google.common.base.Objects;

import java.net.URI;
import java.util.UUID;
import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement
public class DtoPoolMember {
    private UUID id;
    private URI uri;
    private UUID poolId;
    private String address;
    private int protocolPort;
    private int weight;
    private boolean adminStateUp = true;
    private LBStatus status = LBStatus.ACTIVE;

    private URI pool;

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

    public UUID getPoolId() {
        return poolId;
    }

    public void setPoolId(UUID poolId) {
        this.poolId = poolId;
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

    public int getWeight() {
        return weight;
    }

    public void setWeight(int weight) {
        this.weight = weight;
    }

    public boolean isAdminStateUp() {
        return adminStateUp;
    }

    public void setAdminStateUp(boolean adminStateUp) {
        this.adminStateUp = adminStateUp;
    }

    public LBStatus getStatus() {
        return status;
    }

    public void setStatus(LBStatus status) {
        this.status = status;
    }

    public URI getPool() {
        return pool;
    }

    public void setPool(URI pool) {
        this.pool = pool;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        DtoPoolMember that = (DtoPoolMember) o;

        if (!Objects.equal(id, that.getId())) return false;
        if (!Objects.equal(poolId, that.getPoolId())) return false;
        if (!Objects.equal(address, that.getAddress())) return false;
        if (protocolPort != that.getProtocolPort()) return false;
        if (weight != that.getWeight()) return false;
        if (adminStateUp != that.isAdminStateUp()) return false;
        if (!Objects.equal(status, that.getStatus())) return false;
        if (!Objects.equal(pool, that.getPool())) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = id != null ? id.hashCode() : 0;
        result = 31 * result + (poolId != null ? poolId.hashCode() : 0);
        result = 31 * result + (address != null ? address.hashCode() : 0);
        result = 31 * result + protocolPort;
        result = 31 * result + weight;
        result = 31 * result + (adminStateUp ? 1 : 0);
        result = 31 * result + (status != null ? status.hashCode() : 0);
        result = 31 * result + (pool != null ? pool.hashCode() : 0);
        return result;
    }
}
