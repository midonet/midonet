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
import org.midonet.api.validation.VerifyEnumValue;
import org.midonet.midolman.state.l4lb.VipSessionPersistence;
import org.midonet.packets.IPv4;

import java.net.URI;
import java.util.UUID;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement
public class VIP extends UriResource {

    private UUID id;
    private UUID loadBalancerId;
    @NotNull
    private UUID poolId;
    @Pattern(regexp = IPv4.regex,
             message = "is an invalid IP format")
    private String address;
    @Min(0)
    @Max(65536)
    private int protocolPort;
    @VerifyEnumValue(VipSessionPersistence.class)
    private String sessionPersistence;
    private boolean adminStateUp = true;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getLoadBalancerId() {
        return loadBalancerId;
    }

    public void setLoadBalancerId(UUID loadBlancerId) {
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
        this.sessionPersistence = vip.getSessionPersistence() != null ?
                vip.getSessionPersistence().toString() : null;
        this.adminStateUp = vip.getAdminStateUp();
    }

    public org.midonet.cluster.data.l4lb.VIP toData() {
        return new org.midonet.cluster.data.l4lb.VIP()
                .setId(this.id)
                .setLoadBalancerId(this.loadBalancerId)
                .setPoolId(this.poolId)
                .setAddress(this.address)
                .setProtocolPort(this.protocolPort)
                .setSessionPersistence(this.sessionPersistence != null ?
                        VipSessionPersistence.valueOf(
                                this.sessionPersistence) : null)
                .setAdminStateUp(this.adminStateUp);
    }

    public URI getUri() {
        if (getBaseUri() != null && id != null) {
            return ResourceUriBuilder.getVip(getBaseUri(), id);
        } else {
            return null;
        }
    }

    public URI getLoadBalancer() {
        return (getBaseUri() == null || loadBalancerId == null) ? null :
                ResourceUriBuilder.getLoadBalancer(getBaseUri(), loadBalancerId);
    }

    public URI getPool() {
        return (getBaseUri() == null || poolId == null) ? null :
                ResourceUriBuilder.getPool(getBaseUri(), poolId);
    }
}
