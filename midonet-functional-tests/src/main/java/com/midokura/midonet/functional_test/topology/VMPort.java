/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test.topology;

import com.midokura.midonet.client.dto.DtoMaterializedRouterPort;
import com.midokura.midonet.functional_test.mocks.MidolmanMgmt;

public class VMPort extends Port {

    VMPort(MidolmanMgmt mgmt, DtoMaterializedRouterPort port, String name) {
        super(mgmt, port, name);
    }

}
