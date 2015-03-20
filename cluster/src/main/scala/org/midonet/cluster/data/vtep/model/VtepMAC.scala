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

package org.midonet.cluster.data.vtep.model

import com.google.common.base.Objects

import org.midonet.packets.{Ethernet, MAC}

/**
 * A wrapper over the IEEE 802 MAC that supports the unknown-dst wildcard used
 * by the OVSDB VTEP schema.
 */
final class VtepMAC (val mac: MAC) {
    import VtepMAC._
    private val str: String = if (mac == null) S_UNKNOWN_DST else mac.toString

    def this(macString: String) = this(MAC.fromString(macString))
    def this() = this(null: MAC) // null means UNKNOWN-DST

    def isMcast: Boolean = mac == null || Ethernet.isMcast(mac)

    def isUcast: Boolean = mac != null && mac.unicast

    override def toString: String = str

    override def hashCode: Int =
        if (mac == null) 0 else mac.hashCode

    override def equals(o: Any): Boolean = o match {
        case null => false
        case m: VtepMAC => Objects.equal(m.mac, mac)
        case m: MAC => Objects.equal(m, mac)
        case other => false
    }

    /**
     * Returns an IEEE 802 representation of the MAC, or null when the wrapped
     * MAC is the VTEP's non-standard unknown-dst wildcard.
     */
    def IEEE802: MAC = mac
    def isIEEE802: Boolean = this != UNKNOWN_DST
}

object VtepMAC {
    /**
     * This constant is defined in the OVSDB spec for the VTEP schema, it is
     * used to designate a "unknown-dst" mac in mcast_remote tables. Refer to
     * http://openvswitch.org/docs/vtep.5.pdf for further details.
     */
    private final val S_UNKNOWN_DST = "unknown-dst"

    final val UNKNOWN_DST = new VtepMAC()

    def fromString(s: String): VtepMAC =
        if (S_UNKNOWN_DST.equals(s)) UNKNOWN_DST else new VtepMAC(s)

    def fromMac(m: MAC): VtepMAC = new VtepMAC(m)
}

