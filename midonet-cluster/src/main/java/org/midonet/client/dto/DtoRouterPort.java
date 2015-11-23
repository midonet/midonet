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

public class DtoRouterPort extends DtoPort {

    private String networkAddress;
    private int networkLength;
    private String portAddress;
    private String portMac;
    private URI bgps;
    private URI serviceContainer;

    public void setServiceContainer(URI uri) {
        this.serviceContainer = uri;
    }

    public URI getServiceContainer() {
        return this.serviceContainer;
    }

    @Override
    public String getNetworkAddress() {
        return networkAddress;
    }

    public void setNetworkAddress(String networkAddress) {
        this.networkAddress = networkAddress;
    }

    @Override
    public int getNetworkLength() {
        return networkLength;
    }

    public void setNetworkLength(int networkLength) {
        this.networkLength = networkLength;
    }

    @Override
    public String getPortAddress() {
        return portAddress;
    }

    public void setPortAddress(String portAddress) {
        this.portAddress = portAddress;
    }

    @Override
    public String getPortMac() {
        return portMac;
    }

    public void setPortMac(String portMac) {
        this.portMac = portMac;
    }

    @Override
    public URI getBgps() {
        return this.bgps;
    }

    public void setBgps(URI bgps) {
        this.bgps = bgps;
    }

    @Override
    public String getType() {
        return PortType.ROUTER;
    }

    @Override
    public boolean equals(Object other) {

        if (!super.equals(other)) {
            return false;
        }

        DtoRouterPort port = (DtoRouterPort) other;

        if (networkAddress != null ? !networkAddress
                .equals(port.networkAddress) : port.networkAddress != null) {
            return false;
        }

        if (networkLength != port.networkLength) {
            return false;
        }

        if (portAddress != null ? !portAddress.equals(port.portAddress)
                : port.portAddress != null) {
            return false;
        }

        if (portMac != null ? !portMac.equals(port.portMac)
                : port.portMac != null) {
            return false;
        }

        if (bgps != null ? !bgps.equals(port.bgps) : port.bgps != null) {
            return false;
        }

        return true;
    }
}
