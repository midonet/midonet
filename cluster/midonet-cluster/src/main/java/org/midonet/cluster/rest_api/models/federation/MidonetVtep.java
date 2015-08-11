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

package org.midonet.cluster.rest_api.models.federation;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonIgnore;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Federation;
import org.midonet.cluster.rest_api.models.UriResource;
import org.midonet.cluster.rest_api.validation.MessageProperty;
import org.midonet.cluster.util.IPAddressUtil;
import org.midonet.cluster.util.UUIDUtil.Converter;
import org.midonet.packets.IPv4;

@ZoomClass(clazz = Federation.MidoNetVtep.class)
public class MidonetVtep extends UriResource {

    @ZoomField(name = "id", converter = Converter.class)
    public UUID id;

    @ZoomField(name = "name")
    public String name;

    @ZoomField(name = "username")
    public String username;

    @ZoomField(name = "password")
    public String password;

    @ZoomField(name = "group_id", converter = Converter.class)
    public UUID groupId;

    @ZoomField(name = "vtep_router_id", converter = Converter.class)
    public UUID vtepRouterId;

    @Size(min = 1)
    @Pattern(regexp = IPv4.regex, message = MessageProperty.IP_ADDR_INVALID)
    @ZoomField(name = "network_api_endpoint", converter = IPAddressUtil.Converter.class)
    public List<String> networkApiEndpoint;

    public MidonetVtep() {
    }

    @Override
    public URI getUri() {
        return absoluteUri(Application.MIDONET_VTEP, id);
    }

    @JsonIgnore
    @Override
    public void create() {
        if (null == id) {
            id = UUID.randomUUID();
        }
    }

    @JsonIgnore
    public void update(MidonetVtep from) {
        this.id = from.id;
        vtepRouterId = from.vtepRouterId;
        groupId = from.groupId;
    }
}
