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
import java.util.UUID;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

import org.codehaus.jackson.annotate.JsonIgnore;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.util.UUIDUtil;

@ZoomClass(clazz = Topology.VtepBinding.class)
public class VTEPBinding extends UriResource {

    @JsonIgnore
    @ZoomField(name = "id", converter = UUIDUtil.Converter.class)
    private UUID id;

    @JsonIgnore
    public String mgmtIp;

    @NotNull
    @ZoomField(name = "port_name")
    public String portName;

    @Min(0)
    @Max(4095)
    @ZoomField(name = "vlan_id")
    public short vlanId;

    @NotNull
    @ZoomField(name = "network_id")
    public UUID networkId;

    @JsonIgnore
    @ZoomField(name = "vtepId", converter = UUIDUtil.Converter.class)
    private UUID vtepId;

    @Override
    public URI getUri() {
        return absoluteUri(ResourceUris.VTEPS, mgmtIp,
                           ResourceUris.BINDINGS, portName,
                           Short.toString(vlanId));
    }

    @Override
    @JsonIgnore
    public void create() {
        if (null == id) {
            id = UUID.randomUUID();
        }
    }

    @JsonIgnore
    public void create(String mgmtIp) {
        create();
        this.mgmtIp = mgmtIp;
    }

    @JsonIgnore
    public void update(VTEPBinding from) {
        id = from.id;
        mgmtIp = from.mgmtIp;
        vtepId = from.id;
    }

}
