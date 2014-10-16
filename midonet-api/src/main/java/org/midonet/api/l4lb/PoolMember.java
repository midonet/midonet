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
import org.midonet.api.validation.MessageProperty;
import org.midonet.api.validation.VerifyEnumValue;
import org.midonet.midolman.state.l4lb.LBStatus;
import org.midonet.packets.IPv4;

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
    @VerifyEnumValue(LBStatus.class)
    private String status = LBStatus.ACTIVE.toString();

    @NotNull
    private UUID poolId;

    @NotNull
    @Pattern(regexp = IPv4.regex,
             message = MessageProperty.IP_ADDR_INVALID)
    private String address;

    @Min(0) @Max(65535)
    private int protocolPort;

    @Min(1)
    private int weight = 1;

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
        this.status = poolMember.getStatus().toString();
        this.id = poolMember.getId();
    }

    public org.midonet.cluster.data.l4lb.PoolMember toData() {
        return new org.midonet.cluster.data.l4lb.PoolMember()
                .setPoolId(this.poolId)
                .setAddress(this.address)
                .setProtocolPort(this.protocolPort)
                .setWeight(this.weight)
                .setAdminStateUp(this.adminStateUp)
                .setStatus(LBStatus.valueOf(this.status))
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
