/*
 * Copyright 2015 Midokura SARL
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

package org.midonet.cluster.rest_api.models;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;

import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.data.dhcp.ExtraDhcpOpt;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.rest_api.ResourceUris;
import org.midonet.cluster.util.IPAddressUtil;

@ZoomClass(clazz = Topology.Dhcp.Host.class)
public class DhcpHost extends UriResource {

    @ZoomField(name = "mac")
    public String macAddr;
    @ZoomField(name = "ip_address", converter = IPAddressUtil.Converter.class)
    public String ipAddr;
    @ZoomField(name = "name")
    public String name;

    @Nonnull
    @ZoomField(name = "extra_dhcp_opts")
    private List<ExtraDhcpOpt> extraDhcpOpts = new ArrayList<>();

    @Nonnull
    public List<ExtraDhcpOpt> getExtraDhcpOpts() {
        return extraDhcpOpts;
    }

    public void setExtraDhcpOpts(@Nonnull List<ExtraDhcpOpt> newOpts) {
        Preconditions.checkNotNull(newOpts,
            "Extra DHCP options should not be null. Use an empty list to "
           + "express the absense of them.");
        extraDhcpOpts.clear();
        extraDhcpOpts.addAll(newOpts);
    }

    @Override
    public URI getUri() {
        return absoluteUri(ResourceUris.DHCP_HOSTS, macAddr);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).omitNullValues()
            .add("macAddr", macAddr)
            .add("ipAddr", ipAddr)
            .add("name", name)
            .add("extraDhcpOpts", extraDhcpOpts)
            .toString();
    }
}
