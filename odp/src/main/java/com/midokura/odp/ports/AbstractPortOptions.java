/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.odp.ports;

import com.midokura.netlink.NetlinkMessage;
import com.midokura.netlink.messages.BaseBuilder;
import com.midokura.odp.PortOptions;

public abstract class AbstractPortOptions implements PortOptions {

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
