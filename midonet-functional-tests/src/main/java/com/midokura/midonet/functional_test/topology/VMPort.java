/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test.topology;

import com.midokura.midonet.functional_test.mocks.MidolmanMgmt;
import com.midokura.midonet.client.dto.DtoExteriorRouterPort;

public class VMPort extends Port {

    VMPort(MidolmanMgmt mgmt, DtoExteriorRouterPort port, String name) {
        super(mgmt, port, name);
    }

}
