/*
 * Copyright 2015 Midokura SARL
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
package org.midonet.cluster.rest_api.models;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import javax.validation.constraints.NotNull;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;

import com.fasterxml.jackson.annotation.JsonIgnore;

import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.data.ZoomObject;
import org.midonet.cluster.rest_api.annotation.Resource;
import org.midonet.cluster.rest_api.annotation.ResourceId;
import org.midonet.cluster.rest_api.annotation.Subresource;
import org.midonet.cluster.rest_api.validation.VerifyEnumValue;
import org.midonet.cluster.util.UUIDUtil;
import org.midonet.midolman.state.l4lb.LBStatus;
import org.midonet.midolman.state.l4lb.PoolLBMethod;
import org.midonet.midolman.state.l4lb.PoolProtocol;

@XmlRootElement
@Resource(name = ResourceUris.POOLS)
public class Pool extends UriResource {

    @ZoomField(name = "id", converter = UUIDUtil.Converter.class)
    @ResourceId
    public UUID id;

    @ZoomField(name = "healthMonitorId", converter = UUIDUtil.Converter.class)
    public UUID healthMonitorId;

    @NotNull
    @ZoomField(name = "loadBalancerId", converter = UUIDUtil.Converter.class)
    public UUID loadBalancerId;

    @VerifyEnumValue(PoolProtocol.class)
    @ZoomField(name = "protocol", converter = UUIDUtil.Converter.class)
    public String protocol = PoolProtocol.TCP.toString();

    @NotNull
    @VerifyEnumValue(PoolLBMethod.class)
    public String lbMethod;

    @ZoomField(name = "adminStateUp", converter = UUIDUtil.Converter.class)
    public boolean adminStateUp = true;

    @VerifyEnumValue(LBStatus.class)
    @ZoomField(name = "status", converter = UUIDUtil.Converter.class)
    public String status = LBStatus.ACTIVE.toString();

    @XmlTransient
    @Subresource(name = ResourceUris.POOL_MEMBERS)
    @ZoomField(name = "pool_member_ids", converter = UUIDUtil.Converter.class)
    public List<UUID> poolMemberIds;

    public URI getHealthMonitor() {
        return absoluteUri(ResourceUris.HEALTH_MONITORS, healthMonitorId);
    }

    public URI getLoadBalancer() {
        return absoluteUri(ResourceUris.LOAD_BALANCERS, loadBalancerId);
    }

    public URI getVips() {
        return relativeUri(ResourceUris.VIPS);
    }

    public URI getPoolMembers() {
        return relativeUri(ResourceUris.POOL_MEMBERS);
    }

}
