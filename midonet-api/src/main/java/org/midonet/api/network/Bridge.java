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
package org.midonet.api.network;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.validation.GroupSequence;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Null;
import javax.validation.groups.Default;
import javax.xml.bind.annotation.XmlRootElement;

import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.UriResource;
import org.midonet.api.network.validation.IsVxlanPortIdIntact;
import org.midonet.api.validation.MessageProperty;
import org.midonet.cluster.data.Bridge.Property;
import org.midonet.util.version.Since;
import org.midonet.util.version.Until;

/**
 * Class representing Virtual Bridge.
 */
@XmlRootElement
@IsVxlanPortIdIntact
public class Bridge extends UriResource {

    @NotNull(groups = BridgeUpdateGroup.class)
    private UUID id;

    @NotNull
    private String tenantId;

    @NotNull
    private String name;

    protected boolean adminStateUp;

    private UUID inboundFilterId;
    private UUID outboundFilterId;

    @Null(groups = BridgeCreateGroup.class,
          message = MessageProperty.VXLAN_PORT_ID_NOT_SETTABLE)
    @Since("2")
    private UUID vxLanPortId;

    @Null(groups = BridgeCreateGroup.class,
          message = MessageProperty.VXLAN_PORT_ID_NOT_SETTABLE)
    @Since("3") // after adding support to multiple vtep bindings
    private List<UUID> vxLanPortIds = null;

    @Since("4")
    private boolean disableAntiSpoof;

    public Bridge() {
        adminStateUp = true;
        disableAntiSpoof = false;
    }

    public Bridge(UUID id, String name, String tenantId) {
        this();
        this.id = id;
        this.name = name;
        this.tenantId = tenantId;
    }

