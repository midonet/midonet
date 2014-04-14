/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.brain.southbound.vtep.model;

import java.util.Objects;

import org.opendaylight.ovsdb.lib.notation.UUID;

/**
 * Represents an entry in any of the Mcast_Mac tables.
 */
public class McastMac {

    public final String mac;
    public final UUID logicalSwitch;
    public final UUID locatorSet;
    public final String ipAddr;

    public McastMac(String mac, UUID logicalSwitch, UUID locatorSet,
                    String ipAddr) {
        this.mac = mac;
        this.logicalSwitch = logicalSwitch;
        this.locatorSet = locatorSet;
        this.ipAddr = ipAddr;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        McastMac that = (McastMac) o;

        return Objects.equals(ipAddr, that.ipAddr) &&
               Objects.equals(mac, that.mac) &&
               Objects.equals(logicalSwitch, that.logicalSwitch) &&
               Objects.equals(locatorSet, that.locatorSet);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mac, ipAddr, locatorSet, logicalSwitch);
    }

    @Override
    public String toString() {
        return "Mcast_Mac{" +
            "mac='" + mac + '\'' +
            ", logicalSwitch=" + logicalSwitch +
            ", locatorSet=" + locatorSet +
            ", ipAddr='" + ipAddr + '\'' +
            '}';
    }
}
