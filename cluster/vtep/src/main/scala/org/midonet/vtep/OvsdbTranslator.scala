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

package org.midonet.vtep

import java.util
import java.util.UUID

import org.opendaylight.ovsdb.lib.notation.{UUID => OvsdbUUID}

import org.midonet.packets.IPv4Addr

/**
 * Type translations to and from ovsdb-specific data models
 * This code also 'normalizes' sets and maps, so that null values from ovsdb
 * are converted into the corresponding empty collections. The guarantee of not
 * being null simplifies the rest of the code.
 * TODO: when converting to ovsdb, the new code should not provide null values
 * when a collection is required. The checks should be removed when the legacy
 * code is replaced by the new one.
 */
object OvsdbTranslator {

    /** Convert from Ovsdb UUIDs to Java UUIDs */
    def fromOvsdb(id: OvsdbUUID): UUID = UUID.fromString(id.toString)

    /** Convert from Java UUIDs to Ovsdb UUIDs */
    def toOvsdb(uuid: UUID): OvsdbUUID = new OvsdbUUID(uuid.toString)

    /** Convert a set of OvsdbUUIDs to a set of Java UUIDs
      * The input set can be a null value, resulting in an empty output set */
    def fromOvsdb(inSet: util.Set[_]): util.Set[UUID] = {
        val outSet = new util.HashSet[UUID]()
        if (inSet != null) {
            val it = inSet.iterator()
            while (it.hasNext) {
                val id = it.next().asInstanceOf[OvsdbUUID]
                outSet.add(fromOvsdb(id))
            }
        }
        outSet
    }

    /** Convert a set of Java UUIDs to a set of ovsdb UUIDs
      * The input set can be a null value, resulting in an empty output set */
    def toOvsdb(inSet: Set[UUID]): util.Set[OvsdbUUID] = {
        val outSet = new util.HashSet[OvsdbUUID]()
        if (inSet != null) {
            for (id: util.UUID <- inSet) {
                outSet.add(toOvsdb(id))
            }
        }
        outSet
    }

    /** Convert from (Long -> OvsdbUUID) to a map of (Integer -> java UUID)
      * The input map can be a null value, resulting in an empty output set
      * NOTE: according to the specs in
      * http://openvswitch.org/ovs-vswitchd.conf.db.5.pdf
      * all the maps in the hardware_vtep database tables that we are
      * interested in, are of the form integer -> UUID, with the key
      * being in the range 0-4095; therefore, even if the internal
      * representation in the ovsdb implementation uses a Long for the key,
      * it is fine to convert this value into an Integer, making it
      * compatible with the existing Midonet code. Note that this is not
      * true for other unused ovsdb tables (such as the maps in the
      * Logical_Router table) */
    def fromOvsdb(inMap: util.Map[_, _]): util.Map[Integer, UUID] = {
        val outMap = new util.HashMap[Integer, UUID]()
        if (inMap != null) {
            val it = inMap.entrySet().iterator()
            while (it.hasNext) {
                val e = it.next().asInstanceOf[util.Map.Entry[Long, OvsdbUUID]]
                outMap.put(e.getKey.toInt, fromOvsdb(e.getValue))
            }
        }
        outMap
    }

    /** Convert from (Integer -> java UUID) to a map of (Long -> OvsdbUUID)
      * The input map can be a null value, resulting in an empty output map */
    def toOvsdb(inMap: Map[Integer, util.UUID]): util.Map[Long, OvsdbUUID] = {
        val outMap = new util.HashMap[Long, OvsdbUUID]()
        if (inMap != null) {
            for (e <- inMap) {
                outMap.put(e._1.toLong, toOvsdb(e._2))
            }
        }
        outMap
    }

    /** Convert from a set of strings to a set of the ip addresses */
    def fromOvsdbIpSet(inSet: util.Set[_]): util.Set[IPv4Addr] = {
        val outSet = new util.HashSet[IPv4Addr]()
        if (inSet != null) {
            val it = inSet.iterator()
            while (it.hasNext) {
                val s = it.next().asInstanceOf[String]
                if (s.nonEmpty)
                    outSet.add(IPv4Addr.fromString(s))
            }
        }
        outSet
    }

    /** Convert from a set of IP addresses to a set of ovsdb strings */
    def toOvsdbIpSet(inSet: Set[IPv4Addr]): util.Set[String] = {
        val outSet = new util.HashSet[String]()
        if (inSet != null) {
            for (ip <- inSet) {
                outSet.add(ip.toString)
            }
        }
        outSet
    }
}
