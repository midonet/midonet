/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.api.l4lb;

import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.UriResource;
import org.midonet.util.StringUtil;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.xml.bind.annotation.XmlRootElement;
import java.net.URI;
import java.util.UUID;

@XmlRootElement
public class VIP extends UriResource {

    public final static String VIP_SOURCE_IP = "SOURCE_IP";

    private UUID id;
    @NotNull
    private UUID loadBalancerId;
    @NotNull
    private UUID poolId;
    @Pattern(regexp = StringUtil.IP_ADDRESS_REGEX_PATTERN,
             message = "is an invalid IP format")
    private String address;
    @Min(0)
    @Max(65536)
    private int protocolPort;
    @NotNull
    @Pattern.List({
        @Pattern(regexp = VIP_SOURCE_IP,
                 message = "is not SOURCE_IP")
    })
    private String sessionPersistence;
    private boolean adminStateUp = true;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getLoadBlancerId() {
        return loadBalancerId;
    }

    public void setLoadBlancerId(UUID loadBlancerId) {
        this.loadBalancerId = loadBlancerId;
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

    public VIP() {
        super();
    }

    public VIP(org.midonet.cluster.data.l4lb.VIP vip) {
        super();
        this.id = vip.getId();
        this.loadBalancerId = vip.getLoadBalancerId();
        this.poolId = vip.getPoolId();
        this.address = vip.getAddress();
        this.protocolPort = vip.getProtocolPort();
        this.sessionPersistence = vip.getSessionPersistence();
        this.adminStateUp = vip.getAdminStateUp();
    }

    public org.midonet.cluster.data.l4lb.VIP toData() {
        return new org.midonet.cluster.data.l4lb.VIP()
                .setId(this.id)
                .setLoadBalancerId(this.loadBalancerId)
                .setPoolId(this.poolId)
                .setAddress(this.address)
                .setProtocolPort(this.protocolPort)
                .setSessionPersistence(this.sessionPersistence)
                .setAdminStateUp(this.adminStateUp);
    }

    public URI getUri() {
        if (getBaseUri() != null && id != null) {
            return ResourceUriBuilder.getVip(getBaseUri(), id);
        } else {
            return null;
        }
    }
}
