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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement
public class DtoDhcpSubnet {
    private String subnetPrefix;
    private int subnetLength;
    private String defaultGateway;
    private String serverAddr;
    private List<String> dnsServerAddrs;
    private int interfaceMTU;
    private List<DtoDhcpOption121> opt121Routes;
    private URI hosts;
    private URI uri;
    private Boolean enabled = true;

    public DtoDhcpSubnet() {
        this.opt121Routes = new ArrayList<>();
    }

    public String getSubnetPrefix() {
        return subnetPrefix;
    }

    public void setSubnetPrefix(String subnetPrefix) {
        this.subnetPrefix = subnetPrefix;
    }

    public int getSubnetLength() {
        return subnetLength;
    }

    public void setSubnetLength(int subnetLength) {
        this.subnetLength = subnetLength;
    }

    public String getDefaultGateway() {
        return defaultGateway;
    }

    public void setDefaultGateway(String defaultGateway) {
        this.defaultGateway = defaultGateway;
    }

    public String getServerAddr() {
        return serverAddr;
    }

    public void setServerAddr(String serverAddr) {
        this.serverAddr = serverAddr;
    }

    public List<String> getDnsServerAddrs() {
        return dnsServerAddrs;
    }

    public void setDnsServerAddrs(List<String> dnsServerAddrs) {
        this.dnsServerAddrs = dnsServerAddrs;
    }

    public int getInterfaceMTU() {
        return interfaceMTU;
    }

    public void setInterfaceMTU(int interfaceMTU) {
        this.interfaceMTU = interfaceMTU;
    }

    public List<DtoDhcpOption121> getOpt121Routes() {
        return opt121Routes;
    }

    public void setOpt121Routes(List<DtoDhcpOption121> opt121Routes) {
        this.opt121Routes = opt121Routes;
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

    public Boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        DtoDhcpSubnet that = (DtoDhcpSubnet) o;

        if (subnetLength != that.subnetLength) return false;
        if (defaultGateway != null
                ? !defaultGateway.equals(that.defaultGateway)
                : that.defaultGateway != null)
            return false;
        if (hosts != null
                ? !hosts.equals(that.hosts)
                : that.hosts != null)
            return false;
        if (opt121Routes != null
                ? !opt121Routes.equals(that.opt121Routes)
                : that.opt121Routes != null)
            return false;
        if (subnetPrefix != null
                ? !subnetPrefix.equals(that.subnetPrefix)
                : that.subnetPrefix != null)
            return false;
        if (uri != null
                ? !uri.equals(that.uri)
                : that.uri != null)
            return false;
        if (serverAddr != null
                ? !serverAddr.equals(that.serverAddr)
                : that.serverAddr != null)
            return false;
        if (dnsServerAddrs != null
                ? !dnsServerAddrs.equals(that.dnsServerAddrs)
                : that.dnsServerAddrs!= null)
            return false;
        if (interfaceMTU != that.interfaceMTU)
            return false;

        if (!Objects.equals(enabled, that.enabled))
            return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = subnetPrefix != null ? subnetPrefix.hashCode() : 0;
        result = 31 * result + subnetLength;
        result = 31 * result + (defaultGateway != null
                ? defaultGateway.hashCode() : 0);
        result = 31 * result + (serverAddr != null
                ? serverAddr.hashCode() : 0);
        result = 31 * result + (dnsServerAddrs != null
                ? dnsServerAddrs.hashCode() : 0);
        result = 31 * result + interfaceMTU;
        result = 31 * result + (opt121Routes != null
                ? opt121Routes.hashCode() : 0);
        result = 31 * result + (hosts != null ? hosts.hashCode() : 0);
        result = 31 * result + (uri != null ? uri.hashCode() : 0);
        result = 31 * result + (enabled != null ? enabled.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "DtoDhcpSubnet{" +
                "defaultGateway='" + defaultGateway + '\'' +
                ", subnetPrefix='" + subnetPrefix + '\'' +
                ", subnetLength=" + subnetLength + '\'' +
                ", serverAddr='" + serverAddr + '\'' +
                ", dnsServerAddrs='" + dnsServerAddrs + '\'' +
                ", interfaceMTU=" + interfaceMTU +
                ", opt121Routes=" + opt121Routes +
                ", hosts=" + hosts +
                ", enabled=" + enabled +
                ", uri=" + uri +
                '}';
    }
}
