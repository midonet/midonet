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

package org.midonet.client.dto;

import java.net.URI;
import java.util.UUID;
import java.util.List;

import javax.xml.bind.annotation.XmlRootElement;

import com.google.common.base.Objects;

@XmlRootElement
public class DtoRouter {
    private UUID id;
    private String name;
    private boolean adminStateUp = true;
    private String tenantId;
    private List<UUID> inboundMirrorIds;
    private List<UUID> outboundMirrorIds;
    private List<UUID> serviceContainerIds;
    private UUID inboundFilterId;
    private UUID outboundFilterId;
    private URI inboundFilter;
    private URI outboundFilter;
    private URI uri;
    private URI peerPorts;
    private URI ports;
    private URI routes;
    private UUID loadBalancerId;
    private URI loadBalancer;
    private URI bgpNetworks;
    private URI bgpPeers;
    private Integer asNumber;
    private UUID localRedirectChainId;
    private URI localRedirectChain;
    private URI serviceContainers;

    public List<UUID> getServiceContainerIds() {
        return serviceContainerIds;
    }

    public void setServiceContainerIds(List<UUID> ids) {
        this.serviceContainerIds = ids;
    }

    public URI getServiceContainers() {
        return serviceContainers;
    }

    public void setServiceContainers(URI uri) {
        this.serviceContainers = uri;
    }

    public URI getBgpPeers() {
        return bgpPeers;
    }

    public void setBgpPeers(URI bgpNetworks) {
        this.bgpPeers = bgpNetworks;
    }

    public URI getBgpNetworks() {
        return bgpNetworks;
    }

    public void setBgpNetworks(URI bgpNetworks) {
        this.bgpNetworks = bgpNetworks;
    }

    public Integer getAsNumber() {
        return asNumber;
    }

    public void setAsNumber(Integer asNumber) {
        this.asNumber = asNumber;
    }

    public UUID getId() {
        return id;
    }

    public List<UUID> getInboundMirrorIds() {
        return this.inboundMirrorIds;
    }

    public List<UUID> getOutboundMirrorIds() {
        return this.outboundMirrorIds;
    }

    public void setInboundMirrorIds(List<UUID> mirrors) {
        this.inboundMirrorIds = mirrors;
    }

    public void setOutboundMirrorIds(List<UUID> mirrors) {
        this.outboundMirrorIds = mirrors;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isAdminStateUp() {
        return adminStateUp;
    }

    public void setAdminStateUp(boolean adminStateUp) {
        this.adminStateUp = adminStateUp;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public UUID getInboundFilterId() {
        return inboundFilterId;
    }

    public void setInboundFilterId(UUID inboundFilterId) {
        this.inboundFilterId = inboundFilterId;
    }

    public UUID getOutboundFilterId() {
        return outboundFilterId;
    }

    public void setOutboundFilterId(UUID outboundFilterId) {
        this.outboundFilterId = outboundFilterId;
    }

    public URI getInboundFilter() {
        return inboundFilter;
    }

    public void setInboundFilter(URI inboundFilter) {
        this.inboundFilter = inboundFilter;
    }

    public URI getOutboundFilter() {
        return outboundFilter;
    }

    public void setOutboundFilter(URI outboundFilter) {
        this.outboundFilter = outboundFilter;
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

    public URI getLocalRedirectChain() {
        return localRedirectChain;
    }

    public void setLocalRedirectChain() {
        this.localRedirectChain = localRedirectChain;
    }

    public URI getUri() {
        return uri;
    }

    public void setUri(URI uri) {
        this.uri = uri;
    }

    public URI getPorts() {
        return ports;
    }

    public void setPorts(URI ports) {
        this.ports = ports;
    }

    public URI getRoutes() {
        return routes;
    }

    public void setRoutes(URI routes) {
        this.routes = routes;
    }

    public URI getPeerPorts() {
        return peerPorts;
    }

    public void setPeerPorts(URI peerPorts) {
        this.peerPorts = peerPorts;
    }

    public UUID getLocalRedirectChainId() {
        return localRedirectChainId;
    }

    public void setLocalRedirectChainId(UUID localRedirectChainId) {
        this.localRedirectChainId = localRedirectChainId;
    }

    @Override
    public boolean equals(Object other) {

        if (other == this) {
            return true;
        }

        if (other == null || getClass() != other.getClass()) {
            return false;
        }

        DtoRouter otherRouter = (DtoRouter) other;
        if (!Objects.equal(this.id, otherRouter.getId())) {
            return false;
        }

        if (!Objects.equal(this.name, otherRouter.getName())) {
            return false;
        }

        if (!Objects.equal(this.tenantId, otherRouter.getTenantId())) {
            return false;
        }

        if (!Objects.equal(
                this.inboundFilterId, otherRouter.getInboundFilterId())) {
            return false;
        }

        if (!Objects.equal(
                this.inboundFilter, otherRouter.getInboundFilter())) {
            return false;
        }

        if (!Objects.equal(
                this.outboundFilterId, otherRouter.getOutboundFilterId())) {
            return false;
        }

        if (!Objects.equal(
                this.outboundFilter, otherRouter.getInboundFilter())) {
            return false;
        }

        if (!Objects.equal(this.uri, otherRouter.getUri())) {
            return false;
        }

        if (!Objects.equal(this.ports, otherRouter.getPorts())) {
            return false;
        }

        if (!Objects.equal(this.peerPorts, otherRouter.getPeerPorts())) {
            return false;
        }

        if (!Objects.equal(this.routes, otherRouter.getRoutes())) {
            return false;
        }

        if (!Objects.equal(this.inboundMirrorIds, otherRouter.getInboundMirrorIds()))
            return false;
        if (!Objects.equal(this.outboundMirrorIds, otherRouter.getOutboundMirrorIds()))
            return false;

        if (!Objects.equal(localRedirectChainId, otherRouter.getLocalRedirectChainId()))
            return false;

        if (adminStateUp != otherRouter.adminStateUp) {
            return false;
        }

        return true;
    }
}
