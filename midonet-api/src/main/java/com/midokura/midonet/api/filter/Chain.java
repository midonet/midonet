/*
 * Copyright 2012 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midonet.api.filter;

import com.midokura.midonet.api.UriResource;
import com.midokura.midonet.api.filter.Chain.ChainExtended;
import com.midokura.midonet.api.ResourceUriBuilder;
import com.midokura.midonet.api.filter.validation.IsUniqueChainName;
import com.midokura.midonet.cluster.data.Chain.Property;

import javax.validation.GroupSequence;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import javax.validation.groups.Default;
import javax.xml.bind.annotation.XmlRootElement;
import java.net.URI;
import java.util.UUID;

/**
 * Class representing chain.
 */
@IsUniqueChainName(groups = ChainExtended.class)
@XmlRootElement
public class Chain extends UriResource {

    public static final int MIN_CHAIN_NAME_LEN = 1;
    public static final int MAX_CHAIN_NAME_LEN = 255;

    private UUID id;

    @NotNull
    private String tenantId;

    @NotNull
    @Size(min = MIN_CHAIN_NAME_LEN, max = MAX_CHAIN_NAME_LEN)
    private String name;

    /**
     * Default constructor
     */
    public Chain() {
        super();
    }

    /**
     * Constructor
     *
     * @param data
     *            Chain data object
     */
    public Chain(com.midokura.midonet.cluster.data.Chain data) {
        this(data.getId(), data.getProperty(Property.tenant_id),
                data.getName());
    }

    /**
     * Constructor
     *
     * @param id
     *            ID of the chain
     * @param tenantId
     *            Tenant ID
     * @param name
     *            Chain name
     */
    public Chain(UUID id, String tenantId, String name) {
        this.id = id;
        this.tenantId = tenantId;
        this.name = name;
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

    /**
     * @return the self URI
     */
    @Override
    public URI getUri() {
        if (getBaseUri() != null && id != null) {
            return ResourceUriBuilder.getChain(getBaseUri(), id);
        } else {
            return null;
        }
    }

    /**
     * @return the rules URI
     */
    public URI getRules() {
        if (getBaseUri() != null && id != null) {
            return ResourceUriBuilder.getChainRules(getBaseUri(), id);
        } else {
            return null;
        }
    }

    /**
     * Convert this object to chain data object
     *
     * @return Chain data object
     */
    public com.midokura.midonet.cluster.data.Chain toData() {

        return new com.midokura.midonet.cluster.data.Chain()
                .setId(this.id)
                .setName(this.name)
                .setProperty(Property.tenant_id, this.tenantId);
    }

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return "id=" + id + " tenantId=" + tenantId + ", name=" + name;
    }

    /**
     * Interface used for a Validation group. This group gets triggered after
     * the default validations.
     */
    public interface ChainExtended {
    }

    /**
     * Interface that defines the ordering of validation groups.
     */
    @GroupSequence({ Default.class, ChainExtended.class })
    public interface ChainGroupSequence {
    }
}
