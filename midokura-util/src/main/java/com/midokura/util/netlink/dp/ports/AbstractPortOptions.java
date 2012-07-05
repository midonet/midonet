/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.netlink.dp.ports;

import com.midokura.util.netlink.NetlinkMessage;
import com.midokura.util.netlink.dp.Port;

public abstract class AbstractPortOptions implements Port.Options {

    @Override
    public void processWithBuilder(NetlinkMessage.Builder builder) {

    }
}
