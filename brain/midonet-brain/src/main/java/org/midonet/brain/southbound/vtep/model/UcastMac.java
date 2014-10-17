/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.brain.southbound.vtep.model;

import java.util.Objects;

import org.opendaylight.ovsdb.lib.notation.UUID;

import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;

/**
 * Represents an entry in any of the Ucast_Mac tables.
 */
public class UcastMac {

    public final String mac;
    public final UUID logicalSwitch;
    public final UUID locator;
    public final String ipAddr;

    public UcastMac(MAC mac, UUID logicalSwitch, UUID locator,
                    IPv4Addr ipAddr) {
        this(mac.toString(), logicalSwitch, locator, ipAddr.toString());
    }

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
        return "Ucast_Mac{" +
            "mac='" + mac + '\'' +
            ", logicalSwitch=" + logicalSwitch +
            ", locator=" + locator +
            ", ipAddr='" + ipAddr + '\'' +
            '}';
    }
}
