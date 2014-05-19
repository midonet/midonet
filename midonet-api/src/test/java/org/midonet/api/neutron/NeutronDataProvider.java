/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.api.neutron;

import org.midonet.cluster.data.neutron.*;

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

    public static Router router() {
        return router(UUID.randomUUID());
    }

    public static Router router(UUID id) {
        Router r= new Router();
        r.id = id;
        return r;
    }
    
    public static SecurityGroup securityGroup() {
        return securityGroup(UUID.randomUUID());
    }

    public static SecurityGroup securityGroup(UUID id) {
        SecurityGroup sg = new SecurityGroup();
        sg.id = id;
        return sg;
    }

    public static SecurityGroupRule securityGroupRule() {
        return securityGroupRule(UUID.randomUUID());
    }

    public static SecurityGroupRule securityGroupRule(UUID id) {
        SecurityGroupRule rule = new SecurityGroupRule();
        rule.id = id;
        return rule;
    }
}
