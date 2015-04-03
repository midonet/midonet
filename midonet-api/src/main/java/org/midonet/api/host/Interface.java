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
package org.midonet.api.host;

import java.net.InetAddress;
import java.net.URI;
import java.util.UUID;

import javax.xml.bind.annotation.XmlRootElement;

import org.midonet.brain.services.rest_api.ResourceUriBuilder;
import org.midonet.brain.services.rest_api.UriResource;
import org.midonet.midolman.host.state.HostDirectory;
import org.midonet.packets.MAC;

@XmlRootElement
public class Interface extends UriResource {

    UUID hostId;
    String name;
    String mac;
    int mtu;
    int status;
    Type type;
    String endpoint;
    String portType;
    InetAddress[] addresses;

    public enum Type {
        Physical, Virtual, Tunnel, Unknown
    }

    public Interface() {
    }

    public Interface(UUID hostId,
                     org.midonet.cluster.data.host.Interface
                             interfaceData) {
        this.setName(interfaceData.getName());
        if (interfaceData.getMac() != null) {
            this.setMac(new MAC(interfaceData.getMac()).toString());
        }
        this.setStatus(interfaceData.getStatus());
        this.setMtu(interfaceData.getMtu());
        this.setHostId(hostId);
        if (interfaceData.getType() != null) {
            this.setType(Interface.Type.valueOf(interfaceData
                    .getType().name()));
        }
        this.setAddresses(interfaceData.getAddresses());
        this.setEndpoint(interfaceData.getEndpoint());
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getMac() {
        return mac;
    }

    public void setMac(String mac) {
        this.mac = mac;
    }

    public int getMtu() {
        return mtu;
    }

    public void setMtu(int mtu) {
        this.mtu = mtu;
    }

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
    }

    public UUID getHostId() {
        return hostId;
    }

    public void setHostId(UUID hostId) {
        this.hostId = hostId;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public InetAddress[] getAddresses() {
        return addresses;
    }

    public void setAddresses(InetAddress[] addresses) {
        this.addresses = addresses;
    }

    public String getPortType() {
        return portType;
    }

    public void setPortType(String portType) {
        this.portType = portType;
    }

    public org.midonet.cluster.data.host.Interface toData() {

        byte[] mac = null;
        if (this.getMac() != null) {
            mac = MAC.fromString(this.getMac()).getAddress();
        }

        HostDirectory.Interface.Type type = null;
        if (this.getType() != null) {
            type = HostDirectory.Interface.Type.valueOf(this
                    .getType().name());
        }

        return new org.midonet.cluster.data.host.Interface()
                .setName(this.name)
                .setMac(mac)
                .setStatus(this.getStatus())
                .setMtu(this.getMtu())
                .setType(type)
                .setAddresses(this.getAddresses())
                .setEndpoint(this.getEndpoint());
    }

    @Override
    public URI getUri() {
        if (super.getBaseUri() != null && hostId != null && name != null) {
            return ResourceUriBuilder.getHostInterface(super.getBaseUri(),
                    hostId, name);
        } else {
            return null;
        }
    }

}
