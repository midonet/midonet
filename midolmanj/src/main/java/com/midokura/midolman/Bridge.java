/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midolman;

import java.util.UUID;

import com.midokura.midolman.layer3.Router;

public class Bridge implements ForwardingElement {

    @Override
    public void process(Router.ForwardInfo fwdInfo) {
        // TODO(pino): naive implementation - forward to Bridge's PortSet
        // TODO(pino): do we need to be able to list all the logical ports on
        // this bridge in order to pre-seed its Filtering Database with those
        // ports' addresses?
        // TODO(pino): do we need to be able to list all routers and dhcp
        // servers so that we can intercept ARP requests?
        // This information is not currently available in MM, only MM-mgmt.
    }

    @Override
    public void addPort(UUID portId) {

    }

    @Override
    public void removePort(UUID portId) {

    }
}
