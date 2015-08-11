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
import java.util.UUID;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;

import com.fasterxml.jackson.annotation.JsonIgnore;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Federation;
import org.midonet.cluster.rest_api.models.UriResource;
import org.midonet.cluster.rest_api.validation.MessageProperty;
import org.midonet.cluster.util.IPAddressUtil;
import org.midonet.cluster.util.UUIDUtil.Converter;
import org.midonet.packets.IPv4;

@ZoomClass(clazz = Federation.OvsdbVtep.class)
public class OvsdbVtep extends UriResource {

    @ZoomField(name = "id", converter = Converter.class)
    public UUID id;

    @NotNull
    @ZoomField(name = "name")
    public String name;

    @NotNull
    @Pattern(regexp = IPv4.regex, message = MessageProperty.IP_ADDR_INVALID)
    @ZoomField(name = "management_ip", converter = IPAddressUtil.Converter.class)
    public String managementIp;

    @Min(1)
    @Max(65535)
    @ZoomField(name = "management_port")
    public int managementPort;

    @ZoomField(name = "group_id", converter = Converter.class)
    public UUID groupId;

    public OvsdbVtep() {
    }

    @Override
    public URI getUri() {
        return absoluteUri(Application.OVSDB_VTEP, id);
    }

    @JsonIgnore
    @Override
    public void create() {
        if (null == id) {
            id = UUID.randomUUID();
        }
    }

    @JsonIgnore
    public void update(OvsdbVtep from) {
        this.id = from.id;
        this.groupId = from.groupId; // Don't allow changing the group.
    }
}
