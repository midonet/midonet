/*
* Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
*/
package org.midonet.api.neutron;


import org.midonet.api.ResourceUriBuilder;

import javax.ws.rs.core.UriBuilder;
import java.net.URI;
import java.util.UUID;

public class NeutronUriBuilder {

    public final static String NETWORKS = "/networks";

    public static URI getNeutron(URI baseUri) {
        return UriBuilder.fromUri(ResourceUriBuilder.getRoot(
                baseUri)).path(ResourceUriBuilder.NEUTRON).build();
    }

    // Network

    public static URI getNetworks(URI baseUri) {
        return UriBuilder.fromUri(getNeutron(baseUri)).path(NETWORKS).build();
    }

    public static URI getNetwork(URI baseUri, UUID id) {
        return UriBuilder.fromUri(
                getNetworks(baseUri)).path(id.toString()).build();
    }

    public static String getNetworkTemplate(URI baseUri) {
        return ResourceUriBuilder.buildIdTemplateUri(getNetworks(baseUri));
    }

}
