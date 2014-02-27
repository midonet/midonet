/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.api.l4lb;

import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.UriResource;
import org.midonet.midolman.state.PoolMemberStatus;

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
    private PoolMemberStatus status;

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

    public PoolMemberStatus getStatus() {
        return status;
    }

    public void setStatus(PoolMemberStatus status) {
        this.status = status;
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

    public URI getPool() {
        return (getBaseUri() == null || poolId == null) ? null :
                ResourceUriBuilder.getPool(getBaseUri(), poolId);
    }
}
