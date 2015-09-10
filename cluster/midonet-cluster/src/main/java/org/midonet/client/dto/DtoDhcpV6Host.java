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
import java.net.URI;

@XmlRootElement
public class DtoDhcpV6Host {
    protected String clientId;
    protected String fixedAddress;
    protected String name;
    private URI uri;

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getFixedAddress() {
        return fixedAddress;
    }

    public void setFixedAddress(String fixedAddress) {
        this.fixedAddress = fixedAddress;
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        DtoDhcpV6Host that = (DtoDhcpV6Host) o;

        if (fixedAddress != null ? !fixedAddress.equals(that.fixedAddress) : that.fixedAddress != null)
            return false;
        if (clientId != null
                ? !clientId.equals(that.clientId) : that.clientId != null)
            return false;
        if (name != null ? !name.equals(that.name) : that.name != null)
            return false;
        if (uri != null ? !uri.equals(that.uri) : that.uri != null)
            return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = clientId != null ? clientId.hashCode() : 0;
        result = 31 * result + (fixedAddress != null ? fixedAddress.hashCode() : 0);
        result = 31 * result + (name != null ? name.hashCode() : 0);
        result = 31 * result + (uri != null ? uri.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "DtoDhcpV6Host{" +
                "fixedAddress='" + fixedAddress + '\'' +
                ", clientId='" + clientId + '\'' +
                ", name='" + name + '\'' +
                ", uri=" + uri +
                '}';
    }
}
