/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.smoketest.topology;

import com.midokura.midonet.smoketest.mgmt.DtoMaterializedRouterPort;
import com.midokura.midonet.smoketest.mocks.MidolmanMgmt;

public class Port {

    MidolmanMgmt mgmt;
    DtoMaterializedRouterPort port;
    String name;

    Port(MidolmanMgmt mgmt, DtoMaterializedRouterPort port, String name) {
        this.mgmt = mgmt;
        this.port = port;
        this.name = name;
    }

    public Route.Builder addRoute() {
        // TODO Auto-generated method stub
        return null;
    }

    public void delete() {
        mgmt.delete(port.getUri());
    }

    public String getName() {
        return name;
    }
}
