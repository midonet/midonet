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

import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;
import java.net.URI;
import java.util.UUID;

/**
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 1/31/12
 */
@XmlRootElement
public class DtoHost {

    private UUID id;
    private String name;
    private String[] addresses;
    private DtoInterface[] hostInterfaces;
    private URI interfaces;
    private URI ports;
    private boolean alive;
    private Integer floodingProxyWeight;
    private Integer containerWeight;
    private Integer containerLimit;
    private Boolean enforceContainerLimit;

    @XmlTransient
    private URI uri;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public URI getInterfaces() {
        return interfaces;
    }

    public void setInterfaces(URI interfaces) {
        this.interfaces = interfaces;
    }

    public DtoInterface[] getHostInterfaces() {
        return hostInterfaces;
    }

    public void setHostInterfaces(DtoInterface[] hostInterfaces) {
        this.hostInterfaces = hostInterfaces;
    }

    public URI getUri() {
        return uri;
    }

    public void setUri(URI uri) {
        this.uri = uri;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String[] getAddresses() {
        return addresses;
    }

    public void setAddresses(String[] addresses) {
        this.addresses = addresses;
    }

    public boolean isAlive() {
        return alive;
    }

    public void setAlive(boolean alive) {
        this.alive = alive;
    }

    public Integer getFloodingProxyWeight() {
        return floodingProxyWeight;
    }

    public void setFloodingProxyWeight(Integer floodingProxyWeight) {
        this.floodingProxyWeight = floodingProxyWeight;
    }

    public Integer getContainerWeight() {
        return containerWeight;
    }

    public void setContainerWeight(Integer containerWeight) {
        this.containerWeight = containerWeight;
    }

    public Integer getContainerLimit() {
        return containerLimit;
    }

    public void setContainerLimit(Integer containerLimit) {
        this.containerLimit = containerLimit;
    }

    public Boolean getEnforceContainerLimit() {
        return enforceContainerLimit;
    }

    public void setEnforceContainerLimit(Boolean enforceContainerLimit) {
        this.enforceContainerLimit = enforceContainerLimit;
    }

    public URI getPorts() {
        return ports;
    }

    public void setPorts(URI ports) {
        this.ports = ports;
    }
}
