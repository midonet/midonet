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

package org.midonet.vtep.model

import java.util.Objects

import org.midonet.packets.IPv4Addr
import org.opendaylight.ovsdb.lib.notation.UUID

/**
 * Represents a VTEP's mac table entry
 * @param uuid is UUID of the entry
 */
abstract class MacEntry(val uuid: UUID, val logicalSwitchId: UUID,
                        val mac: VtepMAC, val ip: IPv4Addr) {
    def isUcast: Boolean
    def locationId: UUID

    override def equals(o: Any): Boolean = o match {
        case null => false
        case that: MacEntry if isUcast =>
            Objects.equals(uuid, that.uuid) &&
                Objects.equals(logicalSwitchId, that.logicalSwitchId) &&
                Objects.equals(mac, that.mac) &&
                Objects.equals(ip, that.ip) &&
                Objects.equals(locationId, that.locationId)
        case other => false
    }
}

final class UcastMacEntry(id: UUID, ls: UUID, macAddr: VtepMAC,
                          ipAddr: IPv4Addr, val locator: UUID)
    extends MacEntry(id, ls, macAddr, ipAddr) {

    // Sanity check
    if (mac == null || !mac.isUcast)
        throw new IllegalArgumentException("not an unicast mac: " + mac)

    override def isUcast: Boolean = true
    override def locationId: UUID = locator

    override def toString: String = "MacEntry{" +
        "uuid=" + uuid + ", " +
        "logicalSwitch=" + logicalSwitchId + ", " +
        "mac=" + mac + ", " +
        "ip=" + ip + ", " +
        "locator=" + locator + "}"
}

final class McastMacEntry(id: UUID, ls: UUID, macAddr: VtepMAC,
                          ipAddr: IPv4Addr, val locatorSet: UUID)
    extends MacEntry(id, ls, macAddr, ipAddr) {

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

abstract class MacEntryUpdate(val oldEntryValue: MacEntry,
                              val newEntryValue: MacEntry) {
    def mac: VtepMAC =
        if (oldEntryValue != null && oldEntryValue.mac != null)
            oldEntryValue.mac
        else if (newEntryValue != null)
            newEntryValue.mac
        else
            null

    def logicalSwitchId =
        if (oldEntryValue != null && oldEntryValue.logicalSwitchId != null)
            oldEntryValue.logicalSwitchId
        else if (newEntryValue != null)
            newEntryValue.logicalSwitchId
        else
            null

    def oldEntry = oldEntryValue
    def newEntry = newEntryValue

    def isUcast = mac != null && mac.isUcast
    def isDeletion = newEntryValue == null
    def isAddition = oldEntryValue == null
}

case class UcastMacEntryUpdate(old: UcastMacEntry, cur: UcastMacEntry)
    extends MacEntryUpdate(old, cur)
case class McastMacEntryUpdate(old: McastMacEntry, cur: McastMacEntry)
    extends MacEntryUpdate(old, cur)

