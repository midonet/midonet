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

import javax.validation.constraints.NotNull;
import javax.xml.bind.annotation.XmlRootElement;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.rest_api.ResourceUris;
import org.midonet.cluster.util.IPAddressUtil;

@ZoomClass(clazz = Topology.DhcpV6.Host.class)
public class DhcpV6Host extends UriResource {

    @NotNull
    @ZoomField(name = "client_id")
    public String clientId;
    @NotNull
    @ZoomField(name = "ip_address", converter = IPAddressUtil.Converter.class)
    public String fixedAddress;
    @NotNull
    @ZoomField(name = "name")
    public String name;

    public DhcpV6Host() { }

    @Override
    public URI getUri() {
        return absoluteUri(ResourceUris.DHCPV6_HOSTS(), clientIdToUri(clientId));
    }

    public static String clientIdToUri(String clientId) {
        return clientId.replace(':', '-');
    }

    public static String clientIdFromUri(String clientId) {
        return clientId.replace('-', ':');
    }


}

