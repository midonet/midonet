/*
 * Copyright (c) 2013 Midokura SARL, All Rights Reserved.
 */
package org.midonet.api.filter;

import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.UriResource;

import javax.validation.GroupSequence;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import javax.validation.groups.Default;
import javax.xml.bind.annotation.XmlRootElement;
import java.net.URI;
import java.util.UUID;


/**
 * Class representing a IP address group.
 */
@XmlRootElement
public class IpAddrGroup extends UriResource {

    public static final int MIN_IP_ADDR_GROUP_NAME_LEN = 1;
    public static final int MAX_IP_ADDR_GROUP_NAME_LEN = 255;

    private UUID id;

    @NotNull
    @Size(min = MIN_IP_ADDR_GROUP_NAME_LEN, max = MAX_IP_ADDR_GROUP_NAME_LEN)
    private String name;

    public IpAddrGroup() {
    }

    public IpAddrGroup(org.midonet.cluster.data.IpAddrGroup data) {
        this(data.getId(), data.getName());
    }

    public IpAddrGroup(UUID id, String name) {
        this.id = id;
        this.name = name;
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

    public void setName(String name) {
        this.name = name;
    }

    @Override
    public URI getUri() {
        if (getBaseUri() != null && id != null) {
            return ResourceUriBuilder.getIpAddrGroup(getBaseUri(), id);
        } else {
            return null;
        }
    }

    public URI getAddrs() {
        if (getBaseUri() != null && id != null) {
            return ResourceUriBuilder.getIpAddrGroupAddrs(getBaseUri(), id);
        } else {
            return null;
        }
    }

    public org.midonet.cluster.data.IpAddrGroup toData() {

        return new org.midonet.cluster.data.IpAddrGroup()
                .setId(this.id)
                .setName(this.name);
    }

    @Override
    public String toString() {
        return "id=" + id + ", name=" + name;
    }

    /**
     * Interface used for a Validation group. This group gets triggered after
     * the default validations.
     */
    public interface IpAddrGroupExtended {
    }

    /**
     * Interface used for validating a IP addr group on creates.
     */
    public interface IpAddrGroupCreateGroup {
    }

    /**
     * Interface that defines the ordering of validation groups for IP addr
     * group create.
     */
    @GroupSequence({ Default.class, IpAddrGroupCreateGroup.class,
            IpAddrGroupExtended.class })
    public interface IpAddrGroupCreateGroupSequence {
    }
}

