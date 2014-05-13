/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.api.neutron;

import org.midonet.cluster.data.neutron.Network;
import org.midonet.cluster.data.neutron.Port;
import org.midonet.cluster.data.neutron.Subnet;

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

    public static Subnet subnet() {
        return subnet(UUID.randomUUID());
    }

    public static Subnet subnet(UUID id) {
        Subnet sub = new Subnet();
        sub.id = id;
        return sub;
    }

    public static Port port() {
        return port(UUID.randomUUID());
    }

    public static Port port(UUID id) {
        Port p = new Port();
        p.id = id;
        return p;
    }
}
