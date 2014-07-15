/*
 * Copyright 2012 Midokura Europe SARL
 */
package org.midonet.api.network;

import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.UriResource;
import org.midonet.api.network.validation.IsUniquePortGroupName;
import org.midonet.cluster.data.PortGroup.Property;

import javax.validation.GroupSequence;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import javax.validation.groups.Default;
import javax.xml.bind.annotation.XmlRootElement;
import java.net.URI;
import java.util.UUID;


/**
 * Class representing a port group.
 */
@IsUniquePortGroupName(groups = PortGroup.PortGroupExtended.class)
@XmlRootElement
public class PortGroup extends UriResource {

    public static final int MIN_PORT_GROUP_NAME_LEN = 1;
    public static final int MAX_PORT_GROUP_NAME_LEN = 255;

    private UUID id;

    @NotNull
    private String tenantId;

    @NotNull
    @Size(min = MIN_PORT_GROUP_NAME_LEN, max = MAX_PORT_GROUP_NAME_LEN)
    private String name;

    private boolean stateful;

    /**
     * Default constructor
     */
    public PortGroup() {
        super();
    }

    /**
     * Constructor
     *
     * @param data
     *            PortGroup data object
     */
    public PortGroup(org.midonet.cluster.data.PortGroup data) {
        this(data.getId(), data.getName(),
                data.getProperty(Property.tenant_id), data.isStateful());
    }

    /**
     * Constructor
     *
     * @param id
     *            ID of the PortGroup
     * @param name
     *            PortGroup name
     * @param tenantId
     *            Tenant ID
     * @param stateful
     *            Is this a stateful port group
     */
    public PortGroup(UUID id, String name, String tenantId) {
        this(id, name, tenantId, false);
    }

    public PortGroup(UUID id, String name, String tenantId, boolean stateful) {
        this.id = id;
        this.name = name;
        this.tenantId = tenantId;
        this.stateful = stateful;
    }

    /**
     * @return the id
     */
    public UUID getId() {
        return id;
    }

    /**
     * @param id
     *            the id to set
     */
    public void setId(UUID id) {
        this.id = id;
    }

    /**
     * @return the tenantId
     */
    public String getTenantId() {
        return tenantId;
    }

    /**
     * @param tenantId
     *            the tenantId to set
     */
    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    /**
     * @return the name
     */
    public String getName() {
        return name;
    }

    /**
     * @param name
     *            the name to set
     */
    public void setName(String name) {
        this.name = name;
    }

    public boolean isStateful() {
        return stateful;
    }

    public void setStateful(boolean stateful) {
        this.stateful = stateful;
    }

    /**
     * @return the self URI
     */
    @Override
    public URI getUri() {
        if (getBaseUri() != null && id != null) {
            return ResourceUriBuilder.getPortGroup(getBaseUri(), id);
        } else {
            return null;
        }
    }

    public URI getPorts() {
        if (getBaseUri() != null && id != null) {
            return ResourceUriBuilder.getPortGroupPorts(getBaseUri(), id);
        } else {
            return null;
        }
    }

    public org.midonet.cluster.data.PortGroup toData() {

        return new org.midonet.cluster.data.PortGroup()
                .setId(this.id)
                .setName(this.name)
                .setProperty(Property.tenant_id, this.tenantId)
                .setStateful(this.stateful);
    }

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return "id=" + id + " tenantId=" + tenantId + ", name=" + name +
                ", stateful=" + stateful;
    }

    /**
     * Interface used for a Validation group. This group gets triggered after
     * the default validations.
     */
    public interface PortGroupExtended {
    }

    /**
     * Interface used for validating a port group on creates.
     */
    public interface PortGroupCreateGroup {
    }

    /**
     * Interface that defines the ordering of validation groups for port group
     * create.
     */
    @GroupSequence({ Default.class, PortGroupCreateGroup.class,
            PortGroupExtended.class })
    public interface PortGroupCreateGroupSequence {
    }

}

