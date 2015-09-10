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

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Class representing port.
 */
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
