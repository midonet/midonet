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

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.protobuf.Message;

import org.apache.commons.lang.StringUtils;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Federation;
import org.midonet.cluster.rest_api.models.UriResource;
import org.midonet.cluster.util.IPSubnetUtil;
import org.midonet.cluster.util.UUIDUtil.Converter;
import org.midonet.packets.IPSubnet;
import org.midonet.packets.IPv4;

@ZoomClass(clazz = Federation.VtepGroup.class)
public class VxlanSegment extends UriResource {

    @ZoomField(name = "id", converter = Converter.class)
    public UUID id;

    @ZoomField(name = "name")
    public String name;

    @ZoomField(name = "vni")
    public int vni;

    @ZoomField(name = "group_id", converter = Converter.class)
    public UUID groupId;

    @JsonIgnore
    @ZoomField(name = "subnet", converter = IPSubnetUtil.Converter.class)
    public IPSubnet<?> subnet;

    @NotNull
    @Pattern(regexp = IPv4.regex)
    public String subnetAddr;

    @Min(0)
    @Max(32)
    public int subnetLength;

    @ZoomField(name = "midonet_binding")
    public List<MidonetBinding> midonetBindings;

    @ZoomField(name = "ovsdb_binding")
    public List<OvsdbBinding> ovsdbBindings;

    public VxlanSegment() {
    }

    @JsonIgnore
    @Override
    public void afterFromProto(Message message) {
        if (null != subnet) {
            subnetAddr = subnet.getAddress().toString();
            subnetLength = subnet.getPrefixLen();
        }
    }

    @JsonIgnore
    @Override
    public void beforeToProto() {
        if (StringUtils.isNotEmpty(subnetAddr)) {
            subnet =
                IPSubnet.fromString(subnetAddr + "/" + subnetLength);
        }
    }

    @Override
    public URI getUri() {
        return absoluteUri(Application.VTEP_GROUP, id);
    }

    @JsonIgnore
    @Override
    public void create() {
        if (null == id) {
            id = UUID.randomUUID();
        }
    }

    @JsonIgnore
    public void update(VxlanSegment from) {
        this.id = from.id;
        groupId = from.groupId;
        vni = from.vni;
    }
}
