/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.brain.southbound.vtep.model;

import java.util.Objects;

import org.opendaylight.ovsdb.lib.notation.UUID;

/**
 * Represents an entry in any of the Ucast_Mac tables.
 */
public class UcastMac {

    public final String mac;
    public final UUID logicalSwitch;
    public final UUID locator;
    public final String ipAddr;

    public UcastMac(String mac, UUID logicalSwitch, UUID locator,
                    String ipAddr) {
        this.mac = mac;
        this.logicalSwitch = logicalSwitch;
        this.locator = locator;
        this.ipAddr = ipAddr;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        UcastMac that = (UcastMac) o;

        return Objects.equals(ipAddr, that.ipAddr) &&
               Objects.equals(mac, that.mac) &&
               Objects.equals(logicalSwitch, that.logicalSwitch) &&
               Objects.equals(locator, that.locator);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mac, ipAddr, locator, logicalSwitch);
    }

    @Override
    public String toString() {
        return "Mcast_Mac{" +
            "mac='" + mac + '\'' +
            ", logicalSwitch=" + logicalSwitch +
            ", locator=" + locator +
            ", ipAddr='" + ipAddr + '\'' +
            '}';
    }
}