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
