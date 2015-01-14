/*
 * Copyright 2015 Midokura SARL
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

import java.util.{Objects, UUID}

import org.midonet.packets.IPv4Addr

/**
 * Represents a VTEP's mac table entry
 * @param uuid is UUID of the entry
 */
abstract class MacEntry(val uuid: UUID, val logicalSwitchId: UUID,
                        val mac: VtepMAC, val ipAddr: IPv4Addr) {
    val macString = mac.toString
    val ipString = if (ipAddr == null) null else ipAddr.toString

    def isUcast: Boolean
    def locationId: UUID

    override def equals(o: Any): Boolean = o match {
        case null => false
        case that: MacEntry if isUcast != that.isUcast => false
        case that: MacEntry =>
            Objects.equals(uuid, that.uuid) &&
            Objects.equals(logicalSwitchId, that.logicalSwitchId) &&
            Objects.equals(mac, that.mac) &&
            Objects.equals(ipAddr, that.ipAddr) &&
            Objects.equals(locationId, that.locationId)
        case other => false
    }

    override def hashCode: Int =
        Objects.hash(mac, ipAddr, locationId, logicalSwitchId)
}

final case class UcastMac(id: UUID, ls: UUID, macAddr: VtepMAC, ip: IPv4Addr,
                          locator: UUID)
    extends MacEntry(id, ls, macAddr, ip) {

    def this(ls: UUID, macAddr: VtepMAC, ip: IPv4Addr, loc: UUID) =
        this(null, ls, macAddr, ip, loc)

    // Sanity check
    if (mac == null || !mac.isUcast)
        throw new IllegalArgumentException("not an unicast mac: " + mac)

    override def isUcast: Boolean = true
    override def locationId: UUID = locator

    override def toString: String = "MacEntry{" +
        "uuid=" + uuid + ", " +
        "logicalSwitch=" + logicalSwitchId + ", " +
        "mac='" + mac + "', " +
        "ip='" + ip + "', " +
        "locator=" + locator + "}"
}

final case class McastMac(id: UUID, ls: UUID, macAddr: VtepMAC, ip: IPv4Addr,
                          locatorSet: UUID)
    extends MacEntry(id, ls, macAddr, ip) {

    def this(ls: UUID, macAddr: VtepMAC, ip: IPv4Addr, locSet: UUID) =
        this(null, ls, macAddr, ip, locSet)

    // Sanity check
    if (mac == null || mac.isUcast)
        throw new IllegalArgumentException("not a multicast mac: " + mac)

    override def isUcast: Boolean = false
    override def locationId: UUID = locatorSet

    override def toString: String = "MacEntry{" +
        "uuid=" + uuid + ", " +
        "logicalSwitch=" + logicalSwitchId + ", " +
        "mac=" + mac + ", " +
        "ip=" + ip + ", " +
        "locatorSet=" + locatorSet + "}"
}


