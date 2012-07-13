/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.netlink.dp.ports;

import com.midokura.util.netlink.NetlinkMessage;
import com.midokura.util.netlink.dp.Port;
import com.midokura.util.netlink.messages.BaseBuilder;

public abstract class AbstractPortOptions implements Port.Options {

    @Override
    public void serialize(BaseBuilder builder) {

    }

    @Override
    public boolean deserialize(NetlinkMessage message) {
        return true;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        return true;
    }

    public String toString() {
        return getClass().getName() + "{}";
    }
}
