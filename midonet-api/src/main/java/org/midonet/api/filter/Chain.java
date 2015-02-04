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
package org.midonet.api.filter;

import java.net.URI;
import java.util.UUID;

import javax.validation.GroupSequence;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import javax.validation.groups.Default;
import javax.xml.bind.annotation.XmlRootElement;

import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.UriResource;
import org.midonet.cluster.data.boilerplate.Chain.Property;

/**
 * Class representing chain.
 */
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
    public Chain(org.midonet.cluster.data.boilerplate.Chain data) {
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
    public org.midonet.cluster.data.boilerplate.Chain toData() {

        return new org.midonet.cluster.data.boilerplate.Chain()
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
