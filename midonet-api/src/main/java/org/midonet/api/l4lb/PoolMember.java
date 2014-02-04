/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.api.l4lb;

import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.UriResource;

import javax.xml.bind.annotation.XmlRootElement;
import java.net.URI;
import java.util.UUID;

/* Class representing pool member info */
@XmlRootElement
public class PoolMember extends UriResource {

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

    public PoolMember() {
        super();
    }

    public PoolMember(org.midonet.cluster.data.l4lb.PoolMember poolMember) {
        super();
        this.poolId = poolMember.getPoolId();
        this.address = poolMember.getAddress();
        this.protocolPort = poolMember.getProtocolPort();
        this.weight = poolMember.getWeight();
        this.adminStateUp = poolMember.getAdminStateUp();
        this.status = poolMember.getStatus();
        this.pool = poolMember.getPool();
        this.id = poolMember.getId();
    }

    public org.midonet.cluster.data.l4lb.PoolMember toData() {
        return new org.midonet.cluster.data.l4lb.PoolMember()
                .setPoolId(this.poolId)
                .setAddress(this.address)
                .setProtocolPort(this.protocolPort)
                .setWeight(this.weight)
                .setAdminStateUp(this.adminStateUp)
                .setStatus(this.status)
                .setPool(this.pool)
                .setId(this.id);
    }

    /**
     * @return the self URI
     */
    @Override
    public URI getUri() {
        if (getBaseUri() != null && id != null) {
            return ResourceUriBuilder.getPoolMember(getBaseUri(), id);
        } else {
            return null;
        }
    }
}