    public Bridge(org.midonet.cluster.data.Bridge bridgeData) {
        this(bridgeData.getId(), bridgeData.getName(),
             bridgeData.getProperty(Property.tenant_id));
        this.adminStateUp = bridgeData.isAdminStateUp();
        this.inboundFilterId = bridgeData.getInboundFilter();
        this.outboundFilterId = bridgeData.getOutboundFilter();
        this.vxLanPortId = bridgeData.getVxLanPortId();
        this.vxLanPortIds = bridgeData.getVxLanPortIds();
        this.disableAntiSpoof = bridgeData.getDisableAntiSpoof();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public boolean isAdminStateUp() {
        return adminStateUp;
    }

    public void setAdminStateUp(boolean adminStateUp) {
        this.adminStateUp = adminStateUp;
    }

    public void setDisableAntiSpoof(boolean disableAntiSpoof) {
        this.disableAntiSpoof = disableAntiSpoof;
    }

    public boolean getDisableAntiSpoof() {
        return disableAntiSpoof;
    }

    public void setName(String name) {
        this.name = name;
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

    public URI getInboundFilter() {
        if (getBaseUri() != null && inboundFilterId != null) {
            return ResourceUriBuilder.getChain(getBaseUri(), inboundFilterId);
        } else {
            return null;
        }
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

    public URI getOutboundFilter() {
        if (getBaseUri() != null && outboundFilterId != null) {
            return ResourceUriBuilder.getChain(getBaseUri(), outboundFilterId);
        } else {
            return null;
        }
    }

    public UUID getVxLanPortId() {
        return vxLanPortId;
    }

    public void setVxLanPortId(UUID vxLanPortId) {
        this.vxLanPortId = vxLanPortId;
    }

    public List<UUID> getVxLanPortIds() {
        return vxLanPortIds;
    }

    public void setVxLanPortIds(List<UUID> vxLanPortIds) {
        this.vxLanPortIds = vxLanPortIds;
    }

    @Since("2")
    public URI getVxLanPort() {
        if (getBaseUri() == null || vxLanPortId == null)
            return null;
        return ResourceUriBuilder.getPort(getBaseUri(), vxLanPortId);
    }

    @Since("3")
    public List<URI> getVxLanPorts() {
        if (getBaseUri() == null || vxLanPortIds == null)
            return new ArrayList<>();
        List<URI> uris = new ArrayList<>(vxLanPortIds.size());
        for (UUID id : vxLanPortIds) {
            uris.add(ResourceUriBuilder.getPort(getBaseUri(), id));
        }
        return uris;
    }

    /**
     * @return the ports URI
     */
    public URI getPorts() {
        if (getBaseUri() != null && id != null) {
            return ResourceUriBuilder.getBridgePorts(getBaseUri(), id);
        } else {
            return null;
        }
    }

    /**
     * @return the peer ports URI
     */
    public URI getPeerPorts() {
        if (getBaseUri() != null && id != null) {
            return ResourceUriBuilder.getBridgePeerPorts(getBaseUri(), id);
        } else {
            return null;
        }
    }

    /**
     * @return the URI for the Bridge's MAC table.
     */
    public URI getMacTable() {
        return getMacTable(null);
    }

    public URI getMacTable(Short vlanId) {
        return ResourceUriBuilder.getMacTable(getUri(), vlanId);
    }

    /**
     * @return the URI for the Bridge's ARP table.
     */
    public URI getArpTable() {
        return ResourceUriBuilder.getArpTable(getUri());
    }

    /**
     * @return the DHCP server configuration URI
     */
    public URI getDhcpSubnets() {
        if (getBaseUri() != null && id != null) {
            return ResourceUriBuilder.getBridgeDhcps(getBaseUri(), id);
        } else {
            return null;
        }
    }

    /**
     * @return the DHCPV6 server configuration URI
     */
    public URI getDhcpSubnet6s() {
        if (getBaseUri() != null && id != null) {
            return ResourceUriBuilder.getBridgeDhcpV6s(getBaseUri(), id);
        } else {
            return null;
        }
    }

    @Override
    public URI getUri() {
        if (getBaseUri() != null && id != null) {
            return ResourceUriBuilder.getBridge(getBaseUri(), id);
        } else {
            return null;
        }
    }

    public String getVlanMacTableTemplate() {
        return ResourceUriBuilder.getVlanMacTableTemplate(getUri());
    }

    public String getMacPortTemplate() {
        return ResourceUriBuilder.getMacPortTemplate(getUri());
    }

    public String getVlanMacPortTemplate() {
        return ResourceUriBuilder.getVlanMacPortTemplate(getUri());
    }

    public org.midonet.cluster.data.Bridge toData() {
        return new org.midonet.cluster.data.Bridge()
                .setId(this.id)
                .setName(this.name)
                .setAdminStateUp(this.adminStateUp)
                .setInboundFilter(this.inboundFilterId)
                .setOutboundFilter(this.outboundFilterId)
                .setVxLanPortId(this.vxLanPortId)
                .setVxLanPortIds(this.vxLanPortIds)
                .setDisableAntiSpoof(this.disableAntiSpoof)
                .setProperty(Property.tenant_id, this.tenantId);
    }

    @Override
    public String toString() {
        return "id=" + id + ", name=" + name +
               ", adminStateUp=" + adminStateUp + ", tenantId=" + tenantId;
    }

    /**
     * Interface used for validating a bridge on updates.
     */
    public interface BridgeUpdateGroup {
    }

    /**
     * Interface used for validating a bridge on creates.
     */
    public interface BridgeCreateGroup {
    }

    /**
     * Interface that defines the ordering of validation groups for bridge
     * create.
     */
    @GroupSequence({ Default.class, BridgeCreateGroup.class })
    public interface BridgeCreateGroupSequence {
    }

    /**
     * Interface that defines the ordering of validation groups for bridge
     * update.
     */
    @GroupSequence({ Default.class, BridgeUpdateGroup.class })
    public interface BridgeUpdateGroupSequence {
    }
}
