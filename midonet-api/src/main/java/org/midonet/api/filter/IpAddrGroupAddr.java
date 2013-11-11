/*
 * Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.api.filter;

import org.codehaus.jackson.annotate.JsonSubTypes;
import org.codehaus.jackson.annotate.JsonTypeInfo;
import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.UriResource;
import org.midonet.util.StringUtil;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.xml.bind.annotation.XmlRootElement;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.util.UUID;

/**
 * Class representing port.
 */
@XmlRootElement
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY,
        property = "version")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Ipv4AddrGroupAddr.class,
                name = "4"),
        @JsonSubTypes.Type(value = Ipv6AddrGroupAddr.class,
                name = "6") })
public abstract class IpAddrGroupAddr extends UriResource {

    // IP address in canonical form. Can be either IPv4 or IPv6.
    @NotNull
    @Pattern(regexp = StringUtil.IP_ADDRESS_REGEX_PATTERN,
            message = "is not a valid IP address")
    protected String addr;

    @NotNull
    protected UUID ipAddrGroupId;

    public IpAddrGroupAddr() {
    }

    public IpAddrGroupAddr(UUID ipAddrGroupId, String addr) {
        this.addr = addr;
        this.ipAddrGroupId = ipAddrGroupId;
    }

    public String getAddr() {
        return addr;
    }

    public UUID getIpAddrGroupId() {
        return ipAddrGroupId;
    }

    public void setIpAddrGroupId(UUID ipAddrGroupId) {
        this.ipAddrGroupId = ipAddrGroupId;
    }

    public abstract int getVersion();

    @Override
    public URI getUri() {
        if (getBaseUri() != null && addr != null) {
            try {
                return ResourceUriBuilder.getIpAddrGroupVersionAddr(getBaseUri(),
                        ipAddrGroupId, getVersion(), getAddr());
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException("Invalid IP address format");
            }
        } else {
            return null;
        }
    }

    public URI getIpAddrGroup() {
        if (getBaseUri() != null && ipAddrGroupId != null) {
            return ResourceUriBuilder.getIpAddrGroup(getBaseUri(),
                    ipAddrGroupId);
        } else {
            return null;
        }
    }
}
