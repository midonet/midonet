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

package org.midonet.api.dhcp;

import org.midonet.api.RelativeUriResource;
import org.midonet.api.ResourceUriBuilder;
import org.midonet.cluster.data.dhcp.Subnet6;
import org.midonet.packets.IPv6Subnet;
import org.midonet.packets.IPv6Addr;
import org.midonet.packets.IPv6;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.xml.bind.annotation.XmlRootElement;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

@XmlRootElement
public class DhcpSubnet6 extends RelativeUriResource {

    @NotNull
    @Pattern(regexp = IPv6.regex,
             message = "is an invalid IP format")
    private String prefix;

    @Min(0)
    @Max(128)
    private int prefixLength;

    /* Default constructor is needed for parsing/unparsing. */
    public DhcpSubnet6() {
    }

    public DhcpSubnet6(String prefix, int prefixLength) {
        this.prefix = prefix;
        this.prefixLength = prefixLength;
    }

    public DhcpSubnet6(Subnet6 subnet) {
        this(subnet.getPrefix().getAddress().toString(),
                subnet.getPrefix().getPrefixLen());
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
        if (getUri() != null) {
            return ResourceUriBuilder.getDhcpV6Hosts(getUri());
        } else {
            return null;
        }
    }

    public URI getUri() {
        if (getParentUri() != null && prefix != null) {
            return ResourceUriBuilder.getBridgeDhcpV6(getParentUri(),
                    new IPv6Subnet(IPv6Addr.fromString(prefix), prefixLength));
        } else {
            return null;
        }
    }

    public Subnet6 toData() {

        IPv6Subnet prefix = new IPv6Subnet(IPv6Addr.fromString(this.prefix),
                                    this.prefixLength);

        return new Subnet6()
                .setPrefix(prefix);
    }

    @Override
    public String toString() {
        return "DhcpSubnet6{" + "prefix='" + prefix + ':'
                + prefixLength
                + '}';
    }
}
