/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.client.dto;

import javax.xml.bind.annotation.XmlRootElement;
import java.net.URI;
import java.util.UUID;

@XmlRootElement
public class DtoPoolMember {
    private UUID id;
    private UUID poolId;
    private String address;
    private int protocolPort;
    private int weight;
    private boolean adminStateUp = true;
    private String status;
    private URI pool;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
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

        DtoPoolMember dtoPoolMember = (DtoPoolMember) o;

        if (adminStateUp != dtoPoolMember.adminStateUp) return false;
        if (protocolPort != dtoPoolMember.protocolPort) return false;
        if (weight != dtoPoolMember.weight) return false;
        if (address != null ?
                !address.equals(dtoPoolMember.address)
                : dtoPoolMember.address != null) return false;
        if (pool != null ? !pool.equals(dtoPoolMember.pool)
                : dtoPoolMember.pool != null) return false;
        if (poolId != null ? !poolId.equals(dtoPoolMember.poolId)
                : dtoPoolMember.poolId != null) return false;
        if (status != null ? !status.equals(dtoPoolMember.status)
                : dtoPoolMember.status != null) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = poolId != null ? poolId.hashCode() : 0;
        result = 31 * result + (address != null ? address.hashCode() : 0);
        result = 31 * result + protocolPort;
        result = 31 * result + weight;
        result = 31 * result + (adminStateUp ? 1 : 0);
        result = 31 * result + (status != null ? status.hashCode() : 0);
        result = 31 * result + (pool != null ? pool.hashCode() : 0);
        return result;
    }
}
