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
import java.net.InetAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 1/31/12
 */
@XmlRootElement
public class DtoInterface {

    public enum Type {
        Physical, Virtual, Tunnel, Unknown
    }

    public enum StatusType {
        Up(0x01), Carrier(0x02);

        private int mask;

        private StatusType(int mask) {
            this.mask = mask;
        }

        public int getMask() {
            return mask;
        }
    }

    private UUID id;
    private UUID hostId;
    private String name;
    private String mac;
    private int mtu;
    private int status;
    private Type type;
    private String endpoint;
    private String portType;
    private InetAddress[] addresses;

    @XmlTransient
    private URI uri;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public URI getUri() {
        return uri;
    }

    public void setUri(URI uri) {
        this.uri = uri;
    }

    public UUID getHostId() {
        return hostId;
    }

    public void setHostId(UUID hostId) {
        this.hostId = hostId;
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

    public int getStatus() {
        return status;
    }

    public boolean getStatusField(StatusType statusType) {
        return (status & statusType.getMask()) != 0;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public void setStatusField(StatusType statusType) {
        setStatus(getStatus() & statusType.getMask());
    }

    public void clearStatusField(StatusType statusType) {
        setStatus(getStatus() & ~statusType.getMask());
    }

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
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

    @Override
    public String toString() {
        return "DtoInterface{" +
                "name=" + name +
                ", hostId=" + hostId +
                ", mac='" + mac + '\'' +
                ", mtu=" + mtu +
                ", status=" + status +
                ", type=" + type +
                ", endpoint='" + endpoint + '\'' +
                ", porttype='" + portType + '\'' +
                ", addresses=" + (addresses == null ? null : Arrays.asList(
                addresses)) +
                ", uri=" + uri +
                '}';
    }
}
