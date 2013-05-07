/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.network;

import java.net.URI;
import java.util.UUID;
import javax.validation.GroupSequence;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import javax.validation.groups.Default;
import javax.xml.bind.annotation.XmlRootElement;

import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.UriResource;
import org.midonet.api.network.validation.IsUniqueVlanBridgeName;
import org.midonet.cluster.data.VlanAwareBridge.Property;

/**
 * Class representing Virtual Vlan aware Bridge.
 */
@IsUniqueVlanBridgeName(groups = VlanBridge.VlanBridgeExtended.class)
@XmlRootElement
public class VlanBridge extends UriResource {

    public static final int MIN_VLAN_BRIDGE_NAME_LEN = 1;
    public static final int MAX_VLAN_BRIDGE_NAME_LEN = 255;

    @NotNull(groups = VlanBridgeUpdateGroup.class)
    private UUID id;

    @NotNull
    private String tenantId;

    @NotNull
    @Size(min = MIN_VLAN_BRIDGE_NAME_LEN, max = MAX_VLAN_BRIDGE_NAME_LEN)
    private String name;

    /**
     * Constructor.
     */
    public VlanBridge() {
    }

    /**
     * Constructor
     *
     * @param id ID of the bridge.
     * @param name Name of the bridge.
     * @param tenantId ID of the tenant that owns the bridge.
     */
    public VlanBridge(UUID id, String name, String tenantId) {
        super();
        this.id = id;
        this.name = name;
        this.tenantId = tenantId;
    }

    public VlanBridge(org.midonet.cluster.data.VlanAwareBridge bridgeData) {
        this(bridgeData.getId(), bridgeData.getName(),
             bridgeData.getProperty(Property.tenant_id));
    }

    /**
     * Get the vlan-aware bridge ID.
     *
     * @return vlan-aware Bridge ID.
     */
    public UUID getId() {
        return id;
    }

    /**
     * Set vlan-aware bridge ID.
     *
     * @param id ID of the vlan-aware bridge.
     */
    public void setId(UUID id) {
        this.id = id;
    }

    /**
     * Get vlan-aware bridge name.
     *
     * @return vlan-aware bridge name.
     */
    public String getName() {
        return name;
    }

    /**
     * Set vlan-aware bridge name.
     *
     * @param name Name of the vlan-aware bridge.
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * Get tenant ID.
     *
     * @return Tenant ID.
     */
    public String getTenantId() {
        return tenantId;
    }

    /**
     * Set tenant ID.
     *
     * @param tenantId Tenant ID.
     */
    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    /**
     * @return the trunk ports URI
     */
    public URI getTrunkPorts() {
        if (getBaseUri() != null && id != null) {
            return ResourceUriBuilder.getVlanBridgeTrunkPorts(getBaseUri(), id);
        } else {
            return null;
        }
    }

    /**
     * Get the interior ports URI
     *
     * @return
     */
    public URI getInteriorPorts() {
        if (getBaseUri() != null && id != null) {
            return ResourceUriBuilder.getVlanBridgeInteriorPorts(getBaseUri(),
                                                                 id);
        } else {
            return null;
        }
    }

    /**
     * @return the self URI
     */
    @Override
    public URI getUri() {
        if (getBaseUri() != null && id != null) {
            return ResourceUriBuilder.getVlanBridge(getBaseUri(), id);
        } else {
            return null;
        }
    }

    /**
     * Convert this object to bridge data object
     *
     * @return Bridge data object
     */
    public org.midonet.cluster.data.VlanAwareBridge toData() {

        return new org.midonet.cluster.data.VlanAwareBridge()
                .setId(this.id)
                .setName(this.name)
                .setProperty(Property.tenant_id, this.tenantId);
    }

    @Override
    public String toString() {
        return "id=" + id + ", name=" + name + ", tenantId=" + tenantId;
    }

    /**
     * Interface used for a Validation group. This group gets triggered after
     * the default validations.
     */
    public interface VlanBridgeExtended {
    }

    /**
     * Interface used for validating a vlan-bridge on updates.
     */
    public interface VlanBridgeUpdateGroup {
    }

    /**
     * Interface used for validating a vlan-bridge on creates.
     */
    public interface VlanBridgeCreateGroup {
    }

    /**
     * Interface that defines the ordering of validation groups for vlan bridge
     * create.
     */
    @GroupSequence({ Default.class, VlanBridgeCreateGroup.class,
            VlanBridgeExtended.class })
    public interface VlanBridgeCreateGroupSequence {
    }

    /**
     * Interface that defines the ordering of validation groups for bridge
     * update.
     */
    @GroupSequence({ Default.class, VlanBridgeUpdateGroup.class,
            VlanBridgeExtended.class })
    public interface VlanBridgeUpdateGroupSequence {
    }
}
