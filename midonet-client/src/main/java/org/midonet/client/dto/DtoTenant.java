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

import com.google.common.base.Objects;

import java.net.URI;

public class DtoTenant {

    private String id;
    private String name;
    private URI uri;
    private URI routers;
    private URI bridges;
    private URI chains;
    private URI portGroups;

    public DtoTenant(){
    }

    public DtoTenant(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public URI getUri() {
        return uri;
    }

    public void setUri(URI uri) {
        this.uri = uri;
    }

    public URI getRouters() {
        return routers;
    }

    public void setRouters(URI routers) {
        this.routers = routers;
    }

    public URI getBridges() {
        return bridges;
    }

    public void setBridges(URI bridges) {
        this.bridges = bridges;
    }

    public URI getChains() {
        return chains;
    }

    public void setChains(URI chains) {
        this.chains = chains;
    }

    public URI getPortGroups() {
        return portGroups;
    }

    public void setPortGroups(URI portGroups) {
        this.portGroups = portGroups;
    }

    @Override
    public boolean equals(Object other) {

        if (other == this) {
            return true;
        }

        if (other == null || getClass() != other.getClass()) {
            return false;
        }

        DtoTenant otherTenant = (DtoTenant) other;
        if (!Objects.equal(this.id, otherTenant.getId())) {
             return false;
        }

        if (!Objects.equal(this.name, otherTenant.getName())) {
            return false;
        }

        if (!Objects.equal(this.bridges, otherTenant.getBridges())) {
            return false;
        }

        if (!Objects.equal(this.routers, otherTenant.getRouters())) {
            return false;
        }

        if (!Objects.equal(this.chains, otherTenant.getChains())) {
            return false;
        }

        if (!Objects.equal(this.portGroups, otherTenant.getPortGroups())) {
            return false;
        }

        if (!Objects.equal(this.uri, otherTenant.getUri())) {
            return false;
        }

        return true;
    }
}
