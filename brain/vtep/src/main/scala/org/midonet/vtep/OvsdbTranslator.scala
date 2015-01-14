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

import scala.collection.JavaConversions.{asScalaSet, mapAsJavaMap, mapAsScalaMap, setAsJavaSet}

import org.opendaylight.ovsdb.lib.notation.{UUID => OvsdbUUID}

import org.midonet.packets.IPv4Addr

/**
 * Type translations to and from ovsdb-specific data models
 */
object OvsdbTranslator {

    /** Convert from Ovsdb UUIDs to Java UUIDs */
    def fromOvsdb(id: OvsdbUUID): util.UUID = util.UUID.fromString(id.toString)

    /** Convert from Java UUIDs to Ovsdb UUIDs */
    def toOvsdb(uuid: util.UUID): OvsdbUUID = new OvsdbUUID(uuid.toString)

    /** Convert a set of OvsdbUUIDs to a set of Java UUIDs
      * The input set can be a null value, resulting in an empty * output set */
    def fromOvsdb(inSet: util.Set[_]): util.Set[util.UUID] =
        if (inSet == null) new util.HashSet[util.UUID]()
        else setAsJavaSet(asScalaSet[OvsdbUUID](
            inSet.asInstanceOf[util.Set[OvsdbUUID]]).map(fromOvsdb))

    /** Convert a set of Java UUIDs to a set of ovsdb UUIDs
      * The input set can be a null value, resulting in an empty output set */
    def toOvsdb(inSet: Set[util.UUID]): util.Set[OvsdbUUID] =
        if (inSet == null) new util.HashSet[OvsdbUUID]()
        else setAsJavaSet(inSet.map(toOvsdb))

    /** Convert from (Long -> OvsdbUUID) to a map of (Integer -> java UUID)
     * The input map can be a null value, resulting in an empty output set */
    def fromOvsdb(inMap: util.Map[_, _]): util.Map[Integer, util.UUID] =
        if (inMap == null) new util.HashMap[Integer, util.UUID]()
        else mapAsJavaMap(
            mapAsScalaMap(inMap.asInstanceOf[util.Map[Long, OvsdbUUID]])
                .map(e => (e._1.toInt.asInstanceOf[Integer], fromOvsdb(e._2)))
                .toMap
        )

    /** Convert from (Integer -> java UUID) to a map of (Long -> OvsdbUUID)
      * The input map can be a null value, resulting in an empty output map */
    def toOvsdb(inMap: Map[Integer, util.UUID]): util.Map[Long, OvsdbUUID] =
        if (inMap == null) new util.HashMap[Long, OvsdbUUID]()
        else mapAsJavaMap(inMap.map(e => (e._1.toLong, toOvsdb(e._2))))

    /** Convert from a set of strings to a set of the ip addresses */
    def fromOvsdbIpSet(inSet: util.Set[_]): util.Set[IPv4Addr] =
        if (inSet == null) new util.HashSet[IPv4Addr]()
        else setAsJavaSet(
            asScalaSet(inSet.asInstanceOf[util.Set[String]]).collect({
                case s: String if s.nonEmpty => IPv4Addr.fromString(s)
            })
        )

    /** Convert from a set of IP addresses to a set of ovsdb strings */
    def toOvsdbIpSet(inSet: Set[IPv4Addr]): util.Set[String] =
        if (inSet == null) new util.HashSet[String]()
        else setAsJavaSet(inSet.collect{case s: IPv4Addr => s.toString})
}
