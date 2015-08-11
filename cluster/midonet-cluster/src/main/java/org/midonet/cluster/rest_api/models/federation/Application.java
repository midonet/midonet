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

import javax.ws.rs.core.UriBuilder;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.midonet.cluster.rest_api.ResourceUris;
import org.midonet.cluster.rest_api.neutron.NeutronUriBuilder;
import org.midonet.cluster.rest_api.version.Version;

public class Application {

    @JsonIgnore
    private final URI root;
    public static final String VTEP_GROUP = "vtep_group";
    public static final String VXLAN_SEGMENT = "vxlan_segment";
    public static final String MIDONET_VTEP = "midonet_vtep";
    public static final String OVSDB_VTEP = "ovsdb_vtep";

    public Application(URI root)  {
        this.root = root;
    }

    private URI uriFor(String s) {
        return UriBuilder.fromUri(root).segment(s).build();
    }

    private String templateFor(String s) {
        return uriFor(s).toString() + "/{id}";
    }

    public URI getUri() {
        return root;
    }

    @JsonProperty("version")
    public String getVersion() {
        return Version.CURRENT;
    }

    @JsonProperty(VTEP_GROUP)
    public URI getVtepGroups() {
        return uriFor(VTEP_GROUP);
    }

    @JsonProperty(VXLAN_SEGMENT)
    public URI getVxlanSegments() {
        return uriFor(VXLAN_SEGMENT);
    }

    @JsonProperty(MIDONET_VTEP)
    public URI getMidonetVteps() {
        return uriFor(MIDONET_VTEP);
    }

    @JsonProperty(OVSDB_VTEP)
    public URI getOvsdbVteps() {
        return uriFor(OVSDB_VTEP);
    }

    @JsonProperty(VTEP_GROUP + "Template")
    public String getVtepGroupTemplate() {
        return templateFor(VTEP_GROUP);
    }

    @JsonProperty(VXLAN_SEGMENT + "Template")
    public String getVxlanSegmentTemplate() {
        return templateFor(VXLAN_SEGMENT);
    }

    @JsonProperty(MIDONET_VTEP + "Template")
    public String getMidonetVtepTemplate() {
        return templateFor(MIDONET_VTEP);
    }

    @JsonProperty(OVSDB_VTEP + "Template")
    public String getOvsdbVtepTemplate() {
        return templateFor(OVSDB_VTEP);
    }
}
