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
    public static final String VXLAN_SEGMENTS = "vxlan_segments";
    public static final String MIDONET_VTEPS = "midonet_vteps";
    public static final String MIDONET_BINDINGS = "midonet_bindings";
    public static final String OVSDB_VTEPS = "ovsdb_vteps";
    public static final String OVSDB_BINDINGS = "ovsdb_bindings";

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

    @JsonProperty("systemState")
    public URI getSystemState() {
        return uriFor(ResourceUris.SYSTEM_STATE);
    }

    @JsonProperty("vxlanSegments")
    public URI getVxlanSegments() {
        return uriFor(VXLAN_SEGMENTS);
    }

    @JsonProperty("midonetBindings")
    public URI getMidonetBindings() {
        return uriFor(MIDONET_BINDINGS);
    }

    @JsonProperty("midonetVteps")
    public URI getMidonetVteps() {
        return uriFor(MIDONET_VTEPS);
    }

    @JsonProperty("ovsdbBindings")
    public URI getOvsdbBindings() {
        return uriFor(OVSDB_BINDINGS);
    }

    @JsonProperty("ovsdbVteps")
    public URI getOvsdbVteps() {
        return uriFor(OVSDB_VTEPS);
    }

    @JsonProperty("vxlanSegmentTemplate")
    public String getVxlanSegmentTemplate() {
        return templateFor(VXLAN_SEGMENTS);
    }

    @JsonProperty("midonetBindingTemplate")
    public String getMidonetBindingTemplate() {
        return templateFor(MIDONET_BINDINGS);
    }

    @JsonProperty("midonetVtepTemplate")
    public String getMidonetVtepTemplate() {
        return templateFor(MIDONET_VTEPS);
    }

    @JsonProperty("ovsdbBindingTemplate")
    public String getOvsdbBindingTemplate() {
        return templateFor(OVSDB_BINDINGS);
    }

    @JsonProperty("ovsdbVtepTemplate")
    public String getOvsdbVtepTemplate() {
        return templateFor(OVSDB_VTEPS);
    }
}
