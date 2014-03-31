/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.api.l4lb;

import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.UriResource;
import org.midonet.api.validation.MessageProperty;
import org.midonet.midolman.state.LBStatus;
import org.midonet.util.StringUtil;

import java.net.URI;
import java.util.UUID;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.xml.bind.annotation.XmlRootElement;

/* Class representing pool member info */
@XmlRootElement
public class PoolMember extends UriResource {

    private UUID id;
    private boolean adminStateUp = true;
    private LBStatus status = LBStatus.ACTIVE;

    @NotNull
    private UUID poolId;

    @NotNull
    @Pattern(regexp = StringUtil.IP_ADDRESS_REGEX_PATTERN,
             message = MessageProperty.IP_ADDR_INVALID)
    private String address;

    @Min(0) @Max(65535)
    private int protocolPort;

    @Min(0)
    private int weight;

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

    public LBStatus getStatus() {
        return status;
    }

    public void setStatus(LBStatus status) {
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
