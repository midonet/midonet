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

import java.util.{Objects, Set}

import org.midonet.packets.IPv4Addr
import org.opendaylight.ovsdb.lib.notation.UUID

/**
 * Represents a VTEP's mac table entry
 * @param uuid is UUID of the entry
 */
abstract class MacEntry(val uuid: UUID, val logicalSwitchId: UUID,
                        val mac: VtepMAC, val ip: IPv4Addr) {
    override def hashCode: Int = uuid.hashCode
}

final class UcastMacEntry(id: UUID, ls: UUID, macAddr: VtepMAC,
                          ipAddr: IPv4Addr, val locator: UUID)
    extends MacEntry(id, ls, macAddr, ipAddr) {

    // Sanity check
    if (mac != null && !mac.isIEEE802)
        throw new IllegalArgumentException("not an ucast mac: " + mac)

    override def toString: String = "MacEntry{" +
        "uuid=" + uuid + ", " +
        "logicalSwitch=" + logicalSwitchId + ", " +
        "mac=" + mac + ", " +
        "ip=" + ip + ", " +
        "locator=" + locator + "}"

    override def equals(o: Any): Boolean = o match {
        case null => false
        case that: UcastMacEntry =>
            Objects.equals(uuid, that.uuid) &&
            Objects.equals(logicalSwitchId, that.logicalSwitchId) &&
            Objects.equals(mac, that.mac) &&
            Objects.equals(ip, that.ip) &&
            Objects.equals(locator, that.locator)
        case other => false
    }
}

final class McastMacEntry(id: UUID, ls: UUID, macAddr: VtepMAC,
                          ipAddr: IPv4Addr, val locatorSet: UUID)
    extends MacEntry(id, ls, macAddr, ipAddr) {

    // Sanity check
    if (mac != null && mac.isIEEE802)
        throw new IllegalArgumentException("not an mcast mac: " + mac)

    override def toString: String = "MacEntry{" +
        "uuid=" + uuid + ", " +
        "logicalSwitch=" + logicalSwitchId + ", " +
        "mac=" + mac + ", " +
        "ip=" + ip + ", " +
        "locatorSet=" + locatorSet + "}"

    override def equals(o: Any): Boolean = o match {
        case null => false
        case that: McastMacEntry =>
            Objects.equals(uuid, that.uuid) &&
            Objects.equals(logicalSwitchId, that.logicalSwitchId) &&
            Objects.equals(mac, that.mac) &&
            Objects.equals(ip, that.ip) &&
            Objects.equals(locatorSet, that.locatorSet)
        case other => false
    }
}

abstract class MacEntryUpdate(val oldEntryValue: _ <: MacEntry,
                              val newEntryValue: _ <: MacEntry) {
    def mac: VtepMAC =
        if (oldEntry != null && oldEntry.mac != null)
            oldEntry.mac
        else if (newEntry != null)
            newEntry.mac
        else
            null

    def logicalSwitchId =
        if (oldEntry != null && oldEntry.logicalSwitchId != null)
            oldEntry.logicalSwitchId
        else if (newEntry != null)
            newEntry.logicalSwitchId
        else
            null

    def oldEntry = oldEntryValue
    def newEntry = newEntryValue
}

case class UcastMacEntryUpdate(old: UcastMacEntry, cur: UcastMacEntry)
    extends MacEntryUpdate(old, cur)
case class McastMacEntryUpdate(old: McastMacEntry, cur: McastMacEntry)
    extends MacEntryUpdate(old, cur)

