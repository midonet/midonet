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

import java.lang.reflect.Type;
import java.net.URI;

import javax.xml.bind.annotation.XmlRootElement;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomConvert;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Commons;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.rest_api.annotation.Resource;
import org.midonet.cluster.rest_api.annotation.ResourceId;
import org.midonet.cluster.util.IPAddressUtil;
import org.midonet.cluster.util.MACUtil;
import org.midonet.packets.MAC;

@XmlRootElement
@Resource(name = ResourceUris.DHCP_HOSTS, parents = { DhcpSubnet.class })
@ZoomClass(clazz = Topology.Dhcp.Host.class)
public class DhcpHost extends UriResource {

    @ResourceId
    @ZoomField(name = "mac")
    public String macAddr;
    @ZoomField(name = "ip_address", converter = IPAddressUtil.Converter.class)
    public String ipAddr;
    @ZoomField(name = "name")
    public String name;

    public DhcpHost() {
        this(null);
    }

    public DhcpHost(URI baseUri) {
        super(baseUri);
    }
}
