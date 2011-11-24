/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.smoketest.topology;

import com.midokura.midonet.smoketest.mgmt.DtoMaterializedRouterPort;
import com.midokura.midonet.smoketest.mocks.MidolmanMgmt;

public class InternalPort extends Port {

    InternalPort(MidolmanMgmt mgmt, DtoMaterializedRouterPort port, String name) {
        super(mgmt, port, name);
    }
}
