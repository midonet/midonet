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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.MoreObjects;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.rest_api.ResourceUris;
import org.midonet.cluster.util.UUIDUtil;

@ZoomClass(clazz = Topology.Vtep.Binding.class)
public class VtepBinding extends UriResource {

    @NotNull
    public UUID vtepId;

    @NotNull
    @ZoomField(name = "port_name")
    public String portName;

    @Min(0)
    @Max(4095)
    @ZoomField(name = "vlan_id")
    public short vlanId;

    @NotNull
    @ZoomField(name = "network_id", converter = UUIDUtil.Converter.class)
    public UUID networkId;

    @Override
    public URI getUri() {
        return absoluteUri(ResourceUris.VTEPS, vtepId,
                           ResourceUris.BINDINGS, portName,
                           Short.toString(vlanId));
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
            .omitNullValues()
            .add("vtepId", vtepId)
            .add("portName", portName)
            .add("vlanId", vlanId)
            .add("networkId", networkId)
            .toString();
    }
}
