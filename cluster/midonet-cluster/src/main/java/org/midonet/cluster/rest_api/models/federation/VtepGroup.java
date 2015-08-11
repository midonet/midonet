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

import com.fasterxml.jackson.annotation.JsonIgnore;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Federation;
import org.midonet.cluster.rest_api.models.UriResource;
import org.midonet.cluster.util.UUIDUtil.Converter;

@ZoomClass(clazz = Federation.VtepGroup.class)
public class VtepGroup extends UriResource {

    @ZoomField(name = "id", converter = Converter.class)
    public UUID id;

    @NotNull
    @ZoomField(name = "name")
    public String name;

    @ZoomField(name = "segment_id", converter = Converter.class)
    public List<UUID> vxlanSegmentIds;

    @ZoomField(name = "midonet_vtep_id", converter = Converter.class)
    public List<UUID> midonetVtepIds;

    @ZoomField(name = "ovsdb_vtep_id", converter = Converter.class)
    public List<UUID> ovsdbVtepIds;

    public VtepGroup() {
    }

    @Override
    public URI getUri() {
        return absoluteUri(Application.VTEP_GROUP, id);
    }

    public URI getVxlanSegments() {
        return relativeUri(Application.VXLAN_SEGMENT);
    }

    public URI getOvsdbVteps() {
        return relativeUri(Application.OVSDB_VTEP);
    }

    public URI getMidonetVteps() {
        return relativeUri(Application.MIDONET_VTEP);
    }

    @JsonIgnore
    @Override
    public void create() {
        if (null == id) {
            id = UUID.randomUUID();
        }
    }

    @JsonIgnore
    public void update(VtepGroup from) {
        this.id = from.id;
        vxlanSegmentIds = from.vxlanSegmentIds;
        midonetVtepIds = from.midonetVtepIds;
        ovsdbVtepIds = from.ovsdbVtepIds;
    }
}
