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
package org.midonet.cluster.rest_api;

import java.net.URI;
import java.util.UUID;

import javax.ws.rs.core.UriBuilder;

/**
 * We're keeping this one around as many tests still depend on it and the
 * client DTOs can't provide the URIS.  Client DTOs should be replaced with
 * the ordinary DTOs provided in cluster, and then the URIs can be generated
 * by each DTO.
 */
@Deprecated
public class ResourceUriBuilder {

    public static final String ROOT = "/";
    public static final String PORTS = "/ports";           // exterior ports
    public static final String HOSTS = "/hosts";
    public static final String VTEP_BINDINGS = "/vtep_bindings";

    private ResourceUriBuilder() {
    }

    public static URI getRoot(URI baseUri) {
        return UriBuilder.fromUri(baseUri).path(ROOT).build();
    }

    public static URI getPorts(URI baseUri) {
        return UriBuilder.fromUri(getRoot(baseUri)).path(PORTS).build();
    }

    public static URI getPort(URI baseUri, UUID portId) {
        return UriBuilder.fromUri(getPorts(baseUri)).path(portId.toString())
                .build();
    }

    public static URI getHosts(URI baseUri) {
        return UriBuilder.fromUri(getRoot(baseUri)).path(HOSTS).build();
    }

    public static URI getHost(URI baseUri, UUID hostId) {
        return UriBuilder.fromUri(getHosts(baseUri)).path(hostId.toString())
                .build();
    }

    public static URI getVxLanPortBindings(URI baseUri, UUID vxLanPortId) {
        return UriBuilder.fromUri(getPort(baseUri, vxLanPortId))
                .path(VTEP_BINDINGS).build();
    }

    public static URI getVxLanPortBinding(URI baseUri, UUID vxLanPortId,
                                          String portName, short vlanId) {
        return UriBuilder.fromUri(getPort(baseUri, vxLanPortId))
                .path(VTEP_BINDINGS)
                .segment(portName)
                .path(Short.toString(vlanId))
                .build();
    }


}
