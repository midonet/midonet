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
public class DtoDhcpSubnet6 {
    private String prefix;
    private int prefixLength;
    private URI hosts;
    private URI uri;

    public DtoDhcpSubnet6() {
    }

    public String getPrefix() {
        return prefix;
    }

    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }

    public int getPrefixLength() {
        return prefixLength;
    }

    public void setPrefixLength(int prefixLength) {
        this.prefixLength = prefixLength;
    }

    public URI getHosts() {
        return hosts;
    }

    public void setHosts(URI hosts) {
        this.hosts = hosts;
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

        DtoDhcpSubnet6 that = (DtoDhcpSubnet6) o;

        if (prefixLength != that.prefixLength) return false;
        if (hosts != null
                ? !hosts.equals(that.hosts)
                : that.hosts != null)
            return false;
        if (prefix != null
                ? !prefix.equals(that.prefix)
                : that.prefix != null)
            return false;
        if (uri != null
                ? !uri.equals(that.uri)
                : that.uri != null)
            return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = prefix != null ? prefix.hashCode() : 0;
        result = 31 * result + prefixLength;
        result = 31 * result + (hosts != null ? hosts.hashCode() : 0);
        result = 31 * result + (uri != null ? uri.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "DtoDhcpSubnet6{" +
                "prefix='" + prefix + '\'' +
                ", prefixLength=" + prefixLength + '\'' +
                ", hosts=" + hosts +
                ", uri=" + uri +
                '}';
    }
}
