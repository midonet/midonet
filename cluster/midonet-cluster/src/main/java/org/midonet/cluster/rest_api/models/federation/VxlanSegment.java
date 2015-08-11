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
import java.util.concurrent.atomic.AtomicInteger;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;

import com.fasterxml.jackson.annotation.JsonIgnore;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Federation;
import org.midonet.cluster.rest_api.models.UriResource;
import org.midonet.cluster.util.UUIDUtil.Converter;

@ZoomClass(clazz = Federation.VxlanSegment.class)
public class VxlanSegment extends UriResource {

    @ZoomField(name = "id", converter = Converter.class)
    public UUID id;

    @ZoomField(name = "name")
    public String name;

    @Min(0)
    @Max(2 ^ 24 - 1)
    @ZoomField(name = "vni")
    public int vni;

    @JsonIgnore
    @ZoomField(name = "midonet_binding", converter = Converter.class)
    public List<UUID> midonetBindingIds;

    @JsonIgnore
    @ZoomField(name = "ovsdb_binding", converter = Converter.class)
    public List<UUID> ovsdbBindingIds;

    public URI getMidonetBindings() {
        return relativeUri(Application.MIDONET_BINDINGS);
    }

    public URI getOvsdbBindings() {
        return relativeUri(Application.OVSDB_BINDINGS);
    }

    @Override
    public URI getUri() {
        return absoluteUri(Application.VXLAN_SEGMENTS, id);
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
    }
}
