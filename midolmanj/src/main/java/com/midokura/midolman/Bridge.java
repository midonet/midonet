/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midolman;

import com.midokura.midolman.layer3.Router;

public class Bridge implements ForwardingElement {
    @Override
    public void process(Router.ForwardInfo fwdInfo) {
        // TODO(pino): naive implementation - forward to Bridge's PortSet
    }
}
