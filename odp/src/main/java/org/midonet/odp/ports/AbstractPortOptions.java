/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.odp.ports;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.messages.BaseBuilder;
import org.midonet.odp.PortOptions;

public abstract class AbstractPortOptions implements PortOptions {

    @Override
    public void serialize(BaseBuilder<?,?> builder) {
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
