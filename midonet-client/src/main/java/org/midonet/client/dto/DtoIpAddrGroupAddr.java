/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.client.dto;

import org.codehaus.jackson.annotate.JsonSubTypes;
import org.codehaus.jackson.annotate.JsonTypeInfo;

import javax.xml.bind.annotation.XmlRootElement;
import java.net.URI;
import java.util.UUID;

/**
 * Class representing port.
 */
@XmlRootElement
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY,
        property = "version")
@JsonSubTypes({
        @JsonSubTypes.Type(value = DtoIpv4AddrGroupAddr.class,
                name = "4"),
        @JsonSubTypes.Type(value = DtoIpv6AddrGroupAddr.class,
                name = "6") })
public abstract class DtoIpAddrGroupAddr {

    protected String addr;
    protected UUID ipAddrGroupId;
    private URI uri;
    private URI ipAddrGroup;

    /**
     * Default constructor
     */
    public DtoIpAddrGroupAddr() {
    }

    public DtoIpAddrGroupAddr(UUID ipAddrGroupId, String addr) {
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

    /**
     * @return　The version
     */
    public abstract int getVersion();

    public URI getUri() {
        return this.uri;
    }

    public void setUri(URI uri) {
        this.uri = uri;
    }

    public URI getIpAddrGroup() {
        return this.ipAddrGroup;
    }

    public void setIpAddressGroup(URI ipAddressGroup) {
        this.ipAddrGroup = ipAddressGroup;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        DtoIpAddrGroupAddr that = (DtoIpAddrGroupAddr) o;

        if (getVersion() != that.getVersion()) {
            return false;
        }

        if (addr != null ? !addr.equals(that.addr) : that.addr != null)
            return false;
        if (ipAddrGroupId != null ? !ipAddrGroupId.equals(
                that.ipAddrGroupId) : that.ipAddrGroupId != null)
            return false;
        if (ipAddrGroup != null ? !ipAddrGroup.equals(
                that.ipAddrGroup) : that.ipAddrGroup != null)
            return false;
        if (uri != null ? !uri.equals(that.uri) : that.uri != null)
            return false;

        return true;
    }
}
