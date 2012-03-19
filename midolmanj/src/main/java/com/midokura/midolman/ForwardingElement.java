/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midolman;

import com.midokura.midolman.layer3.Router;
import com.midokura.midolman.state.StateAccessException;

public interface ForwardingElement {

    void process(Router.ForwardInfo fwdInfo) throws StateAccessException;

}
