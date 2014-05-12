/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.api.neutron;


import org.midonet.cluster.data.neutron.Network;

import java.util.UUID;

public class NeutronDataProvider {

    private NeutronDataProvider() {}

    public static Network network() {
        return network(UUID.randomUUID());
    }

    public static Network network(UUID id) {
        Network net = new Network();
        net.id = id;
        return net;
    }

}
