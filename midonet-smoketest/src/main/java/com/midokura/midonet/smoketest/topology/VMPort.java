/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.smoketest.topology;

import com.midokura.midonet.smoketest.mgmt.DtoMaterializedRouterPort;
import com.midokura.midonet.smoketest.mocks.MidolmanMgmt;

public class VMPort extends Port {

    VMPort(MidolmanMgmt mgmt, DtoMaterializedRouterPort port, String name) {
        super(mgmt, port, name);
    }

}
