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
 * (common section for unicast and multicast).
 * As the Ovsdb implementations often use strings to represent IPs and MACs,
 * the values macString and ipString are provided for convenience.
 * Note ovsdb specs don't prevent mac and ip from being null.
 * @param uuid is UUID of the entry
 */
abstract class MacEntry(val uuid: UUID, val logicalSwitchId: UUID,
                        val mac: VtepMAC, val ip: IPv4Addr) {
    val macString = if (mac == null) null else mac.toString
    val ipString = if (ip == null) null else ip.toString

    /** Convenience method to distinguish between unicast and multicast */
    def isUcast: Boolean

    /** Reference to actual locators (a single locator for unicast, and
      * a locator set for multicast) */
    def locationId: UUID

    override def equals(o: Any): Boolean = o match {
        case null => false
        case that: MacEntry if isUcast != that.isUcast => false
        case that: MacEntry =>
            Objects.equals(logicalSwitchId, that.logicalSwitchId) &&
            Objects.equals(mac, that.mac) &&
            Objects.equals(ip, that.ip) &&
            Objects.equals(locationId, that.locationId)
        case other => false
    }

    override def hashCode: Int =
        if (uuid == null) Objects.hash(mac, ip, locationId, logicalSwitchId)
        else uuid.hashCode()

    /** Convenience method to filter multicast entries */
    def asMcast: McastMac = this match {
        case McastMac(_, _, _, _, _) => this.asInstanceOf[McastMac]
        case _ => throw new ClassCastException("not multicast: " + this.toString)
    }

    /** Convenience method to filter unicast entries */
    def asUcast: UcastMac = this match {
        case UcastMac(_, _, _, _, _) => this.asInstanceOf[UcastMac]
        case _ => throw new ClassCastException("not unicast: " + this.toString)
    }
}

/**
 * A class representing a VTEP unicast mac table entry
 */
final case class UcastMac(id: UUID, ls: UUID, macAddr: VtepMAC, ipAddr: IPv4Addr,
                          locator: UUID)
    extends MacEntry(id, ls, macAddr, ipAddr) {

    // Sanity check
    if (mac != null && !mac.isUcast)
        throw new IllegalArgumentException("not an unicast mac: " + mac)

    override def isUcast: Boolean = true
    override def locationId: UUID = locator

    override def toString: String = "UcastMac{" +
        "uuid=" + uuid + ", " +
        "logicalSwitch=" + logicalSwitchId + ", " +
        "mac='" + mac + "', " +
        "ip='" + ip + "', " +
        "locator=" + locator + "}"
}

object UcastMac {
    // TODO: Variations to deal with the many forms that data from ovsdb can take
    // These are useful to facilitate compatibility with existing ovsdb code,
    // but they will be removed once the code has been migrated to new ovsdb.

    def apply(id: UUID, ls: UUID, mac: String, ip: IPv4Addr, loc: UUID): UcastMac =
        new UcastMac(id, ls,
            if (mac == null || mac.isEmpty) null else VtepMAC.fromString(mac),
            ip, loc)
    def apply(id: UUID, ls: UUID, mac: VtepMAC, ip: String, loc: UUID): UcastMac =
        new UcastMac(id, ls, mac,
            if (ip == null || ip.isEmpty) null else IPv4Addr.fromString(ip),
            loc)
    def apply(id: UUID, ls: UUID, mac: String, ip:String, loc: UUID): UcastMac =
        apply(id, ls, mac,
            if (ip == null || ip.isEmpty) null else IPv4Addr.fromString(ip),
            loc)

    def apply(ls: UUID, mac: VtepMAC, ip: IPv4Addr, loc: UUID): UcastMac =
        apply(null, ls, mac, ip, loc)
    def apply(ls: UUID, mac: String, ip: IPv4Addr, loc: UUID): UcastMac =
        apply(null, ls, mac, ip, loc)
    def apply(ls: UUID, mac: VtepMAC, ip: String, loc: UUID): UcastMac =
        apply(null, ls, mac, ip, loc)
    def apply(ls: UUID, mac: String, ip: String, loc: UUID): UcastMac =
        apply(null, ls, mac, ip, loc)

    def apply(ls: UUID, mac: VtepMAC, loc: UUID): UcastMac =
        apply(null, ls, mac, null: IPv4Addr, loc)
}

/**
 * A class representing a VTEP unicast mac table entry
 */
final case class McastMac(id: UUID, ls: UUID, macAddr: VtepMAC, ipAddr: IPv4Addr,
                          locatorSet: UUID)
    extends MacEntry(id, ls, macAddr, ipAddr) {

    // Sanity check
    if (mac != null && mac.isUcast)
        throw new IllegalArgumentException("not a multicast mac: " + mac)

    override def isUcast: Boolean = false
    override def locationId: UUID = locatorSet

    override def toString: String = "McastMac{" +
        "uuid=" + uuid + ", " +
        "logicalSwitch=" + logicalSwitchId + ", " +
        "mac=" + mac + ", " +
        "ip=" + ip + ", " +
        "locatorSet=" + locatorSet + "}"
}

object McastMac {
    // TODO: Variations to deal with the many forms that data from ovsdb can take
    // These are useful to facilitate compatibility with existing ovsdb code,
    // but they will be removed once the code has been migrated to new ovsdb.

    def apply(id: UUID, ls: UUID, mac: String, ip: IPv4Addr, loc: UUID): McastMac =
        new McastMac(id, ls,
            if (mac == null || mac.isEmpty) null else VtepMAC.fromString(mac),
            ip, loc)
    def apply(id: UUID, ls: UUID, mac: VtepMAC, ip: String, loc: UUID): McastMac =
        new McastMac(id, ls, mac,
            if (ip == null || ip.isEmpty) null else IPv4Addr.fromString(ip),
            loc)
    def apply(id: UUID, ls: UUID, mac: String, ip:String, loc: UUID): McastMac =
        apply(id, ls, mac,
            if (ip == null || ip.isEmpty) null else IPv4Addr.fromString(ip),
            loc)

    def apply(ls: UUID, mac: VtepMAC, ip: IPv4Addr, loc: UUID): McastMac =
        apply(null, ls, mac, ip, loc)
    def apply(ls: UUID, mac: String, ip: IPv4Addr, loc: UUID): McastMac =
        apply(null, ls, mac, ip, loc)
    def apply(ls: UUID, mac: VtepMAC, ip: String, loc: UUID): McastMac =
        apply(null, ls, mac, ip, loc)
    def apply(ls: UUID, mac: String, ip: String, loc: UUID): McastMac =
        apply(null, ls, mac, ip, loc)

    def apply(ls: UUID, mac: VtepMAC, loc: UUID): McastMac =
        apply(null, ls, mac, null: IPv4Addr, loc)
}

