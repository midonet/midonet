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
package org.midonet.brain.southbound.vtep;

import com.google.common.base.Objects;

import org.midonet.packets.Ethernet;
import org.midonet.packets.MAC;

/**
 * A wrapper over the IEEE 802 MAC that supports the unknown-dst wildcard used
 * by the OVSDB VTEP schema.
 */
public final class VtepMAC {

    /**
     * This constant is defined in the OVSDB spec for the VTEP schema, it is
     * used to designate a "unknown-dst" mac in mcast_remote tables. Refer to
     * http://openvswitch.org/docs/vtep.5.pdf for further details.
     */
    private static final String S_UNKNOWN_DST = "unknown-dst";

    public static VtepMAC UNKNOWN_DST = new VtepMAC();

    public static VtepMAC fromString(String s) {
        return S_UNKNOWN_DST.equals(s) ? UNKNOWN_DST : new VtepMAC(s);
    }

    public static VtepMAC fromMac(MAC m) {
        return new VtepMAC(m);
    }

    private final MAC mac; // null means UNKNOWN-DST

    private VtepMAC() { this.mac = null; }

    private VtepMAC(String mac) {
        this.mac = MAC.fromString(mac);
    }

    private VtepMAC(final MAC mac) {
        this.mac = mac;
    }

    public boolean isMcast() {
        return mac == null || Ethernet.isMcast(mac);
    }

    public boolean isUcast() {
        return mac != null && mac.unicast();
    }

    @Override
    public String toString() {
        return mac == null ? S_UNKNOWN_DST : mac.toString();
    }

    @Override
    public int hashCode() {
        return mac == null ? 0 : mac.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        VtepMAC that = (VtepMAC) o;
        return Objects.equal(this.mac, that.mac);
    }

    /**
     * Returns an IEEE 802 representation of the MAC, or null when the wrapped
     * MAC is the VTEP's non-standard unknown-dst wildcard.
     */
    public MAC IEEE802() {
        return mac;
    }

    public boolean isIEEE802() {
        return this != UNKNOWN_DST;
    }

}
